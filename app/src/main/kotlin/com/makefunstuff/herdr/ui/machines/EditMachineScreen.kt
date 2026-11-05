package com.makefunstuff.herdr.ui.machines

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.api.SessionEntry
import com.makefunstuff.herdr.core.ssh.HerdrBinaries
import com.makefunstuff.herdr.core.ssh.HerdrCommands
import com.makefunstuff.herdr.core.ssh.Machine
import com.makefunstuff.herdr.core.ssh.SshConnector
import com.makefunstuff.herdr.data.MachineStore
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.copyToClipboard
import com.makefunstuff.herdr.ui.design.ActionRow
import com.makefunstuff.herdr.ui.design.BarIcon
import com.makefunstuff.herdr.ui.design.BottomActionBar
import com.makefunstuff.herdr.ui.design.ErrorMessage
import com.makefunstuff.herdr.ui.design.FieldLabel
import com.makefunstuff.herdr.ui.design.HerdrChip
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrMenu
import com.makefunstuff.herdr.ui.design.HerdrTextField
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.LoadingState
import com.makefunstuff.herdr.ui.design.MonoBlock
import com.makefunstuff.herdr.ui.design.PrimaryButton
import com.makefunstuff.herdr.ui.design.QuietButton
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.design.SecondaryButton
import com.makefunstuff.herdr.ui.design.SectionHeading
import com.makefunstuff.herdr.ui.design.StatusBadge
import com.makefunstuff.herdr.ui.design.SwitchRow
import com.makefunstuff.herdr.ui.manage.ConfirmDialog
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import com.makefunstuff.herdr.ui.theme.color
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

private sealed interface TestResult {
    data object Running : TestResult
    data class Ok(val fingerprint: String, val sessions: List<SessionEntry>) : TestResult
    data class Failed(val message: String) : TestResult
}

/**
 * Add a machine (id == null) or edit an existing one. Adding starts with scanning the code
 * `herdr-push pair` shows on the machine ([PairMachineScreen]); typing the address is the other way.
 */
@Composable
fun EditMachineScreen(nav: NavController, id: String?) {
    var typing by rememberSaveable(id) { mutableStateOf(id != null) }
    if (typing) MachineForm(nav, id, onScan = { typing = false }.takeIf { id == null }) else PairMachineScreen(nav, onType = { typing = true })
}

