package com.makefunstuff.herdr

import android.app.Application
import android.app.NotificationManager
import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.makefunstuff.herdr.brief.AndroidBrief
import com.makefunstuff.herdr.brief.AndroidVoice
import com.makefunstuff.herdr.brief.BriefStore
import com.makefunstuff.herdr.brief.BriefWork
import com.makefunstuff.herdr.bubble.AndroidBubbles
import com.makefunstuff.herdr.bubble.Bubbles
import com.makefunstuff.herdr.data.DraftStore
import com.makefunstuff.herdr.data.HealthStore
import com.makefunstuff.herdr.data.HostManager
import com.makefunstuff.herdr.data.IdentityStore
import com.makefunstuff.herdr.data.MachineStore
import com.makefunstuff.herdr.data.Shares
import com.makefunstuff.herdr.push.AlertWork
import com.makefunstuff.herdr.push.AndroidAlerts
import com.makefunstuff.herdr.push.AndroidLive
import com.makefunstuff.herdr.push.AndroidPhone
import com.makefunstuff.herdr.push.Inbox
import com.makefunstuff.herdr.push.LiveWork
import com.makefunstuff.herdr.push.PushHosts
import com.makefunstuff.herdr.push.PushStore
import com.makefunstuff.herdr.push.QuietWork
import com.makefunstuff.herdr.push.TallyStore
import com.makefunstuff.herdr.push.Wakes
import com.makefunstuff.herdr.share.Widgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class HerdrApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Android ships a cut-down "BC" provider; sshj needs the full one for Ed25519 and friends.
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.insertProviderAt(BouncyCastleProvider(), 1)

        container = AppContainer(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                container.guard.started()
                container.hosts.setForeground(true)
            }
            override fun onStop(owner: LifecycleOwner) {
                // The app lock counts the time away from here.
                container.guard.stopped()
                // What the screens showed was seen; the next look while closed reports only what is new.
                container.alertWork.leaving()
                container.briefWork.leaving()
                container.hosts.setForeground(false)
                Wakes.plan(this@HerdrApp)
            }
        })
        // While the app runs, the phone leaving Do Not Disturb or car mode ends quiet hours at once
        // (Android tells only running apps; a closed one looks every quarter of an hour).
        val modes = IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED).apply {
            addAction(UiModeManager.ACTION_ENTER_CAR_MODE)
            addAction(UiModeManager.ACTION_EXIT_CAR_MODE)
        }
        ContextCompat.registerReceiver(this, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                container.quietWork.check()
            }
        }, modes, ContextCompat.RECEIVER_NOT_EXPORTED)
        // The widgets follow the counts (their time to the minute) and the Coordinator used last.
        container.scope.launch {
            combine(
                container.tally.counts.map { it.copy(at = it.at / 60_000) }.distinctUntilChanged(),
                container.shares.last,
                container.usage.latest,
            ) { _, _, _ -> }.collect { Widgets.update(this@HerdrApp) }
        }
        // The launcher's shortcuts say how many things need you, and which Coordinator they open.
        container.scope.launch {
            combine(container.tally.counts, container.shares.last, container.machines.machines) { c, last, list ->
                c.needYou to last?.let { id -> list.firstOrNull { it.id == id }?.label }
            }.distinctUntilChanged().collect { (need, label) -> container.shortcuts.update(need, label) }
        }
        container.scope.launch {
            container.machines.machines.collect { list ->
                val ids = list.map { it.id }.toSet()
                container.shortcuts.keep(ids)
                container.starts.keep(ids)
            }
        }
    }
}

