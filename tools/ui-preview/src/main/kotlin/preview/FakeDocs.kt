package preview

import com.makefunstuff.herdr.core.docs.DocsCommands
import com.makefunstuff.herdr.core.ssh.CommandResult

/**
 * A machine's `~/.herdr/docs`: herdr.doc/2 files as agents write them with herdr-doc, each with how long ago it
 * changed, on the demo's clock. Answers [DocsCommands.list] and
 * [DocsCommands.read] as the shell does.
 */
class FakeDocs(
    /** Each file by its name in the folder: its JSON and how many minutes ago it changed. */
    var files: Map<String, Pair<String, Long>>,
    /** Set to make reading fail as the shell says it. */
    var refuse: String? = null,
) {
    private val dir = "/home/demo/.herdr/docs"
    private val at = Demo.NOW / 1000

    private fun mtime(minutes: Long) = at - minutes * 60

    fun run(command: String): CommandResult? {
        val reading = "No such document" in command
        if (!reading && !(".herdr/docs" in command && "ls -1t" in command)) return null
        refuse?.let { return CommandResult(1, "", "$it\n") }
        if (!reading) return CommandResult(
            0,
            files.entries.sortedBy { it.value.second }.joinToString("") { (name, f) ->
                "\n${DocsCommands.MARK}${mtime(f.second)} ${f.first.length} $dir/$name\n" + f.first.take(DocsCommands.HEAD_BYTES)
            },
            "",
        )
        val path = Shell.words(command.substringBefore(";")).first().removePrefix("f=")
        val f = files[path.removePrefix("$dir/")] ?: return CommandResult(2, "", "No such document\n")
        val m = mtime(f.second)
        val since = Regex("""\[ "\${'$'}m \${'$'}s" = '([^']*)' ]""").find(command)?.groupValues?.get(1)
        return CommandResult(0, "${DocsCommands.MARK}$m ${f.first.length} $path\n" + if (since == "$m ${f.first.length}") "" else f.first, "")
    }
}

/** The demo's documents on devbox: an investigation under way, a runbook, a report and an incident that needs you. */
object DemoDocs {
    const val DIR = "/home/demo/.herdr/docs"
    const val UPLOAD = "upload-flake.hdoc.json"
    const val RUNBOOK = "rotate-deploy-keys.hdoc.json"
    const val REPORT = "build-times-week-42.hdoc.json"
    const val INCIDENT = "pages-after-1900-deploy.hdoc.json"

    fun docs() = FakeDocs(
        mapOf(
            UPLOAD to (upload to 12L),
            INCIDENT to (incident to 95L),
            RUNBOOK to (runbook to 60L * 26),
            REPORT to (report to 60L * 24 * 3),
            "half-written.hdoc.json" to ("{\"format\":\"herdr.doc/2\",\"title\":\"Notes on" to 60L * 24 * 6),
        ),
    )

