package com.makefunstuff.herdr.core.chat

import com.makefunstuff.herdr.core.api.AgentSessionRef
import com.makefunstuff.herdr.core.ssh.HerdrCommands.quote
import com.makefunstuff.herdr.core.ssh.MachineConnection
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/** A pinned set of rollout files. Offsets are in their concatenation (including missing final newlines). */
internal object TranscriptPages {
    const val PAGE = 512_000
    private val script = """
        import os,sys,json,base64
        mode,amount=sys.argv[1],int(sys.argv[2]); out=sys.stdout.buffer
        if mode=='open':
            paths=sys.argv[4].splitlines() or [sys.argv[3]]
            files=[]; total=0
            for path in paths:
                with open(path,'rb') as f:
                    st=os.fstat(f.fileno()); n=st.st_size; sep=0
                    if n and path!=paths[-1]:
                        f.seek(n-1); sep=int(f.read(1)!=b'\n')
                    files.append([path,st.st_dev,st.st_ino,n,sep]); total+=n+sep
            token=base64.urlsafe_b64encode(json.dumps(files).encode()).decode()
            end=total
            print(str(total)+' '+token,flush=True)
        else:
            files=json.loads(base64.urlsafe_b64decode(sys.argv[3])); end=int(sys.argv[4])
        start=max(0,end-amount); pos=0
        for path,dev,ino,n,sep in files:
            lo=max(0,start-pos); hi=min(n,end-pos)
            if hi>lo:
                with open(path,'rb') as f:
                    st=os.fstat(f.fileno())
                    if (dev,ino)!=(st.st_dev,st.st_ino) or st.st_size<n: sys.exit(2)
                    f.seek(lo); data=f.read(hi-lo)
                    if len(data)!=hi-lo: sys.exit(2)
                    out.write(data)
            if sep and start<=pos+n<end: out.write(b'\n')
            pos+=n+sep
        out.flush()
        if mode=='open':
            path,dev,ino,n,_=files[-1]
            os.execlp('tail','tail','-c','+'+str(n+1),'-F',path)
    """.trimIndent()

    fun openCommand(ref: AgentSessionRef, bytes: Int): String =
        "kind=${quote(ref.kind)}; value=${quote(ref.value)}; agent=${quote(ref.agent)}; f=''; all=''\n" +
            TranscriptFollower.FIND + "\n[ -f \"\u0024f\" ] || { echo @@missing; exit; }; " +
            "command -v python3 >/dev/null || { echo @@unsupported; exit; }; " +
            "python3 -c ${quote(script)} open $bytes \"\$f\" \"\$all\" & child=\$!; " +
            "trap 'kill \$child 2>/dev/null' EXIT HUP INT TERM; cat >/dev/null # herdr-page-open"

    fun pageCommand(token: String, end: Long, count: Int): String =
        "python3 -c ${quote(script)} page $count ${quote(token)} $end # herdr-page-read"

    suspend fun older(connection: MachineConnection, token: String, end: Long, count: Int): ByteArray = withContext(Dispatchers.IO) {
        connection.start(pageCommand(token, end, count)).use { p ->
            val data = p.input.readBytes()
            if (data.size != count) throw IOException("History file changed or was unavailable")
            data
        }
    }

    /** Returns false when the host has no Python/file; caller can use the portable follower. */
    suspend fun follow(connection: MachineConnection, ref: AgentSessionRef, bytes: Int, make: () -> Transcript,
                       ready: (Window) -> Unit, update: (Window) -> Unit): Boolean = kotlinx.coroutines.coroutineScope {
        val initialBytes = minOf(bytes, PAGE)
        val proc = connection.start(openCommand(ref, initialBytes))
        try {
            var supported = false
            val job = launch(Dispatchers.IO) {
                val input = proc.input.buffered()
                val header = StringBuilder()
                while (header.length < 100_000) {
                    val b = input.read(); if (b < 0 || b == 10) break
                    header.append(b.toChar())
                }
                val size = header.toString().substringBefore(' ').toLongOrNull() ?: return@launch
                val token = header.toString().substringAfter(' ')
                val w = Window(token, (size - initialBytes).coerceAtLeast(0), make)
                supported = true
                var remaining = minOf(size, initialBytes.toLong())
                val buf = ByteArray(32768)
                while (remaining > 0) {
                    val n = input.read(buf, 0, minOf(remaining, buf.size.toLong()).toInt()); if (n < 0) throw IOException("Short snapshot")
                    w.append(buf.copyOf(n)); remaining -= n
                }
                // A tool result or attachment can fill the first page. Read backwards only until
                // there is a conversation to show, up to the previous first-load budget.
                var loaded = minOf(size, initialBytes.toLong()).toInt()
                while (!w.hasConversation() && w.start > 0 && loaded < bytes) {
                    val count = minOf(w.nextCount(), bytes - loaded)
                    if (count <= 0) break
                    w.prepend(older(connection, token, w.start, count))
                    loaded += count
                }
                ready(w)
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    synchronized(w) {
                        val revision = w.transcript.revision
                        w.append(buf.copyOf(n))
                        if (revision != w.transcript.revision) update(w)
                    }
                }
            }
            job.join(); supported
        } finally { withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { proc.close() } }
    }

    /** Keeps the cut first line in bytes, so even a UTF-8 character split across pages is recovered. */
    class Window(val token: String, var start: Long, private val make: () -> Transcript) {
        private val chunks = ArrayDeque<ByteArray>()
        private var kept = 0
        var transcript = make(); private set
        private var partial = ByteArrayOutputStream()
        private var skip = start > 0
        private var position = start
        private var lineStart = start
        var capped = false; private set
        fun append(data: ByteArray) {
            if (start > 0 && !capped) {
                if (kept + data.size <= 32_000_000) { chunks.addLast(data); kept += data.size }
                else { capped = true; chunks.clear() }
            }
            feed(data)
        }
        fun prepend(data: ByteArray) {
            require(!capped && data.size.toLong() <= start)
            start -= data.size; chunks.addFirst(data); kept += data.size
            transcript = make(); partial = ByteArrayOutputStream(); skip = start > 0; position = start; lineStart = start
            chunks.forEach(::feed)
            if (start == 0L || kept >= 32_000_000) { capped = start > 0; chunks.clear() }
        }
        fun hasConversation() = transcript.items().any { it is ChatItem.User || it is ChatItem.Agent }
        fun nextCount() = minOf(start, PAGE.toLong(), (32_000_000 - kept).toLong()).coerceAtLeast(0).toInt()
        private fun feed(data: ByteArray) {
            var from = 0
            for (i in data.indices) if (data[i] == 10.toByte()) {
                if (!skip && partial.size() + i - from <= 4_000_000) {
                    partial.write(data, from, i-from); transcript.feed(partial.toString("UTF-8").removeSuffix("\r"), lineStart)
                }
                partial.reset(); skip = false; from = i+1; lineStart = position + from
            }
            if (partial.size() + data.size - from > 4_000_000) skip = true
            if (!skip) partial.write(data, from, data.size-from)
            position += data.size
        }
    }
}
