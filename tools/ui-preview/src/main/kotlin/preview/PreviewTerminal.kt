package preview

import androidx.compose.ui.test.ComposeUiTest
import com.makefunstuff.herdr.ui.terminal.CellPoint
import com.makefunstuff.herdr.ui.terminal.TerminalView

/**
 * The terminal in a scene. [selection]: "Select text" starts it here (anchor, cursor), as a drag
 * across the grid would on a phone; null starts it at the cell tapped. [live]: the screen's live
 * terminal, which [scrollBack] swipes back as a finger would.
 */
object PreviewTerminal {
    @Volatile var selection: Pair<CellPoint, CellPoint>? = null
    @Volatile var live: TerminalView? = null
}

/** A swipe back (down the screen) on the live terminal, then the screen settles. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
fun ComposeUiTest.scrollBack(lines: Int = 5) {
    runOnUiThread { PreviewTerminal.live?.onScroll?.invoke(lines) }
    settle()
}
