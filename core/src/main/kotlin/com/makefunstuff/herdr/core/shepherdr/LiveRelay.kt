package com.makefunstuff.herdr.core.shepherdr

import com.makefunstuff.herdr.core.ssh.Duplex
import com.makefunstuff.herdr.core.ssh.HerdrCommands.quote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import kotlin.concurrent.thread

/** A live link's text messages both ways: Shepherdr's own WebSocket ([WebSocketLink]), or [LiveRelay]'s. */
interface LiveSocket : java.io.Closeable {
    /** The next whole message; null once the link closed. Blocks until one is in. */
    fun receive(): String?

    fun send(text: String)
}

/**
 * Shepherdr's live link held on its own machine, by its `bun` (or `node`) over an SSH command, its events
 * passed on as lines. Shepherdr sends every live link its whole snapshot (most of a megabyte with many tasks)
 * on each change, often a few times a second, and closes a link that falls a megabyte behind: over a phone's
 * connection a chat's news waited behind snapshots, or never came. The relay reads them at the machine's own
 * speed and passes on only the newest of each kind (a chat's, the snapshot), only when it changed, and only
 * as fast as the phone takes them; without [snapshots] (a chat screen's link) none at all. Shepherdr 3's named views
 * (`view`: its window's own shapes, a few hundred KB of task rows) are the web app's alone: never passed on.
 */
class RelayLink internal constructor(private val process: Duplex, private val reader: BufferedReader) : LiveSocket {
    @Volatile private var closed = false
    private var any = false

    override fun receive(): String? {
        if (closed) return null
        val line = try {
            reader.readLine()
        } catch (e: IOException) {
            if (closed) null else throw e
        }
        if (line == null) {
            close()
            // Ended before Shepherdr sent anything: it isn't there to link to, as its own link would have said.
            if (!any) throw IOException("Shepherdr refused the live link")
        }
        any = true
        return line
    }

    @Synchronized
    override fun send(text: String) {
        if (closed) throw IOException("The live link is closed")
        process.output.write((text.replace('\n', ' ') + "\n").toByteArray(Charsets.UTF_8))
        process.output.flush()
    }

    override fun close() {
        if (closed) return
        closed = true
        LiveRelay.closeAside(process)
    }
}

object LiveRelay {
    /** The relay's first line, once it runs with a WebSocket to hand: before it, nothing on its machine could relay. */
    const val HELLO = """{"type":"relay","v":1}"""

    /**
     * Opens a relay on [process] (started from [command]): its first line is [HELLO] within [timeoutMs], else
     * nothing on the machine can run it (no `bun`, a `node` without WebSocket) and this throws [NoRelay].
     */
    suspend fun open(process: Duplex, timeoutMs: Long = 15_000): RelayLink {
        val reader = BufferedReader(InputStreamReader(process.input, Charsets.UTF_8), 64 * 1024)
        // A login shell may print a greeting first: anything before the hello is skipped. Read on a thread of its own,
        // which closing the process ends: a wait cancelled or timed out never waits on it.
        val hello = CompletableDeferred<Boolean>()
        thread(isDaemon = true, name = "shepherdr-relay-hello") {
            hello.complete(
                runCatching {
                    var lines = 0
                    while (lines++ < 200) {
                        val line = reader.readLine() ?: return@runCatching false
                        if (line.trim() == HELLO) return@runCatching true
                    }
                    false
                }.getOrDefault(false),
            )
        }
        val ok = try {
            withTimeoutOrNull(timeoutMs) { hello.await() }
        } catch (e: CancellationException) {
            closeAside(process)
            throw e
        }
        if (ok != true) {
            closeAside(process)
            // No hello in time is a slow start this once (a login shell, a slow link), not a machine without a relay.
            throw if (ok == null) Slow() else NoRelay()
        }
        return RelayLink(process, reader)
    }

    /**
     * Closes an SSH command off the caller's thread: closing its channel writes to the network, which Android refuses
     * on the main thread (and a stalled link would hold it). Unclosed, the relay and its SSH session would stay.
     */
    internal fun closeAside(process: Duplex) {
        thread(isDaemon = true, name = "shepherdr-relay-close") { runCatching { process.close() } }
    }

