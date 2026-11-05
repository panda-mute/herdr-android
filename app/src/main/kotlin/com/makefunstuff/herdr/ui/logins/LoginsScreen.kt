package com.makefunstuff.herdr.ui.logins

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import com.makefunstuff.herdr.core.api.plainMessage
import com.makefunstuff.herdr.core.guard.Guarded
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.core.logins.CliLogin
import com.makefunstuff.herdr.core.logins.KeySource
import com.makefunstuff.herdr.core.logins.LoginAction
import com.makefunstuff.herdr.core.logins.LoginEvent
import com.makefunstuff.herdr.core.logins.LoginProvider
import com.makefunstuff.herdr.core.logins.LoginRun
import com.makefunstuff.herdr.core.logins.LoginState
import com.makefunstuff.herdr.core.logins.LoginWords
import com.makefunstuff.herdr.core.logins.LoginsReport
import com.makefunstuff.herdr.core.logins.ProfileKey
import com.makefunstuff.herdr.data.HostManager
import com.makefunstuff.herdr.data.LoginsStore
import com.makefunstuff.herdr.ui.common.EmptyState
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.LocalGuard
import com.makefunstuff.herdr.ui.common.copyToClipboard
import com.makefunstuff.herdr.ui.common.label
import com.makefunstuff.herdr.ui.common.toast
import com.makefunstuff.herdr.ui.design.ActionRow
import com.makefunstuff.herdr.ui.design.AgentMark
import com.makefunstuff.herdr.ui.design.BarIcon
import com.makefunstuff.herdr.ui.design.ErrorMessage
import com.makefunstuff.herdr.ui.design.FieldLabel
import com.makefunstuff.herdr.ui.design.HerdrDialog
import com.makefunstuff.herdr.ui.design.HerdrField
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrMenu
import com.makefunstuff.herdr.ui.design.HerdrShapes
import com.makefunstuff.herdr.ui.design.HerdrSheet
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.ListRow
import com.makefunstuff.herdr.ui.design.LoadingState
import com.makefunstuff.herdr.ui.design.MonoBlock
import com.makefunstuff.herdr.ui.design.Notice
import com.makefunstuff.herdr.ui.design.PrimaryButton
import com.makefunstuff.herdr.ui.design.QuietButton
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.design.RowDivider
import com.makefunstuff.herdr.ui.design.SecondaryButton
import com.makefunstuff.herdr.ui.design.SheetTitle
import com.makefunstuff.herdr.ui.design.Spinner
import com.makefunstuff.herdr.ui.design.StatusBadge
import com.makefunstuff.herdr.ui.design.hairline
import com.makefunstuff.herdr.ui.theme.CodeStyle
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** How old a reading may be when the screen opens before it is read again. */
private const val OPEN_FRESH_MS = 60_000L

/**
 * A machine's agent logins: each agent CLI there, signed in or not, how, and until when; from here
 * sign in again (the link and code on the phone), paste a key, or sign out, each through that CLI's
 * own command over this app's SSH session (host/herdr-logins). Keys exported in a shell profile get
 * a warning and the steps to move them; the app edits no profile.
 */
