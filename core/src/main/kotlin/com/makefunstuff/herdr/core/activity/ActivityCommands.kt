package com.makefunstuff.herdr.core.activity

import com.makefunstuff.herdr.core.chat.TranscriptFollower
import com.makefunstuff.herdr.core.ssh.HerdrCommands.quote

/**
 * The shell commands the timeline reads with, over the machine's SSH connection. Both only read.
 * Their output is in parts, each after a `@@herdr-activity <part>` line.
 */
object ActivityCommands {
    const val MARK = "@@herdr-activity"

    /** What one session sends at most, after it is pruned: its newest lines. */
    const val SESSION_BYTES = 1_500_000

    /** All of an agent's sessions together, at most. */
    const val TOTAL_BYTES = 6_000_000

    /**
     * Drops what the timeline never shows (API request copies, attachments, reasoning, whole files
     * kept beside an edit) and cuts long strings, so a 20 MB session comes to a megabyte or two.
     * Keeps the newest lines within its byte budget, and says when it left older ones out.
     */
    private val PRUNE = """
        import json,sys
        L=3000
        SKIP={"api-request","api-request-blob","api-request-shape","mode","permission-mode","atis-latch","last-prompt","ai-title","file-history-snapshot","file-history-delta","queue-operation","cost-state","attachment","token_count","token_usage_record","world_state","reasoning","model_usage","thinking_level_change","credential_pin","title","title_change","Reasoning","custom_message"}
        DROP={"originalFile","encrypted_content","thinkingSignature","signature","partialArgs","thinking","base_instructions","developer_instructions","user_instructions"}
        def p(v):
            if isinstance(v,str): return v if len(v)<=L else v[:L]+"…"
            if isinstance(v,list): return [p(x) for x in v[:600]]
            if isinstance(v,dict): return {k:p(x) for k,x in v.items() if k not in DROP}
            return v
        budget=int(sys.argv[1]); out=[]; size=0
        for line in sys.stdin.buffer:
            if not line.startswith(b"{"): continue
            try: o=json.loads(line)
            except Exception: continue
            if not isinstance(o,dict): continue
            t=o.get("type"); pl=o.get("payload")
            if t in SKIP: continue
            if isinstance(pl,dict) and (pl.get("type") in SKIP or (pl.get("item") or {}).get("type") in SKIP): continue
            if t=="custom" and o.get("customType")!="tool_execution_start": continue
            s=json.dumps(p(o),ensure_ascii=False,separators=(",",":"))
            out.append(s); size+=len(s)+1
        cut=False
        while out and size>budget:
            size-=len(out.pop(0))+1; cut=True
        w=sys.stdout.buffer
        if cut: w.write(b"$MARK cut\n")
        for s in out: w.write((s+"\n").encode("utf-8","replace"))
    """.trimIndent()

    // $1 bytes, $2 follow (1 or 0), then three arguments a session: agent kind (claude|codex|pi|omp),
    // kind (path|id), value. The session written to last is the one followed: after every session's
    // pruned lines, the lines it gets from then on, raw, until the app closes the channel (the trailing `cat`).
    private val SCRIPT = """
        bytes="${'$'}1"; follow="${'$'}2"; shift 2
        py=${'$'}(command -v python3 2>/dev/null)
        last=""; lastsize=0; lastvalue=""
        while [ ${'$'}# -ge 3 ]; do
          agent="${'$'}1"; kind="${'$'}2"; value="${'$'}3"; shift 3; f=""
          if [ "${'$'}kind" = path ]; then
            f="${'$'}value"
            case "${'$'}f" in "~/"*) f="${'$'}HOME/${'$'}{f#\~/}" ;; esac
          else
            case "${'$'}agent" in
              claude) for c in "${'$'}{CLAUDE_CONFIG_DIR:-${'$'}HOME/.claude}"/projects/*/"${'$'}value".jsonl; do [ -f "${'$'}c" ] && f="${'$'}c" && break; done ;;
              ${TranscriptFollower.CODEX}
              pi) for c in "${'$'}HOME"/.pi/agent/sessions/*/*_"${'$'}value".jsonl; do [ -f "${'$'}c" ] && f="${'$'}c" && break; done ;;
              omp) for c in "${'$'}HOME"/.omp/agent/sessions/*/*_"${'$'}value".jsonl; do [ -f "${'$'}c" ] && f="${'$'}c" && break; done ;;
            esac
          fi
          if [ -z "${'$'}f" ] || [ ! -f "${'$'}f" ]; then echo "$MARK missing ${'$'}agent ${'$'}value"; continue; fi
          size=${'$'}(wc -c < "${'$'}f" | tr -d ' ')
          echo "$MARK session ${'$'}agent ${'$'}size ${'$'}value"
          if [ -n "${'$'}py" ] && head -c "${'$'}size" "${'$'}f" | "${'$'}py" -c "${'$'}PRUNE" "${'$'}bytes"; then :
          elif [ "${'$'}size" -gt "${'$'}bytes" ]; then echo "$MARK cut raw"; head -c "${'$'}size" "${'$'}f" | tail -c "${'$'}bytes"; echo
          else head -c "${'$'}size" "${'$'}f"; echo
          fi
          if [ -z "${'$'}last" ] || [ "${'$'}f" -nt "${'$'}last" ]; then last="${'$'}f"; lastsize="${'$'}size"; lastvalue="${'$'}value"; fi
        done
        echo "$MARK done"
        if [ "${'$'}follow" = 1 ] && [ -n "${'$'}last" ]; then
          echo "$MARK live ${'$'}lastvalue"
          tail -c +${'$'}((lastsize + 1)) -F "${'$'}last" 2>/dev/null &
          t=${'$'}!
          trap 'kill "${'$'}t" 2>/dev/null' EXIT HUP INT TERM
          cat > /dev/null
        fi
    """.trimIndent()

    /** Reads [sources] in order and, with [follow], keeps following the one written to last. */
    fun sessions(sources: List<SessionSource>, follow: Boolean, bytes: Int = SESSION_BYTES): String =
        "PRUNE=${quote(PRUNE)} sh -c ${quote(SCRIPT)} herdr-activity " + (
            listOf(bytes.toString(), if (follow) "1" else "0") + sources.flatMap { listOf(it.agent.substringBefore('/'), it.kind, it.value) }
            ).joinToString(" ") { quote(it) }
}
