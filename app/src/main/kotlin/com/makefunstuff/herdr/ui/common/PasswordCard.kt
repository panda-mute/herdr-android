package com.makefunstuff.herdr.ui.common

import androidx.compose.ui.graphics.RectangleShape

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.makefunstuff.herdr.ui.design.HerdrField
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.PrimaryButton
import com.makefunstuff.herdr.ui.theme.CodeStyle
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Radius
import com.makefunstuff.herdr.ui.theme.Space

/**
 * A terminal that asks for a password ([com.makefunstuff.herdr.core.chat.PasswordPrompt]): the
 * line that asks, a masked field (dots, the keyboard's password mode, so it neither shows nor
 * learns it; the eye shows it), and Send, which types it with Enter. What is typed stays in this
 * card only: no draft, no history. X puts it away until the terminal asks again. It never takes
 * the keyboard by itself: a tap on the field does, so what you type goes on into the terminal
 * if the card was wrong.
 */
@Composable
fun PasswordCard(asks: String, onSend: (String) -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val c = Herdr.colors
    var text by remember(asks) { mutableStateOf("") }
    var shown by remember(asks) { mutableStateOf(false) }
    fun send() {
        if (text.isEmpty()) return
        val secret = text
        text = ""
        onSend(secret)
    }
    val shape = RectangleShape
    Surface(
        color = c.card,
        border = BorderStroke(1.dp, c.lineStrong),
        shape = shape,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = Space.l, end = Space.xs, top = Space.xs, bottom = Space.m), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(HerdrIcons.Lock, null, tint = c.notice, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(Space.s))
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                    Text("Asks for a password", style = MaterialTheme.typography.labelMedium, color = c.notice)
                    Text(asks, style = CodeStyle, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                RoundIcon(HerdrIcons.X, "Type it in the terminal instead", onDismiss)
            }
            Row(Modifier.padding(end = Space.s), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                HerdrField(
                    value = text,
                    onValueChange = { text = it.replace("\n", "") },
                    singleLine = true,
                    placeholder = { Text("Password") },
                    visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    trailingIcon = { RoundIcon(if (shown) HerdrIcons.EyeOff else HerdrIcons.Eye, if (shown) "Hide the password" else "Show the password") { shown = !shown } },
                    modifier = Modifier.weight(1f),
                )
                PrimaryButton("Send", enabled = text.isNotEmpty()) { send() }
            }
        }
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(RectangleShape).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Herdr.colors.muted, modifier = Modifier.size(18.dp)) }
}