@Composable
fun LoginsScreen(nav: NavController, machineId: String) {
    val container = LocalContainer.current
    val session = container.hosts.session(machineId)
    if (session == null) {
        EmptyState("Machine not found", "It may have been removed.")
        return
    }
    val store = container.logins
    val scope = rememberCoroutineScope()
    val owner = LocalLifecycleOwner.current
    val state by session.state.collectAsStateWithLifecycle()
    val latest by store.latest.collectAsStateWithLifecycle()
    val problems by store.problems.collectAsStateWithLifecycle()
    val reading by store.reading.collectAsStateWithLifecycle()
    val entry = latest[machineId]
    val name = state.machine.label

    LaunchedEffect(machineId, state.online) {
        if (!state.online) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (store.stale(machineId, OPEN_FRESH_MS)) store.read(session)
        }
    }

    var menuFor by remember { mutableStateOf<CliLogin?>(null) }
    var keyFor by remember { mutableStateOf<KeyAsk?>(null) }
    var signOut by remember { mutableStateOf<Pair<CliLogin, LoginProvider?>?>(null) }
    var moving by remember { mutableStateOf(false) }
    var showHow by remember { mutableStateOf<CliLogin?>(null) }
    val runner = remember(machineId) { LoginRunner(store, container.hosts, session, scope) }
    val guard = LocalGuard.current

    fun begin(cli: CliLogin, action: LoginAction, provider: String? = null, key: String? = null, credential: String? = null) {
        val what = when (action) {
            LoginAction.Login -> "Sign in to ${cli.name}${provider?.let { " ($it)" }.orEmpty()} on $name?"
            LoginAction.Key -> "Give ${cli.name} a key on $name?"
            LoginAction.Logout -> "Sign ${cli.name}${provider?.let { " ($it)" }.orEmpty()} out on $name?"
        }
        guard.run(Guarded.Login, what) { runner.start(cli, action, provider, key, credential) }
    }

    fun signIn(cli: CliLogin) {
        when {
            LoginAction.Login in cli.actions -> begin(cli, LoginAction.Login)
            LoginAction.Key in cli.actions -> keyFor = KeyAsk(cli, null)
            else -> showHow = cli
        }
    }

    Scaffold(
        containerColor = Herdr.colors.ground,
        topBar = {
            HerdrTopBar(
                "Agent logins",
                subtitle = listOf(
                    name,
                    when {
                        machineId in reading -> "reading…"
                        entry != null -> "read ${ago(store.clock() - entry.readMs)}"
                        machineId in problems -> "couldn't read it"
                        !state.online -> state.status.label()
                        else -> "reading…"
                    },
                ).joinToString(" · "),
                onBack = { nav.popBackStack() },
            ) {
                BarIcon(HerdrIcons.RefreshCw, "Read again", { session.retry(); scope.launch { store.read(session) } }, enabled = machineId !in reading)
            }
        },
    ) { padding ->
        if (entry == null) {
            Box(Modifier.padding(padding)) {
                val problem = problems[machineId]
                when {
                    !session.machine.allowHostScripts -> EmptyState(
                        "Host scripts are off", "Reading the logins copies a small script to $name. Turn host scripts on in Edit machine.",
                        icon = HerdrIcons.KeyRound,
                    )
                    problem != null -> ErrorMessage(problem, Modifier.padding(Space.screen)) { scope.launch { store.read(session) } }
                    else -> LoadingState("Reading $name's agent logins")
                }
            }
            return@Scaffold
        }
        val report = entry.report
        val now = store.nowSeconds()
        val zone = store.zone()
        LazyColumn(
            Modifier.padding(padding),
            contentPadding = PaddingValues(start = Space.screen, end = Space.screen, top = Space.s, bottom = Space.xxxl),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            problems[machineId]?.let { p ->
                item(key = "problem") { Notice("Couldn't read it again: $p. This is the reading from ${ago(store.clock() - entry.readMs)}.", color = Herdr.colors.notice, icon = HerdrIcons.Clock) }
            }
            item(key = "clis") {
                QuietCard {
                    val shown = report.installed
                    if (shown.isEmpty()) Text(
                        "No agent CLI is installed on $name (Claude Code, Codex, pi, opencode or Gemini CLI).",
                        style = MaterialTheme.typography.bodyMedium, color = Herdr.colors.muted, modifier = Modifier.padding(Space.l),
                    )
                    shown.forEachIndexed { i, cli ->
                        if (i > 0) RowDivider(inset = Space.l + 28.dp + Space.m)
                        CliRow(cli, now, zone, onOpen = { menuFor = cli }, onSignIn = { signIn(cli) })
                    }
                }
            }
            LoginWords.profileWarning(report)?.let { warning ->
                item(key = "profile") { ProfileWarning(warning, onHow = { moving = true }) }
            }
            item(key = "how") {
                Text(
                    "Each sign-in, key and sign-out is that CLI's own command, run on $name over this app's SSH session. " +
                        "A key or a code goes in as the command's input: never on a command line, in shell history or on the phone. " +
                        "Only whether each one is signed in comes back.",
                    style = MaterialTheme.typography.bodySmall, color = Herdr.colors.muted, modifier = Modifier.padding(horizontal = Space.xs),
                )
            }
        }
    }

    menuFor?.let { cli ->
        CliMenu(
            cli, name,
            onDismiss = { menuFor = null },
            onSignIn = { menuFor = null; signIn(cli) },
            onKey = { p -> menuFor = null; keyFor = KeyAsk(cli, p) },
            onSignOut = { p -> menuFor = null; signOut = cli to p },
            onHow = { menuFor = null; showHow = cli },
        )
    }
    keyFor?.let { ask ->
        KeySheet(ask, name, runner, onDismiss = { keyFor = null; runner.close() }, onSave = { provider, key -> begin(ask.cli, LoginAction.Key, provider, key) })
    }
    signOut?.let { (cli, p) ->
        val provider = p?.id
        HerdrDialog(
            onDismissRequest = { signOut = null },
            title = { Text("Sign ${cli.name}${provider?.let { " ($it)" }.orEmpty()} out on $name?") },
            text = {
                Text(
                    if (cli.id == "pi") "Only $provider's entry is taken out of pi's auth.json. Agents using it there stop working until it is signed in again."
                    else "${signOutCommand(cli, provider)} runs on $name. Agents using it there stop working until it is signed in again.",
                )
            },
            dismissButton = { QuietButton({ signOut = null }) { Text("Cancel") } },
            confirmButton = { QuietButton({ signOut = null; begin(cli, LoginAction.Logout, provider, credential = p?.credential) }, color = Herdr.colors.red) { Text("Sign out") } },
        )
    }
    showHow?.let { cli -> HowDialog(cli, name) { showHow = null } }
    if (moving) entry?.report?.let { MoveSheet(it, name) { moving = false } }
    // A run that isn't a key's (those show in their own sheet): sign in, sign out.
    runner.current?.takeIf { it.action != LoginAction.Key }?.let { RunSheet(it, name, runner) }
}