    val upload = """
        {
          "format": "herdr.doc/2",
          "title": "Flaky upload test in api-server",
          "subtitle": "on duty · 17 Oct 2026",
          "kind": "investigation",
          "status": "work",
          "tags": ["api-server", "ci"],
          "updated": "2026-10-17T07:30:00Z",
          "blocks": [
            { "type": "callout", "tone": "warn", "title": "So far", "text": "`TestUpload/large` fails **1 run in 9** on CI, never locally. The temp dir is shared between parallel runs." },
            { "type": "stats", "items": [
              { "label": "Failed runs", "value": "7", "tone": "bad", "note": "of 63 this week" },
              { "label": "Fail rate", "value": "11%", "tone": "warn" },
              { "label": "Local runs", "value": "0/200", "tone": "ok", "note": "with -count" }
            ] },
            { "type": "kv", "items": [["repo", "api-server"], ["test", "TestUpload/large"], ["since", "2026-10-14, after #412"], ["runner", "ubuntu-24.04, 4 cores"]] },
            { "type": "heading", "text": "What happened", "level": 2 },
            { "type": "timeline", "items": [
              { "time": "Oct 14", "text": "#412 merged: uploads go to os.TempDir() instead of t.TempDir()" },
              { "time": "Oct 15", "text": "First red run on main", "tone": "bad" },
              { "time": "Oct 16", "text": "Retried green; marked flaky in the dashboard", "who": "ci-bot" },
              { "time": "07:12", "text": "Reproduced with -parallel 8", "tone": "ok" }
            ] },
            { "type": "finding", "title": "Parallel runs share one temp file", "severity": "high",
              "detail": "`writeChunk` names its file after the upload id, which the test fixes to `large`. Two runs at once write the same file.",
              "evidence": [ { "type": "evidence", "command": "go test ./upload -run TestUpload/large -count 20 -parallel 8", "where": "devbox",
                "output": "--- FAIL: TestUpload/large (0.41s)\n    upload_test.go:88: checksum mismatch: got 9f2c…, want 41ab…\nFAIL\nFAIL\tapi-server/upload\t3.902s" } ],
              "fix": "Use t.TempDir() per run, as before #412." },
            { "type": "checks", "items": [
              { "text": "Reproduces with -parallel 8", "ok": true },
              { "text": "Passes with the fix, 200 runs", "ok": true, "note": "-count 200" },
              { "text": "CI on the branch", "ok": null, "note": "queued" }
            ] },
            { "type": "todo", "items": [
              { "text": "Revert the temp dir change in writeChunk", "done": true },
              { "text": "Add a parallel run to the test", "who": "agent" },
              { "text": "Unmark the test as flaky", "who": "you" }
            ] },
            { "type": "section", "title": "The diff", "collapsed": false, "blocks": [
              { "type": "diff", "file": "upload/chunk.go", "text": "@@ -41,7 +41,7 @@ func writeChunk(t testing.TB, id string, b []byte) {\n-\tdir := os.TempDir()\n+\tdir := t.TempDir()\n \tpath := filepath.Join(dir, id+\".part\")" }
            ] },
            { "type": "section", "title": "Raw CI logs", "collapsed": true, "blocks": [ { "type": "code", "text": "…" } ] }
          ]
        }
    """.trimIndent()

    val runbook = """
        {
          "format": "herdr.doc/2",
          "title": "Rotate the deploy keys",
          "kind": "runbook",
          "status": "ready",
          "tags": ["infra"],
          "blocks": [
            { "type": "md", "text": "Every **90 days**, or at once when a key may have leaked. Takes about ten minutes; nothing goes down." },
            { "type": "steps", "items": [
              { "text": "Make the new key", "state": "done" },
              { "text": "Add it to the deploy user on each host", "state": "done", "note": "3 hosts" },
              { "text": "Switch CI's secret to the new key", "state": "now" },
              { "text": "Remove the old key", "state": "todo" },
              { "text": "Tell #infra", "state": "skip" }
            ] },
            { "type": "code", "lang": "sh", "text": "ssh-keygen -t ed25519 -C deploy@$(date +%F) -f ~/.ssh/deploy_new\nfor h in web1 web2 worker1; do ssh-copy-id -i ~/.ssh/deploy_new deploy@${'$'}h; done" },
            { "type": "table", "columns": ["host", "key", "added"], "align": ["left", "left", "right"], "rows": [["web1", "deploy@2026-10-17", "07:20"], ["web2", "deploy@2026-10-17", "07:21"], ["worker1", "deploy@2026-10-17", "07:21"]] },
            { "type": "links", "items": [ { "label": "CI secrets", "url": "https://ci.example.com/settings/secrets" }, { "label": "Key policy", "url": "https://wiki.example.com/keys" } ] }
          ]
        }
    """.trimIndent()

    val report = """
        {
          "format": "herdr.doc/2",
          "title": "Build times, week 42",
          "kind": "report",
          "status": "done",
          "blocks": [
            { "type": "bars", "unit": "min", "items": [ { "label": "api-server", "value": 6.5 }, { "label": "website", "value": 3 }, { "label": "herdr-android", "value": 14, "tone": "warn" } ] },
            { "type": "progress", "label": "Cache hits", "value": 71, "max": 100, "tone": "ok" }
          ]
        }
    """.trimIndent()

    val incident = """
        {
          "format": "herdr.doc/2",
          "title": "Pages after the 19:00 deploy",
          "kind": "incident",
          "status": "need",
          "blocks": [
            { "type": "callout", "tone": "bad", "text": "Roll back, or wait for the fix? **Needs you.**" },
            { "type": "quote", "text": "p95 on /checkout went from 180 ms to 2.4 s", "who": "alertmanager", "where": "#oncall" }
          ]
        }
    """.trimIndent()
}