/** The machine's address and settings, typed; [onScan] (adding only) goes back to scanning its code. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MachineForm(nav: NavController, id: String?, onScan: (() -> Unit)?) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val existing = remember(id) { id?.let { container.machines.get(it) } }
    val machines by container.machines.machines.collectAsStateWithLifecycle()
    val publicKey by container.identity.publicKey.collectAsStateWithLifecycle()

    var target by remember { mutableStateOf(existing?.target ?: "") }
    var label by remember { mutableStateOf(existing?.label ?: "") }
    var session by remember { mutableStateOf(existing?.session ?: "default") }
    var herdrPath by remember { mutableStateOf(existing?.herdrPath ?: "") }
    var allowHostScripts by remember { mutableStateOf(existing?.allowHostScripts ?: true) }
    var viaId by remember { mutableStateOf(existing?.viaMachineId) }
    var pinnedKey by remember { mutableStateOf(existing?.hostKey) }
    var test by remember { mutableStateOf<TestResult?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var jumpOpen by remember { mutableStateOf(false) }

    val parsed = Machine.parseTarget(target)
    val targetError = when {
        target.isBlank() -> null
        parsed == null -> "Use user@host or user@host:port"
        parsed.first == null -> "Add the user name, like you@${parsed.second}"
        else -> null
    }

    fun build(): Machine? {
        val (user, host, port) = parsed ?: return null
        if (user == null) return null
        return Machine(
            id = existing?.id ?: MachineStore.newId(),
            label = label.trim().ifEmpty { host.substringBefore('.') },
            host = host,
            port = port,
            user = user,
            session = session.trim().ifEmpty { "default" },
            hostKey = pinnedKey,
            viaMachineId = viaId,
            herdrPath = herdrPath.trim().ifEmpty { null },
            enabled = existing?.enabled ?: true,
            allowHostScripts = allowHostScripts,
        )
    }

    fun runTest() {
        val m = build() ?: return
        test = TestResult.Running
        scope.launch {
            test = try {
                val conn = SshConnector.connect(m.copy(hostKey = pinnedKey), container.identity.get(), { container.machines.get(it) })
                try {
                    val found = HerdrBinaries.parse(runCatching { conn.run(HerdrCommands.probe(m)).stdout }.getOrDefault(""))
                    val herdr = HerdrBinaries.choose(found, null)?.let { m.copy(herdrPath = it.path) } ?: m
                    val out = conn.run(HerdrCommands.sessionList(herdr))
                    if (out.exitStatus == HerdrCommands.NOT_FOUND_EXIT) {
                        TestResult.Failed("Connected, but herdr wasn't found. Install it on this machine or set its path below.")
                    } else {
                        val sessions = runCatching {
                            val json = Json { ignoreUnknownKeys = true }
                            json.decodeFromJsonElement(
                                ListSerializer(SessionEntry.serializer()),
                                json.parseToJsonElement(out.stdout).jsonObject["sessions"]!!,
                            )
                        }.getOrElse { emptyList() }
                        TestResult.Ok(conn.hostKeyFingerprint, sessions)
                    }
                } finally {
                    conn.close()
                }
            } catch (t: Throwable) {
                TestResult.Failed(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    fun save() {
        val m = build() ?: return
        val fp = (test as? TestResult.Ok)?.fingerprint
        container.machines.upsert(if (m.hostKey == null && fp != null) m.copy(hostKey = fp) else m)
        nav.popBackStack()
    }

    val others = machines.filter { it.id != existing?.id }
    val canBuild = build() != null

    Scaffold(
        containerColor = Herdr.colors.ground,
        topBar = {
            HerdrTopBar(if (existing == null) "Add machine" else "Edit ${existing.label}", onBack = { nav.popBackStack() }) {
                if (existing != null) BarIcon(HerdrIcons.Trash2, "Remove machine", { confirmDelete = true })
            }
        },
        bottomBar = {
            BottomActionBar {
                SecondaryButton(
                    onClick = { runTest() },
                    enabled = canBuild && test != TestResult.Running,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text("Test connection") }
                PrimaryButton(
                    onClick = { save() },
                    enabled = canBuild,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text(if (existing == null) "Add" else "Save") }
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.screen),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            if (onScan != null) {
                QuietButton(onClick = onScan, modifier = Modifier.padding(top = Space.xs)) {
                    Icon(HerdrIcons.ScanQrCode, null, modifier = Modifier.size(16.dp))
                    Text("Scan the code from herdr-push pair instead")
                }
            }
            SectionHeading("Where it is", top = if (onScan != null) 0.dp else Space.xs)
            HerdrTextField(
                value = target,
                onValueChange = { target = it; test = null },
                label = "SSH target",
                error = targetError,
                hint = "user@host, with :port if it isn't 22",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            HerdrTextField(
                value = label,
                onValueChange = { label = it },
                label = "Name",
                hint = "How the app shows it. Empty: \u201c${parsed?.second?.substringBefore('.') ?: "the host name"}\u201d",
            )

            SectionHeading("herdr there")
            HerdrTextField(
                value = session,
                onValueChange = { session = it },
                label = "herdr session",
                hint = "The herdr session the app opens",
            )
            if (others.isNotEmpty()) {
                Box {
                    HerdrTextField(
                        value = others.firstOrNull { it.id == viaId }?.label ?: "Direct",
                        onValueChange = {},
                        label = "Connect through",
                        hint = "A jump host for a machine only reachable from there",
                        readOnly = true,
                    )
                    Box(Modifier.matchParentSize().clickable(onClickLabel = "Choose a jump host") { jumpOpen = true })
                    Icon(HerdrIcons.ChevronDown, null, tint = Herdr.colors.muted, modifier = Modifier.align(Alignment.TopEnd).padding(top = 16.dp, end = 12.dp))
                }
                HerdrMenu(expanded = jumpOpen, onDismissRequest = { jumpOpen = false }, title = "Connect through", subtitle = "A jump host for a machine only reachable from there") {
                    ActionRow("Direct", icon = HerdrIcons.Smartphone, detail = "From this phone", selected = viaId == null, onClick = { viaId = null; jumpOpen = false })
                    others.forEach { o ->
                        ActionRow(o.label, icon = HerdrIcons.Monitor, detail = o.target, selected = o.id == viaId, onClick = { viaId = o.id; jumpOpen = false })
                    }
                }
            }
            HerdrTextField(
                value = herdrPath,
                onValueChange = { herdrPath = it },
                label = "herdr path",
                hint = "Leave empty to find it automatically",
            )
            QuietCard(padded = true) {
                SwitchRow(
                    label = "Allow host scripts",
                    detail = "Allow copying helper scripts (herdr-usage, herdr-push) to this host. Turn off for zero-touch environments.",
                    checked = allowHostScripts,
                    onChange = { allowHostScripts = it },
                )
            }

            when (val t = test) {
                null -> {}
                TestResult.Running -> QuietCard {
                    LoadingState("Connecting to ${parsed?.second ?: "the machine"}")
                }
                is TestResult.Failed -> ErrorMessage(t.message, onRetry = { runTest() })
                is TestResult.Ok -> ConnectedCard(t, pinnedKey, session) { session = it }
            }

            SectionHeading("This phone's SSH key")
            QuietCard(padded = true) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                    Text(
                        "Add it to ~/.ssh/authorized_keys on the machine.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Herdr.colors.muted,
                    )
                    if (publicKey != null) {
                        MonoBlock(publicKey!!)
                        SecondaryButton(onClick = { context.copyToClipboard("public key", publicKey!!, "Public key copied") }) {
                            Icon(HerdrIcons.Copy, null, modifier = Modifier.size(18.dp))
                            Text("Copy public key")
                        }
                    } else {
                        Text("No SSH key yet. Set one up in Settings.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            pinnedKey?.let { fp ->
                SectionHeading("Trusted host key")
                QuietCard(padded = true) {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                        MonoBlock(fp)
                        SecondaryButton(onClick = { pinnedKey = null; test = null }) {
                            Icon(HerdrIcons.RotateCcw, null, modifier = Modifier.size(18.dp))
                            Text("Forget and check again")
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (confirmDelete && existing != null) {
        ConfirmDialog(
            "Remove ${existing.label}?",
            "Its herdr sessions and agents keep running. Only this phone forgets how to reach them.",
            "Remove machine",
            onDismiss = { confirmDelete = false },
        ) {
            confirmDelete = false
            container.machines.remove(existing.id)
            nav.popBackStack()
            nav.popBackStack()
        }
    }
}

/** What the test found: the host key to check, and the herdr sessions it can pick from. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ConnectedCard(result: TestResult.Ok, pinnedKey: String?, chosen: String, onChoose: (String) -> Unit) {
    QuietCard(padded = true) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
            StatusBadge("Connected", Herdr.colors.done)
            if (pinnedKey == null) {
                Text(
                    "The host key is only seen on the first connection. Check it matches the machine before saving:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Herdr.colors.muted,
                )
                MonoBlock(result.fingerprint)
            }
            if (result.sessions.isEmpty()) {
                Text(
                    "No herdr sessions are running there yet. Start herdr on the machine once.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Herdr.colors.muted,
                )
            } else {
                FieldLabel("herdr sessions")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    result.sessions.forEach { s ->
                        HerdrChip(
                            selected = chosen == s.name,
                            onClick = { onChoose(s.name) },
                            label = { Text(if (s.running) s.name else "${s.name} (stopped)") },
                        )
                    }
                }
            }
        }
    }
}