private fun ago(ms: Long): String = when {
    ms < 60_000 -> "just now"
    ms < 3_600_000 -> "${ms / 60_000} min ago"
    else -> "${ms / 3_600_000} h ago"
}

private fun signOutCommand(cli: CliLogin, provider: String?) = when (cli.id) {
    "claude" -> "claude auth logout"
    "codex" -> "codex logout"
    "opencode" -> "opencode auth logout $provider"
    else -> "Its sign-out"
}

// ---- the machine's row -----------------------------------------------------------------------

/**
 * The Agent logins row on a machine, under Usage: how many agent CLIs are signed in there, and the
 * one that ran out or the key in a profile when there is one. Read when it shows and is older than ten minutes.
 */
@Composable
fun LoginsRow(session: HostSession, onOpen: () -> Unit, modifier: Modifier = Modifier, tile: Boolean = false) {
    val store = LocalContainer.current.logins
    val latest by store.latest.collectAsStateWithLifecycle()
    val state by session.state.collectAsStateWithLifecycle()
    val id = session.machine.id
    LaunchedEffect(id, state.online) {
        if (state.online && store.stale(id, 10 * 60_000L)) store.read(session)
    }
    val c = Herdr.colors
    val report = latest[id]?.report
    val now = store.nowSeconds()
    val wants = report?.installed?.any { LoginWords.wants(it, now) } == true
    val tint = when {
        report == null -> null
        report.installed.any { LoginWords.expired(it, now) } -> c.red
        wants || report.profileKeys.isNotEmpty() || report.installed.any { LoginWords.soon(it, now) } -> c.notice
        else -> null
    }
    val line = report?.let { LoginWords.glance(it, now) } ?: "Which agent CLIs are signed in"
    if (tile) {
        com.makefunstuff.herdr.ui.machines.PlaceTile(
            HerdrIcons.KeyRound, "Logins", line, tint ?: c.muted, modifier, onOpen, "Open agent logins",
            edge = tint ?: c.lineStrong, iconTint = tint ?: c.text,
        )
        return
    }
    QuietCard(
        modifier.semantics(mergeDescendants = true) { contentDescription = "Agent logins: $line" },
        onClick = onOpen, onClickLabel = "Open agent logins", edge = tint ?: c.line,
    ) {
        Row(Modifier.heightIn(min = 48.dp).padding(horizontal = Space.m), verticalAlignment = Alignment.CenterVertically) {
            Icon(HerdrIcons.KeyRound, null, tint = tint ?: c.text, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Space.m))
            Text("Agent logins", style = MaterialTheme.typography.titleSmall, color = c.strong, maxLines = 1)
            Spacer(Modifier.width(Space.s))
            Text(line, style = MaterialTheme.typography.bodySmall, color = tint ?: c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(HerdrIcons.ChevronRight, null, tint = c.faint, modifier = Modifier.padding(start = Space.s).size(18.dp))
        }
    }
}

