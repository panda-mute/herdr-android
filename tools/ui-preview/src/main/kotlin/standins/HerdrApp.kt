// Stand-in for app/…/HerdrApp.kt (an Android Application): the same AppContainer, on a preview Context.
package com.makefunstuff.herdr

import android.content.Context
import com.makefunstuff.herdr.data.DraftStore
import com.makefunstuff.herdr.data.HealthStore
import com.makefunstuff.herdr.data.HostManager
import com.makefunstuff.herdr.data.IdentityStore
import com.makefunstuff.herdr.data.MachineStore
import com.makefunstuff.herdr.data.Shares
import com.makefunstuff.herdr.push.AlertWork
import com.makefunstuff.herdr.push.Inbox
import com.makefunstuff.herdr.push.LiveWork
import com.makefunstuff.herdr.push.PushHosts
import com.makefunstuff.herdr.push.PushStore
import com.makefunstuff.herdr.push.QuietWork
import com.makefunstuff.herdr.push.TallyStore
import com.makefunstuff.herdr.bubble.Bubbles
import com.makefunstuff.herdr.brief.BriefStore
import com.makefunstuff.herdr.brief.BriefWork
import preview.Demo
import preview.PreviewAlerts
import preview.PreviewBrief
import preview.PreviewVoice
import preview.PreviewBubbles
import preview.PreviewLive
import preview.PreviewPhone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.cancel

class AppContainer(app: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val machines = MachineStore(app)
    val identity = IdentityStore(app, scope)
    val hosts = HostManager(scope, machines, identity)
    val drafts = DraftStore(app)
    // At the demo's fixed time, so "2 h ago" reads the same every run.
    val code = com.makefunstuff.herdr.data.CodeStore(java.io.File(app.filesDir, "code.json"), now = { Demo.NOW })
    val health = HealthStore()
    // At the demo's fixed time, on UTC, so "resets 17:00" reads the same every run.
    val usage = com.makefunstuff.herdr.data.UsageStore(
        java.io.File(app.filesDir, "usage.json"), clock = { Demo.NOW }, zone = { java.time.ZoneOffset.UTC },
    )
    // At the demo's fixed time, on UTC, so "good until 13 Nov" reads the same every run.
    val logins = com.makefunstuff.herdr.data.LoginsStore(clock = { Demo.NOW }, zone = { java.time.ZoneOffset.UTC })
    val shares = Shares(java.io.File(app.filesDir, "shares.json"), java.io.File(app.filesDir, "shared"))
    val images = com.makefunstuff.herdr.data.ImageStore(
        java.io.File(app.filesDir, "images.json"), java.io.File(app.filesDir, "images"), java.io.File(app.filesDir, "sent-images"),
    )
    val push = PushStore(app)
    val triage = com.makefunstuff.herdr.data.TriageStore(java.io.File(app.filesDir, "triage.json"))
    val inbox = Inbox(java.io.File(app.filesDir, "alerts.json"), PreviewAlerts, muted = triage::mutes)
    val phone = PreviewPhone
    val pushHosts = PushHosts(scope, hosts, machines, push, phone)
    val quietWork = QuietWork(scope, push.state, phone, inbox, foreground = { hosts.isForeground })
    val liveWork = LiveWork(scope, hosts, machines, push, PreviewLive, quietWork, muted = triage.muted(push.state, hosts.hosts))
    val tally = TallyStore(java.io.File(app.filesDir, "tally.json"), machines)
    val taskSpaces = com.makefunstuff.herdr.push.TaskSpaces(java.io.File(app.filesDir, "task-spaces.json"))
    val alertWork = AlertWork(hosts, machines, push, inbox, liveWork, quietWork, tally, usage, taskSpaces)
    /** Every board's list as last read, and which finished agents the phone has shown: the Agents screen's and the Board tab's. */
    val boards = com.makefunstuff.herdr.data.BoardLists(hosts, onRead = { id, tasks -> tally.tasks(id, tasks); briefWork.looked(id, tasks, null) }, onBoard = { id, o -> taskSpaces.put(id, o); inbox.looked(id, o) })
    val seen = com.makefunstuff.herdr.data.SeenStore(java.io.File(app.filesDir, "seen.json"))
    /** When each agent's chat was last on screen, for its "While you were away" card. */
    val looked = com.makefunstuff.herdr.data.LookedStore(java.io.File(app.filesDir, "looked.json"))
    /** The voice messages recorded here: those waiting to go, and the sent ones to play again. */
    val voice = com.makefunstuff.herdr.data.VoiceStore(java.io.File(app.filesDir, "voice.json"))
    /** The messages held while an agent worked, and the quick replies over its composer. */
    val composer = com.makefunstuff.herdr.data.ComposerStore(java.io.File(app.filesDir, "composer.json"))
    /** The chats' and the terminal's text sizes. */
    val textSizes = com.makefunstuff.herdr.data.TextSizeStore(java.io.File(app.filesDir, "text-sizes.json"))
    /** The terminal's key bar and whether it keeps the screen on. */
    val keyBar = com.makefunstuff.herdr.data.KeyBarStore(java.io.File(app.filesDir, "key-bar.json"))
    /** The coordinator's voice: its settings, and a still voice the scene sets (no sound, no microphone). */
    val voiceSettings = com.makefunstuff.herdr.data.VoiceSettingsStore(java.io.File(app.filesDir, "voice-settings.json"))
    val coordinatorVoice: com.makefunstuff.herdr.voice.CoordinatorVoice = preview.PreviewCoordinatorVoice(preview.PreviewCoordinatorVoice.next)
    /** Voice calls: a still one the scene sets (no sound, no microphone). */
    val voiceCalls: com.makefunstuff.herdr.voice.VoiceCalls = preview.PreviewVoiceCalls(preview.PreviewVoiceCalls.next)
    /** The clock the screens read (a turn's "Working for 3m", the away card): the demo's fixed time. */
    val clock: () -> Long = { Demo.NOW }
    /** The time zone the screens tell times in: UTC, as the demo's clock. */
    val zone: () -> java.time.ZoneId = { java.time.ZoneOffset.UTC }
    val bubbles: Bubbles = PreviewBubbles
    val shortcuts: com.makefunstuff.herdr.share.Shortcuts = com.makefunstuff.herdr.share.NoShortcuts
    val starts = com.makefunstuff.herdr.data.StartStore(java.io.File(app.filesDir, "starts.json"))
    val guard = com.makefunstuff.herdr.data.GuardStore(java.io.File(app.filesDir, "guard.json"), preview.PreviewUnlock)
    // The brief at the demo's fixed time, on UTC, so it reads the same every run.
    val briefWork = BriefWork(
        scope, hosts, machines, BriefStore(java.io.File(app.filesDir, "brief.json")), PreviewBrief, PreviewVoice(),
        zone = { java.time.ZoneOffset.UTC }, clock = { Demo.NOW },
    )

    /** Changing the app: its builder and update as the scene sets them (`appChange`), and an installer that stands still. */
    val changes = com.makefunstuff.herdr.change.AppChanges(
        scope, hosts, java.io.File(app.filesDir, "app-change.json"), java.io.File(app.filesDir, "updates"), preview.PreviewThisApp,
        clock = { Demo.NOW },
    )

    init {
        // The app is in front for the whole scene.
        hosts.setForeground(true)
        // What quiet hours mean now, before the first frame.
        quietWork.check()
        briefWork.start()
        usage.start(scope, hosts, machines)
        changes.start()
    }

    /** Ends the scene: every machine session and the container's coroutines. */
    fun close() {
        hosts.setForeground(false)
        scope.cancel()
    }
}
