package com.makefunstuff.herdr.ui.machines

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.makefunstuff.herdr.Routes
import com.makefunstuff.herdr.core.ssh.AddressKind
import com.makefunstuff.herdr.core.ssh.PairError
import com.makefunstuff.herdr.core.ssh.PairOffer
import com.makefunstuff.herdr.core.ssh.Pairing
import com.makefunstuff.herdr.data.MachineStore
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.ScannerUnavailable
import com.makefunstuff.herdr.ui.common.clipboardText
import com.makefunstuff.herdr.ui.common.scanCode
import com.makefunstuff.herdr.ui.design.ErrorMessage
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrShapes
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.InfoLine
import com.makefunstuff.herdr.ui.design.MonoBlock
import com.makefunstuff.herdr.ui.design.PrimaryButton
import com.makefunstuff.herdr.ui.design.QuietButton
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.design.Spinner
import com.makefunstuff.herdr.ui.design.Toasts
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** What the code scanned says, and how far adding its machine got. */
private data class Found(
    val offer: PairOffer,
    /** Where the machine answered with one of the code's host keys; null while looking or when it didn't. */
    val reached: Pairing.Reached? = null,
    val error: String? = null,
    val pairing: Boolean = false,
)

/** The command that shows the code, on a machine that has herdr-push. */
private const val PAIR_COMMAND = "python3 ~/.local/share/herdr-push/herdr-push pair"

/** Fetches herdr-push to a machine that has none yet (one the app hasn't set up for notifications). */
private const val FETCH_COMMAND = "curl -fsSLO https://raw.githubusercontent.com/makefunstuff/herdr-android/HEAD/host/herdr-push/herdr-push\n" +
    "python3 herdr-push pair"

/**
 * Add machine, by its code: `herdr-push pair` on the machine shows a QR code; the phone's own scanner
 * reads it (or the link is pasted), the app finds the machine at one of its addresses with the host
 * key the code names, and on **Pair and add** installs this phone's key there through the code's
 * one-time key and pins the host key. [onType] goes to typing the address instead.
 */
@Composable
fun PairMachineScreen(nav: NavController, onType: () -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var found by remember { mutableStateOf<Found?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var looking by remember { mutableStateOf<Job?>(null) }

    fun look(offer: PairOffer) {
        looking?.cancel()
        found = Found(offer)
        looking = scope.launch {
            found = try {
                Found(offer, reached = Pairing.reach(offer))
            } catch (e: PairError) {
                Found(offer, error = e.message)
            }
        }
    }

    fun take(text: String?) {
        val offer = text?.let(PairOffer::parse)
        when {
            text == null -> {}
            offer == null -> problem = "That isn't a code from herdr-push pair."
            else -> {
                problem = null
                look(offer)
            }
        }
    }

    fun scan() {
        problem = null
        scope.launch {
            try {
                take(scanCode(context))
            } catch (e: ScannerUnavailable) {
                problem = e.message
            }
        }
    }

    fun paste() {
        val text = context.clipboardText()
        if (text.isNullOrBlank()) problem = "Nothing to paste: copy the link herdr-push pair prints under its code." else take(text)
    }

    fun pair() {
        val f = found ?: return
        val reached = f.reached ?: return
        found = f.copy(pairing = true, error = null)
        scope.launch {
            try {
                val same = container.machines.machines.value.firstOrNull {
                    it.viaMachineId == null && it.user == f.offer.user && it.host == reached.address && it.port == f.offer.port
                }
                val paired = Pairing.pair(f.offer, reached, container.identity.get(), same?.id ?: MachineStore.newId())
                val machine = same?.let { paired.copy(label = it.label, herdrPath = it.herdrPath, enabled = it.enabled, allowHostScripts = it.allowHostScripts, taskSource = it.taskSource) } ?: paired
                container.machines.upsert(machine)
                Toasts.show("${machine.label} added", error = false)
                nav.navigate(Routes.machine(machine.id)) { popUpTo(Routes.EDIT) { inclusive = true } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                found = f.copy(pairing = false, error = e.message ?: "Couldn't pair with ${f.offer.name}.")
            }
        }
    }

    Scaffold(
        containerColor = Herdr.colors.ground,
        topBar = { HerdrTopBar("Add machine", onBack = { nav.popBackStack() }) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = Space.screen, vertical = Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            ScanFrame(onScan = ::scan)
            problem?.let { ErrorMessage(it) }
            val f = found
            if (f == null) {
                HowTo()
                PrimaryButton(onClick = ::scan, modifier = Modifier.fillMaxWidth()) {
                    Icon(HerdrIcons.ScanQrCode, null, modifier = Modifier.size(18.dp))
                    Text("Scan the code")
                }
            } else {
                FoundCard(f)
                f.error?.let { ErrorMessage(it, onRetry = if (f.reached == null) ({ look(f.offer) }) else ::pair) }
                PrimaryButton(onClick = ::pair, enabled = f.reached != null && !f.pairing, modifier = Modifier.fillMaxWidth()) {
                    if (f.pairing) Spinner(size = 18.dp)
                    Text(if (f.pairing) "Pairing with ${f.offer.name}" else "Pair and add ${f.offer.name}")
                }
            }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                QuietButton(onClick = ::paste) {
                    Icon(HerdrIcons.ClipboardPaste, null, modifier = Modifier.size(16.dp))
                    Text("Paste the link")
                }
                QuietButton(onClick = onType) { Text("Type the address instead") }
            }
        }
    }
}

/** The scanner's place on the screen: a tap opens the phone's own scanner (its own screen). */
@Composable
private fun ScanFrame(onScan: () -> Unit) {
    val c = Herdr.colors
    Column(
        Modifier.fillMaxWidth().clip(HerdrShapes.Card).background(c.panel)
            .clickable(onClickLabel = "Scan the code", onClick = onScan)
            .padding(vertical = Space.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.l),
    ) {
        Box(Modifier.size(148.dp), contentAlignment = Alignment.Center) {
            val corner = c.accent
            Canvas(Modifier.fillMaxSize()) {
                val w = 3.dp.toPx()
                val len = 28.dp.toPx()
                val (l, t, r, b) = listOf(w / 2, w / 2, size.width - w / 2, size.height - w / 2)
                listOf(
                    Offset(l, t) to Pair(1f, 1f), Offset(r, t) to Pair(-1f, 1f),
                    Offset(l, b) to Pair(1f, -1f), Offset(r, b) to Pair(-1f, -1f),
                ).forEach { (o, d) ->
                    drawLine(corner, o, o + Offset(d.first * len, 0f), w, StrokeCap.Round)
                    drawLine(corner, o, o + Offset(0f, d.second * len), w, StrokeCap.Round)
                }
            }
            Icon(HerdrIcons.QrCode, null, tint = c.strong, modifier = Modifier.size(84.dp))
        }
        Text("Point at the code from herdr-push pair", style = MaterialTheme.typography.bodyMedium, color = c.muted)
    }
}

/** How to get the code on the machine. */
@Composable
private fun HowTo() {
    QuietCard(padded = true) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Text(
                "On the machine, run",
                style = MaterialTheme.typography.titleSmall,
                color = Herdr.colors.strong,
                modifier = Modifier.semantics { heading() },
            )
            MonoBlock(PAIR_COMMAND)
            Text(
                "It shows a code for five minutes. Scanning it adds this phone's key there and checks the machine's " +
                    "host key, so there's nothing to copy or type. On Windows, use python in place of python3. " +
                    "Where herdr-push isn't yet, or doesn't know pair:",
                style = MaterialTheme.typography.bodyMedium,
                color = Herdr.colors.muted,
            )
            MonoBlock(FETCH_COMMAND)
        }
    }
}