// ---- rows ------------------------------------------------------------------------------------

@Composable
private fun CliRow(cli: CliLogin, now: Long, zone: java.time.ZoneId, onOpen: () -> Unit, onSignIn: () -> Unit) {
    val c = Herdr.colors
    val badge = LoginWords.badge(cli, now)
    val tone = when {
        LoginWords.expired(cli, now) -> c.red
        LoginWords.soon(cli, now) -> c.notice
        cli.state == LoginState.SignedIn -> c.done
        else -> c.muted
    }
    ListRow(
        cli.name,
        subtitle = LoginWords.line(cli, now, zone),
        subtitleLines = 2,
        subtitleColor = if (tone == c.red || tone == c.notice) tone else androidx.compose.ui.graphics.Color.Unspecified,
        leading = { AgentMark(cli.id, size = 28.dp) },
        trailing = {
            if (badge == null || LoginWords.wants(cli, now)) {
                SecondaryButton(onSignIn, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) { Text("Sign in") }
            } else StatusBadge(badge, tone)
        },
        onClickLabel = "Manage ${cli.name}'s login",
        onClick = onOpen,
    )
}

@Composable
private fun ProfileWarning(text: String, onHow: () -> Unit) {
    val c = Herdr.colors
    QuietCard(padded = true, color = c.tint(c.notice), edge = c.notice.copy(alpha = 0.4f)) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(HerdrIcons.TriangleAlert, null, tint = c.notice, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text("Keys in a shell profile", style = MaterialTheme.typography.titleSmall, color = c.strong)
                Text(text, style = MaterialTheme.typography.bodySmall, color = c.text)
                QuietButton(onHow, color = c.accent, contentPadding = PaddingValues(vertical = 6.dp)) { Text("Show me how to move them") }
            }
        }
    }
}

/** A CLI's actions: sign in again, paste a key, sign out; pi's and opencode's per provider. */
@Composable
private fun CliMenu(
    cli: CliLogin,
    machine: String,
    onDismiss: () -> Unit,
    onSignIn: () -> Unit,
    onKey: (String?) -> Unit,
    onSignOut: (LoginProvider?) -> Unit,
    onHow: () -> Unit,
) {
    HerdrMenu(expanded = true, onDismissRequest = onDismiss, title = "${cli.name} on $machine") {
        if (cli.providers.isNotEmpty()) {
            cli.providers.forEach { p -> ProviderRow(cli, p, onKey, onSignOut) }
            RowDivider(inset = Space.l)
        }
        if (LoginAction.Login in cli.actions) ActionRow(
            if (cli.state == LoginState.SignedIn) "Sign in again" else "Sign in",
            detail = loginDetail(cli), icon = HerdrIcons.LogIn, onClick = onSignIn,
        )
        if (LoginAction.Key in cli.actions) ActionRow(
            if (cli.id == "pi") "Add a provider's key" else "Paste a key",
            detail = if (cli.id == "pi") "Saved in pi's auth.json on $machine, only that provider's entry" else "codex login --with-api-key, the key on its input",
            icon = HerdrIcons.KeyRound, onClick = { onKey(null) },
        )
        if (LoginAction.Logout in cli.actions && cli.providers.isEmpty()) ActionRow(
            "Sign out", detail = signOutCommand(cli, null), icon = HerdrIcons.LogOut, destructive = true, onClick = { onSignOut(null) },
        )
        if (LoginAction.Login !in cli.actions && LoginAction.Key !in cli.actions) ActionRow(
            if (cli.env) "Signed in by a variable" else "How to sign in",
            detail = when {
                cli.env -> "Nothing to change from here"
                cli.id == "opencode" -> "Its sign-in asks with menus: run it on the machine"
                else -> "It has no login command"
            },
            icon = HerdrIcons.Info, onClick = onHow,
        )
    }
}

