package com.makefunstuff.herdr.core.terminal

/**
 * A pane's recent output laid out as one tall screen the phone scrolls by itself.
 *
 * Scrolling the live pane would scroll it on the computer too: herdr's viewport is shared, and a
 * full-screen program such as Claude Code takes the wheel and scrolls its own view, which the
 * desktop window shows. So the phone reads the history once (`pane.read`, with its colours) and
 * scrolls that copy instead.
 */
object PhoneHistory {
    /** Enough rows for anything a phone screen would scroll through. */
    const val LINES = 2000

    /**
     * An emulator [cols] wide and as tall as [ansiText] needs once wrapped, with the text in it.
     * Trailing blank lines are dropped, so the last line of output sits at the bottom.
     */
    fun build(ansiText: String, cols: Int): TerminalEmulator {
        val width = cols.coerceAtLeast(1)
        val lines = ansiText.replace("\r\n", "\n").trimEnd('\n', ' ').split('\n')
        val rows = lines.sumOf { line -> maxOf(1, (visibleWidth(line) + width - 1) / width) }
        val emu = TerminalEmulator(width, rows.coerceAtLeast(1))
        val text = lines.joinToString("\r\n") { it.trimEnd('\r') }
        synchronized(emu.lock) { emu.feed(text.toByteArray(Charsets.UTF_8)) }
        return emu
    }

    private val ANSI = Regex("\u001B\\[[0-?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)|\u001B[@-Z\\\\-_]")

    internal fun visibleWidth(line: String): Int {
        val plain = line.replace(ANSI, "")
        var w = 0
        var i = 0
        while (i < plain.length) {
            val cp = plain.codePointAt(i)
            w += CharWidth.of(cp).coerceAtLeast(0)
            i += Character.charCount(cp)
        }
        return w
    }
}