    /** The relay didn't say hello in time: a slow start, this once. */
    class Slow : IOException("The relay on the machine didn't start in time")

    /** Nothing on the machine runs the relay: the app takes Shepherdr's own live link instead. */
    class NoRelay : IOException("Nothing on the machine can relay Shepherdr's live link")

    /**
     * The relay for `bun` (or `node`, 22 or later, which has WebSocket too) through a login shell, where PATH finds
     * them, linked to Shepherdr at [url]. Lines on its stdin go to Shepherdr as they are (a `watch`, a `ping`).
     */
    fun command(snapshots: Boolean, url: String = "ws://${Wire.HOST}/api/ws"): String {
        val run = "if command -v bun >/dev/null 2>&1; then exec bun -e ${quote(script(snapshots, url))}; " +
            "else exec node -e ${quote(script(snapshots, url))}; fi"
        return "sh -c ${quote("exec \"\${SHELL:-/bin/sh}\" -lc \"\$1\"")} sh ${quote(run)}"
    }

    /**
     * A WebSocket to [url] with the service's token, read on the machine from its file (`~/.herdr/shepherdr/token`,
     * or under `SHEPHERDR_DATA`), so it never crosses the phone's link; where it can't be read, none (the service then
     * refuses, which says so).
     */
    fun socket(url: String): String {
        val u = kotlinx.serialization.json.JsonPrimitive(url).toString()
        return "(()=>{let t='';try{t=require('fs').readFileSync((process.env.SHEPHERDR_DATA||require('os').homedir()+'/.herdr/shepherdr')+'/token','utf8').trim()}catch{}" +
            "return new WebSocket($u,t?{headers:{authorization:'Bearer '+t}}:undefined)})()"
    }

    /**
     * The relay itself. Each event waits under its key (a chat's key, `snapshot`, `build`; anything else is passed
     * on as it is) and only its newest is written, once stdout takes more, unless it is just what was written last.
     */
    fun script(snapshots: Boolean, url: String): String {
        return "if(typeof WebSocket==='undefined'){console.error('No WebSocket here');process.exit(5)}" +
            "const out=process.stdout,pending=new Map(),last=new Map(),early=[];let busy=false,closing=false,n=0;" +
            "out.on('error',()=>process.exit(0));" +
            "const pump=()=>{while(!busy&&pending.size){const[k,s]=pending.entries().next().value;pending.delete(k);" +
            "if(last.get(k)===s)continue;if(!k.startsWith('once:'))last.set(k,s);" +
            "if(!out.write(s+'\\n')){busy=true;out.once('drain',()=>{busy=false;pump()})}}" +
            "if(closing&&!busy&&!pending.size)process.exit(0)};" +
            "out.write('$HELLO\\n');" +
            "const ws=${socket(url)};" +
            "ws.onmessage=e=>{const s=String(e.data);if(!$snapshots&&s.startsWith('{\"type\":\"snapshot\"'))return;" +
            "if(s.startsWith('{\"type\":\"view\"'))return;" +
            "let m;try{m=JSON.parse(s)}catch{return}const t=m&&m.type;if(t==='snapshot'&&!$snapshots)return;" +
            "const k=t==='chat'&&m.chat?'chat:'+m.chat.key:t==='terminal'?'terminal:'+m.chat:(t==='snapshot'||t==='build'||t==='update')?t:'once:'+(n++);" +
            "pending.delete(k);pending.set(k,s);pump()};" +
            "ws.onopen=()=>{for(const l of early.splice(0))ws.send(l)};" +
            "ws.onerror=()=>console.error('Shepherdr refused the live link');" +
            "ws.onclose=()=>{closing=true;pump()};" +
            "let buf='';process.stdin.setEncoding('utf8');" +
            "process.stdin.on('data',d=>{buf+=d;let i;while((i=buf.indexOf('\\n'))>=0){const l=buf.slice(0,i).trim();buf=buf.slice(i+1);" +
            "if(!l)continue;if(ws.readyState===1)ws.send(l);else early.push(l)}});" +
            "process.stdin.on('end',()=>process.exit(0))"
    }
}