private fun loginDetail(cli: CliLogin) = when (cli.id) {
    "claude" -> "claude auth login: you paste the page's code back here"
    "codex" -> "codex login --device-auth: the code to type shows here"
    else -> null
}

@Composable
private fun ProviderRow(cli: CliLogin, p: LoginProvider, onKey: (String?) -> Unit, onSignOut: (LoginProvider?) -> Unit) {
    val where = when (p.source) {
        KeySource.Command -> "Key from a command (keyring): kept as it is"
        KeySource.Env -> "From ${p.method ?: "a variable"} in the environment"
        KeySource.OAuth -> "Login"
        KeySource.Typed -> "Key typed in"
    }
    val canKey = cli.id == "pi" && p.source == KeySource.Typed
    val canOut = LoginAction.Logout in cli.actions && (p.source == KeySource.Typed || p.source == KeySource.OAuth) &&
        (cli.id != "opencode" || p.credential != null)
    ActionRow(
        p.name, detail = where,
        icon = if (p.source == KeySource.Command) HerdrIcons.Lock else HerdrIcons.KeyRound,
        enabled = canKey || canOut,
        trailing = if (!(canKey || canOut)) null else ({
            Row {
                if (canKey) QuietButton({ onKey(p.id) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) { Text("New key") }
                if (canOut) QuietButton({ onSignOut(p) }, color = Herdr.colors.red, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) { Text("Sign out") }
            }
        }),
    )
}

@Composable
private fun HowDialog(cli: CliLogin, machine: String, onDismiss: () -> Unit) {
    HerdrDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (cli.env) "${cli.name} signs in with a variable" else "Sign in to ${cli.name}") },
        text = {
            Text(
                when {
                    cli.env -> "${cli.how} on $machine. The app doesn't change variables: see the warning about keys in a shell profile."
                    cli.id == "gemini" -> "Gemini CLI has no login command: its first run asks how to sign in. Open a terminal on $machine, run gemini, and pick Login with Google."
                    cli.id == "opencode" -> "opencode's sign-in picks the provider and its way of signing in from menus, which the phone can't answer. Open a terminal on $machine and run opencode auth login. A login can be signed out from here."
                    cli.id == "pi" -> "To sign pi in to a provider with a login, run pi on $machine and type /login. A key can be added from here."
                    else -> "${cli.name} can't be signed in from the phone."
                },
            )
        },
        confirmButton = { QuietButton(onDismiss) { Text("OK") } },
    )
}

// ---- a run -----------------------------------------------------------------------------------

private data class KeyAsk(val cli: CliLogin, val provider: String?)

/** One sign-in, key or sign-out under way on the machine, and what it said. */
@Stable
internal class Running(val cli: CliLogin, val action: LoginAction, val provider: String?) {
    var run: LoginRun? = null
    var link by mutableStateOf<String?>(null)
    var code by mutableStateOf<String?>(null)
    var ask by mutableStateOf<LoginEvent.Ask?>(null)
    val lines = mutableStateListOf<String>()
    var done by mutableStateOf<LoginEvent.Done?>(null)
    var sent by mutableStateOf(false)

    /** What the CLI said to what was typed when it asks again (a code it refused). */
    var refused by mutableStateOf<String?>(null)
}

