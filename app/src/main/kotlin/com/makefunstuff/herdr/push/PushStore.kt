package com.makefunstuff.herdr.push

import android.content.Context
import com.makefunstuff.herdr.core.push.AlertRules
import com.makefunstuff.herdr.core.push.PushSeal
import com.makefunstuff.herdr.core.push.QuietHours
import com.makefunstuff.herdr.core.push.Watch
import com.makefunstuff.herdr.core.push.WebPush
import com.makefunstuff.herdr.core.push.WebPushKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.Base64
import java.util.UUID

/** Notifications while the app is closed: the switch, the push app's address, and each machine's part. */
@Serializable
data class PushState(
    /** Notifications while the app is closed, on or off. */
    val on: Boolean = false,
    /** This phone, as machines know it; made once. */
    val phoneId: String = "",
    /** The push app's address for this phone (UnifiedPush), once it gave one. */
    val endpoint: String? = null,
    /** The push app the address comes from, by package. */
    val distributor: String? = null,
    /** Why there is no address, when the push app said so. */
    val pushProblem: String? = null,
    /** How often the app looks at the machines itself while closed, in minutes; 0 never. */
    val checkMinutes: Int = 15,
    /** Live Updates for running board tasks and agents (herdr-push then also sends what runs). */
    val live: Boolean = true,
    /** When notifications that can wait are held, to come as one summary later. */
    val quiet: QuietHours = QuietHours(),
    /** Which kinds reach the phone, and whether they stay silent while you are at a machine. */
    val rules: AlertRules = AlertRules(),
    /** One-shot watches set from the long-press sheets before Notify: each becomes its space's Notify: All once its agent is found ([com.makefunstuff.herdr.core.home.NotifyMove]). */
    val watches: List<Watch> = emptyList(),
    val machines: Map<String, MachinePush> = emptyMap(),
) {
    fun machine(id: String): MachinePush = machines[id] ?: MachinePush()
}

/** One machine's part: its key, whether it is left out, and how its herdr-push stands. */
@Serializable
data class MachinePush(
    /** The key its pushes are sealed with (base64); made once, given to the machine over SSH. */
    val key: String = "",
    /** Left out by the person: no herdr-push there, no looks. */
    val off: Boolean = false,
    /** The address the machine sends to now; null when it isn't set up for this phone. */
    val sentTo: String? = null,
    /** herdr-push's version there. */
    val version: String = "",
    /** What went wrong setting it up, or what it says went wrong sending. */
    val problem: String? = null,
    /** A note that isn't a problem, such as "stops when you log out". */
    val note: String? = null,
    /** When a test notification from it last arrived. */
    val testedAt: Long = 0,
    /** The newest push taken from it (its own clock, seconds): an older one is a replay or a straggler. */
    val lastAt: Long = 0,
    /** Whether its subscription asks for what runs (Live Updates); null before one was made with it. */
    val live: Boolean? = null,
    /** The agents an older subscription followed for the one-shot watches (`pane:…`); null or empty: none, as subscriptions are made now. */
    val watching: String? = null,
    /** Its Shepherdr 3 service's task alerts (Web Push): the keys this phone subscribed there with; made once. */
    val webPush: WebPushKeys? = null,
    /** The address its Shepherdr 3 service sends task alerts to; null when not subscribed there. */
    val tasksTo: String? = null,
) {
    val keyBytes: ByteArray? get() = key.takeIf { it.isNotEmpty() }?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
}

/** [PushState], kept as a small JSON file in app storage. */
class PushStore(private val file: File) {
    constructor(context: Context) : this(File(context.filesDir, "push.json"))
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _state = MutableStateFlow(load())
    val state: StateFlow<PushState> = _state.asStateFlow()

    @Synchronized
    fun change(f: (PushState) -> PushState) {
        val next = f(_state.value)
        if (next == _state.value) return
        val tmp = File(file.parentFile, "push.json.tmp")
        tmp.writeText(json.encodeToString(PushState.serializer(), next))
        tmp.renameTo(file)
        _state.value = next
    }

    fun machine(id: String, f: (MachinePush) -> MachinePush) = change { s -> s.copy(machines = s.machines + (id to f(s.machine(id)))) }

    /** The machine's key, made the first time it is asked for. */
    @Synchronized
    fun key(id: String): ByteArray {
        state.value.machine(id).keyBytes?.let { return it }
        val key = PushSeal.newKey()
        machine(id) { it.copy(key = Base64.getEncoder().encodeToString(key)) }
        return key
    }

    /** The machine's Web Push keys for its Shepherdr 3 service, made the first time they are asked for. */
    @Synchronized
    fun webKeys(id: String): WebPushKeys {
        state.value.machine(id).webPush?.let { return it }
        val keys = WebPush.newKeys()
        machine(id) { it.copy(webPush = keys) }
        return keys
    }

    /** Whether a Shepherdr 3 service sends this phone its task alerts: a task's agent then alerts as its task alone. */
    val taskAlerts: Boolean get() = state.value.on && state.value.machines.values.any { it.tasksTo != null && !it.off }

    /** Every machine's key, to find which one sealed a push. */
    fun keys(): Map<String, ByteArray> = state.value.machines.mapNotNull { (id, m) -> m.keyBytes?.let { id to it } }.toMap()

    private fun load(): PushState {
        val loaded = runCatching { if (file.exists()) json.decodeFromJsonElement(PushState.serializer(), known(json.parseToJsonElement(file.readText()))) else null }
            .getOrNull() ?: PushState()
        return if (loaded.phoneId.isEmpty()) loaded.copy(phoneId = UUID.randomUUID().toString()) else loaded
    }

    /** [saved] without the watches of a kind this app no longer has (a herdr-projects task's), so the rest still loads. */
    private fun known(saved: JsonElement): JsonElement {
        val o = saved as? JsonObject ?: return saved
        val watches = o["watches"] as? JsonArray ?: return saved
        val kinds = Watch.What.entries.map { it.name }.toSet()
        return JsonObject(o + ("watches" to JsonArray(watches.filter { ((it as? JsonObject)?.get("what") as? JsonPrimitive)?.content in kinds })))
    }
}