class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val machines = MachineStore(app)
    val identity = IdentityStore(app, scope)
    val hosts = HostManager(scope, machines, identity)
    val drafts = DraftStore(app)
    val code = com.makefunstuff.herdr.data.CodeStore(java.io.File(app.filesDir, "code.json"))
    val health = HealthStore()
    /** Each machine's providers and limits, as last read there (kept through restarts). */
    val usage = com.makefunstuff.herdr.data.UsageStore(java.io.File(app.filesDir, "usage.json"))
    /** Each machine's agent logins, as last read there (only in memory). */
    val logins = com.makefunstuff.herdr.data.LoginsStore()
    val quick = com.makefunstuff.herdr.data.QuickStore(java.io.File(app.filesDir, "quick.json"))
    val shares = Shares(java.io.File(app.filesDir, "shares.json"), java.io.File(app.cacheDir, "shared"))
    // Pictures waiting in a composer are the app's own files; pictures sent are a cache Android may clear.
    val images = com.makefunstuff.herdr.data.ImageStore(
        java.io.File(app.filesDir, "images.json"), java.io.File(app.filesDir, "images"), java.io.File(app.cacheDir, "sent-images"),
    )
    val push = PushStore(app)
    /** The Agents list's pins, snoozes and mutes; a muted agent's notifications don't show. */
    val triage = com.makefunstuff.herdr.data.TriageStore(java.io.File(app.filesDir, "triage.json"))
    /** Which actions ask for the fingerprint, and the app lock. */
    val guard = com.makefunstuff.herdr.data.GuardStore(java.io.File(app.filesDir, "guard.json"), com.makefunstuff.herdr.guard.AndroidUnlock(app))
    // With the app lock on, a locked phone shows its notifications without what they are about.
    val inbox = Inbox(java.io.File(app.filesDir, "alerts.json"), AndroidAlerts(app, appLock = { guard.state.value.appLock }), muted = triage::mutes)
    val phone = AndroidPhone(app, push)
    val pushHosts = PushHosts(scope, hosts, machines, push, phone)
    val quietWork = QuietWork(scope, push.state, phone, inbox, foreground = { hosts.isForeground })
    val liveWork = LiveWork(scope, hosts, machines, push, AndroidLive(app), quietWork, muted = triage.state.map { it.muted })
    val tally = TallyStore(java.io.File(app.filesDir, "tally.json"), machines)
    /** One-shot watches set from the long-press sheets. */
    val watchWork = com.makefunstuff.herdr.push.WatchWork(push, inbox, quietWork)
    val alertWork = AlertWork(hosts, machines, push, inbox, liveWork, quietWork, tally, quick, watchWork, usage)
    /** Every board's list as last read, and which finished agents the phone has shown: the Agents screen's and the Board tab's. */
    val boards = com.makefunstuff.herdr.data.BoardLists(hosts, onRead = { id, tasks -> tally.tasks(id, tasks) })
    val seen = com.makefunstuff.herdr.data.SeenStore(java.io.File(app.filesDir, "seen.json"))
    /** When each agent's chat was last on screen, for its "While you were away" card. */
    val looked = com.makefunstuff.herdr.data.LookedStore(java.io.File(app.filesDir, "looked.json"))
    /** The voice messages recorded here: those waiting to go, and the sent ones to play again. */
    val voice = com.makefunstuff.herdr.data.VoiceStore(java.io.File(app.filesDir, "voice.json"))
    /** The messages held while an agent worked, and the quick replies over its composer. */
    val composer = com.makefunstuff.herdr.data.ComposerStore(java.io.File(app.filesDir, "composer.json"))
    /** The chats' and the terminal's text sizes (the terminal's from before taken from its old setting). */
    val textSizes = com.makefunstuff.herdr.data.TextSizeStore(java.io.File(app.filesDir, "text-sizes.json"), scope) {
        app.getSharedPreferences("terminal", Context.MODE_PRIVATE).takeIf { it.contains("font_sp") }?.getFloat("font_sp", 11f)
    }
    /** The terminal's key bar and whether it keeps the screen on. */
    val keyBar = com.makefunstuff.herdr.data.KeyBarStore(java.io.File(app.filesDir, "key-bar.json"))
    /** The coordinator's voice: its server, voice and speed. */
    val voiceSettings = com.makefunstuff.herdr.data.VoiceSettingsStore(java.io.File(app.filesDir, "voice-settings.json"))
    /** The coordinator's replies read aloud, and your words heard and sent. */
    val coordinatorVoice: com.makefunstuff.herdr.voice.CoordinatorVoice =
        com.makefunstuff.herdr.voice.AndroidCoordinatorVoice(app, scope, hosts, voiceSettings)
    /** Voice calls with an Assistant or a task's agent, through Shepherdr's voice agent on the machine. */
    val voiceCalls: com.makefunstuff.herdr.voice.VoiceCalls = com.makefunstuff.herdr.voice.AndroidVoiceCalls(scope, hosts)
    /** The clock the screens read (a turn's "Working for 3m", the away card). */
    val clock: () -> Long = System::currentTimeMillis
    val bubbles: Bubbles = AndroidBubbles(app)
    /** The launcher's shortcuts: Needs you, the Coordinator, Start work, the agents opened last. */
    val shortcuts: com.makefunstuff.herdr.share.Shortcuts = com.makefunstuff.herdr.share.AndroidShortcuts(app)
    /** The folders work was started in from the phone, and how it was started last. */
    val starts = com.makefunstuff.herdr.data.StartStore(java.io.File(app.filesDir, "starts.json"))
    val briefWork = BriefWork(
        scope, hosts, machines, BriefStore(java.io.File(app.filesDir, "brief.json")), AndroidBrief(app), AndroidVoice(app),
        quiet = { quietWork.check() },
    )

    init {
        // Read the key up front so the public key is ready to show. A missing key is never made
        // here: the first launch offers to restore one first.
        scope.launch { runCatching { identity.load() } }
        pushHosts.start()
        liveWork.start()
        quietWork.start()
        // The boards the app reads while on screen are what the person saw, for the brief.
        tally.start(scope, hosts, onBoard = { id, tasks -> boards.put(id, tasks); briefWork.looked(id, tasks, null) })
        briefWork.start()
        watchWork.start(scope, hosts, boards)
        usage.start(scope, hosts, machines)
        // Shared files waiting for a machine taken out of the app go with it.
        scope.launch { machines.machines.collect { list -> shares.keep(list.map { it.id }.toSet()) } }
        // Pins, snoozes and mutes of panes gone (or snoozes over) go as the machines are read.
        scope.launch { hosts.hosts.collect { triage.keep(it, machines.machines.value.map { m -> m.id }.toSet()) } }
        // The push app's address may have lapsed while the app was closed: ask again, it answers with the same or a new one.
        if (push.state.value.on) phone.register()
        // When network returns (e.g. WiFi connects, cellular signal restored), retry any sessions waiting in backoff.
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        runCatching {
            cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                private var retryJob: Job? = null
                override fun onAvailable(network: Network) {
                    retryJob?.cancel()
                    retryJob = scope.launch {
                        delay(300)
                        hosts.retryAll()
                    }
                }
            })
        }
    }
}