/**
 * Starts and holds the one run of a screen; its status goes into [store] when it ends. The machine
 * is borrowed for the run ([HostManager.borrow]), so opening the sign-in page in the browser, which
 * puts the app in the background, doesn't close the SSH session the run goes over.
 */
@Stable
internal class LoginRunner(
    private val store: LoginsStore,
    private val hosts: HostManager,
    private val session: HostSession,
    private val scope: CoroutineScope,
) {
    var current by mutableStateOf<Running?>(null)
        private set

    /** Starts [action]; [key] (a key's run) is typed in as soon as it runs, and kept nowhere else. */
    fun start(cli: CliLogin, action: LoginAction, provider: String?, key: String? = null, credential: String? = null) {
        close()
        val r = Running(cli, action, provider)
        current = r
        scope.launch {
            try {
                hosts.borrow(session.machine.id) { s ->
                    val run = store.client(s).begin(cli.id, action, provider, credential)
                    r.run = run
                    if (current !== r) { run.close(); return@borrow }
                    key?.let { run.type(it) }
                    run.events.collect { e ->
                        when (e) {
                            is LoginEvent.Link -> if (r.link == null) r.link = e.url
                            is LoginEvent.Code -> if (r.code == null) r.code = e.code
                            is LoginEvent.Ask -> { r.refused = if (e.again) r.lines.lastOrNull() else null; r.ask = e; r.sent = false }
                            is LoginEvent.Line -> { r.lines.add(e.text); if (r.lines.size > 40) r.lines.removeAt(0) }
                            is LoginEvent.Done -> {
                                r.done = e
                                e.status?.let { store.put(session.machine.id, it) }
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (r.done == null) r.done = LoginEvent.Done(false, t.plainMessage(), null)
            } finally {
                r.run?.close()
            }
            // Read the rest again: a sign-in can change what another CLI sees (a shared key).
            if (r.done?.ok == true) store.read(session)
        }
    }

    fun type(line: String) {
        val r = current ?: return
        r.sent = true
        scope.launch { runCatching { r.run?.type(line) }.onFailure { r.done = LoginEvent.Done(false, it.plainMessage(), null) } }
    }

    /** Ends the run: closing its stream ends the CLI on the machine too. */
    fun close() {
        current?.run?.close()
        current = null
    }
}

/** A sign-in or sign-out as it runs: the page to open and the code to type there, or the field for the code the page shows. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RunSheet(r: Running, machine: String, runner: LoginRunner) {
    val c = Herdr.colors
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val done = r.done
    LaunchedEffect(done) {
        if (done?.ok == true) {
            context.toast(if (r.action == LoginAction.Logout) "${r.cli.name} signed out on $machine" else "${r.cli.name} signed in on $machine")
            runner.close()
        }
    }
    HerdrSheet(onDismissRequest = { runner.close() }) {
        SheetTitle(
            when (r.action) {
                LoginAction.Logout -> "Signing ${r.cli.name} out on $machine"
                else -> "Sign in to ${r.cli.name}${r.provider?.let { " ($it)" }.orEmpty()} on $machine"
            },
        )
        Column(Modifier.padding(horizontal = Space.xl), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            val link = r.link
            val code = r.code
            val ask = r.ask
            when {
                done != null && !done.ok -> {
                    ErrorMessage(done.msg ?: "It didn't work")
                    if (r.lines.isNotEmpty()) MonoBlock(r.lines.takeLast(6).joinToString("\n"), Modifier.fillMaxWidth())
                }
                link != null && code != null -> {
                    Text("Open the page, sign in, and type this code there:", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        code, style = CodeStyle.copy(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Medium), color = c.strong,
                        modifier = Modifier.fillMaxWidth().background(c.input, HerdrShapes.Card).hairline(HerdrShapes.Card).padding(vertical = Space.l)
                            .semantics { contentDescription = "Code ${code.toList().joinToString(" ")}" },
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        PrimaryButton({ runCatching { uri.openUri(link) } }) {
                            Icon(HerdrIcons.ExternalLink, null, Modifier.size(16.dp)); Spacer(Modifier.width(Space.xs)); Text("Open ${host(link)}")
                        }
                        SecondaryButton({ context.copyToClipboard("Code", code, "Code copied") }) {
                            Icon(HerdrIcons.Copy, null, Modifier.size(16.dp)); Spacer(Modifier.width(Space.xs)); Text("Copy code")
                        }
                    }
                    Waiting("Waiting for you…")
                }
                link != null -> {
                    Text("Open the page and sign in. It then shows a code: paste it here.", style = MaterialTheme.typography.bodyMedium)
                    PrimaryButton({ runCatching { uri.openUri(link) } }) {
                        Icon(HerdrIcons.ExternalLink, null, Modifier.size(16.dp)); Spacer(Modifier.width(Space.xs)); Text("Open ${host(link)}")
                    }
                    if (ask != null) AskField(ask, r.sent, r.refused, label = "Code from the page", onSend = runner::type)
                    else Waiting("Waiting for ${r.cli.name}…")
                }
                ask != null -> {
                    Text(ask.text, style = MaterialTheme.typography.bodyMedium)
                    AskField(ask, r.sent, r.refused, label = "Answer", onSend = runner::type)
                }
                else -> Waiting(if (r.action == LoginAction.Logout) "Signing out…" else "Starting ${r.cli.name}'s sign-in on $machine…")
            }
            Text(footnote(r, machine), style = MaterialTheme.typography.bodySmall, color = c.muted)
            if (done != null && !done.ok) QuietButton({ runner.close() }) { Text("Close") }
        }
        Spacer(Modifier.padding(bottom = Space.l))
    }
}

private fun host(url: String) = url.substringAfter("://").substringBefore('/').substringBefore('?')

private fun footnote(r: Running, machine: String) = when {
    r.action == LoginAction.Logout -> "${signOutCommand(r.cli, r.provider)} runs on $machine."
    r.cli.id == "codex" -> "codex login --device-auth runs on $machine and saves the login there. The token never comes to the phone."
    r.cli.id == "claude" -> "claude auth login runs on $machine and saves the login there. The code you paste goes to it over this app's SSH session, as its input, and isn't kept."
    else -> "${r.cli.name}'s own sign-in runs on $machine and saves the login there. What you type goes to it as its input, and isn't kept."
}

@Composable
private fun Waiting(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spinner(size = 16.dp)
        Spacer(Modifier.width(Space.s))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Herdr.colors.muted)
    }
}

@Composable
private fun AskField(ask: LoginEvent.Ask, sent: Boolean, refused: String?, label: String, onSend: (String) -> Unit) {
    var value by remember(ask) { mutableStateOf("") }
    HerdrField(
        value, { value = it.replace("\n", "").replace("\r", "") }, singleLine = true, label = label,
        visualTransformation = if (ask.secret && label != "Code from the page") PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = if (ask.secret) KeyboardType.Password else KeyboardType.Text),
        enabled = !sent,
        isError = refused != null && !sent,
        supportingText = refused?.takeIf { !sent }?.let { { Text(it) } },
    )
    if (sent) Waiting("Sent. Waiting for it…")
    else PrimaryButton({ onSend(value.trim()); value = "" }, enabled = value.isNotBlank()) { Text("Send") }
}

/** Paste a key: Codex's, or one provider's of pi. Saved and checked on the machine, then dropped here. */
@Composable
private fun KeySheet(ask: KeyAsk, machine: String, runner: LoginRunner, onDismiss: () -> Unit, onSave: (String?, String) -> Unit) {
    val c = Herdr.colors
    val context = LocalContext.current
    val pi = ask.cli.id == "pi"
    var provider by remember { mutableStateOf(ask.provider.orEmpty()) }
    var key by remember { mutableStateOf("") }
    val r = runner.current?.takeIf { it.action == LoginAction.Key }
    val done = r?.done
    LaunchedEffect(done) {
        if (done?.ok == true) {
            context.toast(if (pi) "$provider's key saved for pi on $machine" else "Codex has its key on $machine")
            onDismiss()
        }
    }
    // A known provider with a key from a command (a keyring) is never replaced by a typed one.
    val keyring = ask.cli.providers.firstOrNull { it.id == provider.trim() }?.takeIf { it.source == KeySource.Command || it.source == KeySource.Env }
    HerdrSheet(onDismissRequest = onDismiss) {
        SheetTitle(if (pi) (if (provider.isBlank()) "A key for pi on $machine" else "${provider.trim()} key for pi on $machine") else "Codex key on $machine")
        Column(Modifier.padding(horizontal = Space.xl), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            if (pi && ask.provider == null) HerdrField(
                provider, { provider = it.filter { ch -> ch.isLetterOrDigit() || ch in "._-" }.take(64) },
                singleLine = true, label = "Provider", placeholder = { Text("groq") },
                isError = keyring != null,
                supportingText = { Text(if (keyring != null) "pi takes ${keyring.id}'s key from a command (a keyring): it stays as it is" else "As pi names it: groq, openrouter, deepseek…") },
            )
            HerdrField(
                key, { key = it.replace("\n", "").replace("\r", "").trim() }, singleLine = true, label = "Key",
                placeholder = { Text("sk-…") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                enabled = r == null || done != null,
            )
            Text(
                if (pi) "Saved in ~/.pi/agent/auth.json (mode 600) on $machine, only ${provider.trim().ifEmpty { "that provider" }}'s entry, under pi's own lock. " +
                    "A key that pi takes from a command (the keyring) is never replaced by a typed one. " +
                    "It travels only over this app's SSH session, as the command's input: never on a command line, in shell history or on the phone."
                else "codex login --with-api-key takes it on its input on $machine and saves it there. " +
                    "It travels only over this app's SSH session: never on a command line, in shell history or on the phone.",
                style = MaterialTheme.typography.bodySmall, color = c.muted,
            )
            if (done != null && !done.ok) ErrorMessage(done.msg ?: "It didn't take the key")
            if (r != null && done == null) Waiting("Saving and checking on $machine…")
            else PrimaryButton(
                {
                    onSave(if (pi) provider.trim() else null, key)
                },
                enabled = key.length >= 8 && !key.startsWith("!") && '$' !in key && (!pi || provider.isNotBlank()) && keyring == null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save and check") }
            if (pi) Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(HerdrIcons.Lock, null, tint = c.muted, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(Space.xs))
                Text("Checked with pi auth check --provider ${provider.trim().ifEmpty { "…" }} --no-refresh", style = MaterialTheme.typography.labelMedium, color = c.muted)
            }
        }
        Spacer(Modifier.padding(bottom = Space.l))
    }
}

/** The steps to move each key out of its shell profile: shown, nothing edited. */
@Composable
private fun MoveSheet(report: LoginsReport, machine: String, onDismiss: () -> Unit) {
    val c = Herdr.colors
    HerdrSheet(onDismissRequest = onDismiss) {
        SheetTitle("Move keys out of shell profiles", subtitle = "On $machine. The app shows the steps and changes nothing.")
        Column(Modifier.padding(horizontal = Space.xl), verticalArrangement = Arrangement.spacedBy(Space.l)) {
            Text(
                "A key exported from a profile reaches every program and agent started from a shell, and sits in plain text in a file that gets copied and backed up. " +
                    "Each CLI's own store keeps it to that CLI.",
                style = MaterialTheme.typography.bodySmall, color = c.muted,
            )
            report.profileKeys.forEach { k -> MoveSteps(k) }
        }
        Spacer(Modifier.padding(bottom = Space.l))
    }
}

@Composable
private fun MoveSteps(k: ProfileKey) {
    val c = Herdr.colors
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        FieldLabel("${k.name} · ${k.file}, line ${k.line}")
        LoginWords.moveSteps(k).forEachIndexed { i, step ->
            Row {
                Text("${i + 1}.", style = MaterialTheme.typography.bodyMedium, color = c.muted, modifier = Modifier.width(22.dp))
                Text(step, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
    }
}