/** What the code names, and where the phone found it. */
@Composable
private fun FoundCard(f: Found) {
    val c = Herdr.colors
    val offer = f.offer
    QuietCard(padded = true) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    f.reached != null -> Icon(HerdrIcons.CircleCheck, null, tint = c.done, modifier = Modifier.size(22.dp))
                    f.error != null -> Icon(HerdrIcons.CircleAlert, null, tint = c.red, modifier = Modifier.size(22.dp))
                    else -> Spinner(size = 22.dp)
                }
                Spacer(Modifier.size(Space.m))
                Text(
                    when {
                        f.reached != null -> "Found ${offer.name}"
                        f.error != null -> "${offer.name} isn't answering"
                        else -> "Looking for ${offer.name}"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = c.strong,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Spacer(Modifier.height(Space.xs))
            val reached = f.reached
            InfoLine("Address", offer.target(reached?.address ?: offer.addresses.first()), mono = true)
            InfoLine("Host key", short(reached?.hostKey ?: offer.hostKeys.first()), mono = true)
            InfoLine(
                "Reached via",
                when {
                    reached == null -> if (f.error == null) "trying ${offer.addresses.size} " + if (offer.addresses.size == 1) "address" else "addresses" else "none of ${offer.addresses.size}"
                    reached.kind == AddressKind.Other -> "its address"
                    else -> reached.kind.label.replaceFirstChar { it.uppercase() }
                },
            )
            if (offer.session != "default") InfoLine("herdr session", offer.session)
            Text(
                "Adds this phone's key to ${offer.name}'s authorized_keys with the code (one-time, five minutes) and pins its host key.",
                style = MaterialTheme.typography.bodySmall,
                color = c.muted,
                modifier = Modifier.padding(top = Space.s),
            )
        }
    }
}

/** `SHA256:q7Lw…9fE`: enough of a fingerprint to compare with what the machine printed. */
private fun short(fingerprint: String): String {
    val body = fingerprint.removePrefix("SHA256:")
    return if (body.length <= 12) fingerprint else "SHA256:${body.take(6)}…${body.takeLast(4)}"
}
