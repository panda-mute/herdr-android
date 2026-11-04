package com.makefunstuff.herdr.core.chat

/**
 * What a blocked agent is asking, read off its screen: the question, the lines that explain it
 * (the command it wants to run, the folder it wants to trust), and the choices, if it offers any.
 *
 * Claude Code's questions (its AskUserQuestion form) come with more: [steps] for a form of several
 * questions (`←  ☒ Route  ☐ Author  ✔ Submit  →`), boxes to tick for a [multiSelect], a "Type
 * something." row for your own words and "Chat about this".
 */
data class BlockedPrompt(
    val question: String,
    val details: List<String>,
    val options: List<PromptOption>,
    /** Which option the agent's cursor is on; -1 when it is on a row that is no option (Claude's Submit). */
    val selected: Int,
    /** The agent's own footer, such as "Esc to cancel · Tab to amend". */
    val hint: String?,
    /** A form of several questions: each one, answered or not. Empty for a single question. */
    val steps: List<PromptStep> = emptyList(),
    /** Several options can be ticked ([PromptOption.checked]), then sent with [keysForTicks]. */
    val multiSelect: Boolean = false,
    /** The row the cursor is on, counted as the arrow keys move ([PromptOption.row]). */
    val cursor: Int = options.getOrNull(selected)?.row ?: selected,
) {
    /**
     * Keys that pick option [index]: move the cursor there, then Enter. This works for claude,
     * codex and pi menus alike, numbered or not.
     */
    fun keysFor(index: Int): List<String> = moves(options[index].row) + "enter"

    /** Arrow keys from the cursor to [row]. */
    private fun moves(row: Int, from: Int = cursor): List<String> {
        val delta = row - from
        val move = if (delta >= 0) "down" else "up"
        return List(kotlin.math.abs(delta)) { move }
    }

    /**
     * Keys that leave exactly [wanted] ticked in a multiple choice and send it: each option that
     * differs is toggled (Enter in Claude's form, Space where the footer says so), then the answer
     * goes on (→ to Claude's next question or its review; Enter where Space toggles).
     */
    fun keysForTicks(wanted: Set<Int>): List<String> {
        val space = hint?.let { SPACE_TOGGLES.containsMatchIn(it) } == true
        val keys = mutableListOf<String>()
        var at = cursor
        options.forEachIndexed { i, o ->
            if (o.checked == null || (i in wanted) == o.checked) return@forEachIndexed
            keys += moves(o.row, at)
            keys += if (space) "space" else "enter"
            at = o.row
        }
        keys += if (space) "enter" else "right"
        return keys
    }

    /** Keys that put the cursor in the "Type something." row, where typed words become the answer; null when there is none. */
    fun keysToType(): List<String>? = customIndex?.let { moves(options[it].row) }

    /** What each option does, for the card's buttons and the notification's. */
    fun role(index: Int): OptionRole {
        val label = options[index].label
        return when {
            CUSTOM.matches(label) -> OptionRole.Custom
            CHAT.matches(label) -> OptionRole.Chat
            NO.containsMatchIn(label) && TELL.containsMatchIn(label) -> OptionRole.TellInstead
            NO.containsMatchIn(label) -> OptionRole.No
            ALWAYS.containsMatchIn(label) && (YES.containsMatchIn(label) || label.startsWith("Always", ignoreCase = true)) -> OptionRole.Always
            YES.containsMatchIn(label) -> OptionRole.Yes
            else -> OptionRole.Other
        }
    }

    /**
     * A short label for a button: the key the menu binds goes ("Yes, proceed (y)"), a permanent
     * allow says what it allows ("Always for npm run"), and "No, and tell Claude what to do
     * differently" is "No, tell it…".
     */
    fun shortLabel(index: Int): String {
        val label = KEY_SUFFIX.replace(options[index].label, "").trim()
        return when (role(index)) {
            OptionRole.TellInstead -> "No, tell it…"
            OptionRole.Always -> {
                DONT_ASK_FOR.find(label)?.let { m ->
                    val what = m.groupValues[1].trim().removePrefix("commands that start with ").removeSuffix(" commands").trim()
                    "Always for $what"
                } ?: ALWAYS_ALLOW.find(label)?.let { "Always allow ${it.groupValues[1].trim()}" }
                ?: SESSION.find(label)?.let { "Yes, for this session" }
                ?: label
            }
            else -> label
        }
    }

    /** The option that says yes ("Yes", "Yes, proceed", "Trust and continue"), when one does; never a permanent "always". */
    val approveIndex: Int? get() = options.indices.firstOrNull { role(it) == OptionRole.Yes }

    /** The option that says no ("No", "No, exit", "No, and tell Codex what to do differently"), when one does. */
    val denyIndex: Int? get() = options.indices.firstOrNull { role(it) == OptionRole.No || role(it) == OptionRole.TellInstead }

    /** A plain yes-or-no question: both a yes and a no option, and they differ. */
    val yesNo: Boolean get() = approveIndex != null && denyIndex != null && approveIndex != denyIndex

    /** Claude's "Type something." row: the answer in your own words. */
    val customIndex: Int? get() = options.indices.firstOrNull { role(it) == OptionRole.Custom }

    /** The keys the footer names besides the menu's own (Tab to amend, Esc to cancel), as buttons. */
    val hintKeys: List<HintKey> get() = hint?.let(::hintKeys).orEmpty()

    /**
     * Whether this is the same question about the same thing, with the same choices: what a press checks
     * before it presses. Claude asks "Do you want to proceed?" with the same options for every command, so
     * the lines that say what it would do ([details]: the command, the file) count too.
     */
    fun same(other: BlockedPrompt?): Boolean =
        other != null && other.question == question && other.details == details &&
            other.options.map { it.label } == options.map { it.label } && other.multiSelect == multiSelect

    companion object {
        private val YES = Regex("^(yes|allow|approve|proceed|accept|trust|continue|ok)\\b", RegexOption.IGNORE_CASE)
        private val NO = Regex("^(no|deny|reject|decline|cancel|don'?t|do not)\\b", RegexOption.IGNORE_CASE)
        private val ALWAYS = Regex("don'?t ask again|\\balways\\b|(for|during) (this|the) session|\\bremember\\b", RegexOption.IGNORE_CASE)
        private val TELL = Regex("\\btell\\b.*\\b(differently|instead)\\b|what to do", RegexOption.IGNORE_CASE)
        private val CUSTOM = Regex("^type something\\.?$", RegexOption.IGNORE_CASE)
        private val CHAT = Regex("^chat about this$", RegexOption.IGNORE_CASE)
        private val KEY_SUFFIX = Regex("\\s*\\((?:[a-z]|esc|tab|shift\\+tab)\\)$", RegexOption.IGNORE_CASE)
        private val DONT_ASK_FOR = Regex("don'?t ask again for (.+)$", RegexOption.IGNORE_CASE)
        private val ALWAYS_ALLOW = Regex("always allow (.+)$", RegexOption.IGNORE_CASE)
        private val SESSION = Regex("(for|during) (this|the) session", RegexOption.IGNORE_CASE)
        private val SPACE_TOGGLES = Regex("\\b(space|␣)\\b[^·]*toggle", RegexOption.IGNORE_CASE)
        private val TICK = Regex("^(?:\\[([ xX✓✔])\\]|([☐☑☒]))\\s*(.*)$")
        /** Claude Code's question form: its footer, its question tabs and a single question's chip. */
        private val CLAUDE_ASK_HINT = Regex("enter to select.*(↑/↓|tab/arrow keys) to navigate.*esc to cancel", RegexOption.IGNORE_CASE)
        private val CLAUDE_TABS = Regex("^←\\s+[☐☒☑✔]")
        private val CLAUDE_CHIP = Regex("^[☐☒☑✔]\\s+\\S")
        private val CLAUDE_ROW = Regex("^\\s*([❯›>]\\s*)?(\\d{1,2})\\.\\s+(\\S.*)$")
        private val CLAUDE_SUBMIT = Regex("^\\s*([❯›>]\\s*)?(Submit|Next)\\s*$")
        private val HINT_KEY = Regex("^(?:press\\s+)?(esc|escape|⎋|tab|⇥|shift\\+tab|⇧\\s?tab|ctrl\\+[a-z])\\s+(?:to\\s+)?([a-z][a-z ]*[a-z])$", RegexOption.IGNORE_CASE)
        private val CURSOR = Regex("^(\\s*)[❯›▶►➤→>]\\s+(\\S.*)$")
        private val NUMBERED = Regex("^(\\d{1,2})[.)]\\s+(.+)$")
        private val HARD_RULE = Regex("^[─━═▔▁]{6,}$")
        private val SOFT_RULE = Regex("^[╌┄┈╍┅┉\\-–—·.]{6,}$")
        private val BOX_EDGE = Regex("^[╭╮╰╯│┃]")
        /** Claude's advice inside its prompt ("Tip: auto mode handles these prompts for you…"): not about this request. */
        private val TIP = Regex("^Tip: ")

        /** Parses the bottom of a blocked agent's screen; null when no menu is visible. */
        fun parse(screen: String): BlockedPrompt? {
            val lines = screen.lines().map(::unbox)
            claudeQuestion(lines)?.let { return it }
            val width = lines.maxOfOrNull { it.length } ?: return null
            val cursorAt = lines.indices.lastOrNull { CURSOR.matches(lines[it]) } ?: return null
            val match = CURSOR.matchEntire(lines[cursorAt])!!
            val textCol = match.groups[2]!!.range.first
            val numbered = NUMBERED.matches(match.groupValues[2])

            fun indent(line: String) = line.length - line.trimStart().length
            fun isOption(i: Int): Boolean {
                val line = lines[i]
                if (i == cursorAt) return true
                if (line.isBlank() || indent(line) != textCol) return false
                return !numbered || NUMBERED.matches(line.trim())
            }
            fun isContinuation(i: Int) = lines[i].isNotBlank() && indent(lines[i]) > textCol

            var start = cursorAt
            while (start > 0 && (isOption(start - 1) || isContinuation(start - 1))) start--
            while (start < cursorAt && !isOption(start)) start++
            var end = cursorAt
            while (end + 1 < lines.size && (isOption(end + 1) || isContinuation(end + 1))) end++

            val options = mutableListOf<PromptOption>()
            var selected = 0
            var previous = ""
            for (i in start..end) {
                val raw = if (i == cursorAt) match.groupValues[2] else lines[i].trim()
                if (isOption(i)) {
                    if (i == cursorAt) selected = options.size
                    val num = NUMBERED.matchEntire(raw)
                    options += PromptOption(num?.groupValues?.get(2)?.trim() ?: raw, num?.groupValues?.get(1)?.toInt())
                } else if (options.isNotEmpty()) {
                    // A line that reached the right edge wrapped, so the next one continues it (with no
                    // space when it was cut mid-word); after a short line, the text describes the option.
                    val last = options.removeAt(options.size - 1)
                    val joiner = if (previous.length >= width) "" else " "
                    options += when {
                        last.description != null -> last.copy(description = last.description + (if (previous.length >= width - 12) joiner else " ") + raw)
                        previous.length >= width - 12 -> last.copy(label = last.label + joiner + raw)
                        else -> last.copy(description = raw)
                    }
                }
                previous = lines[i]
            }
            if (options.isEmpty()) return null
            // Boxes to tick ("[ ] LM-O", "☑ Tests"): a multiple choice.
            val ticked = options.map { o -> TICK.matchEntire(o.label) }
            val multi = ticked.count { it != null } >= 2
            val rows = options.mapIndexed { i, o ->
                val t = ticked[i]
                if (multi && t != null) o.copy(label = t.groupValues[3], checked = (t.groupValues[1].ifEmpty { t.groupValues[2] }) !in setOf(" ", "☐"), row = i)
                else o.copy(row = i)
            }

            val above = context(lines, start, width).filterNot { CLAUDE_TABS.containsMatchIn(it) || TIP.containsMatchIn(it) }
            val questionAt = above.indexOfLast { it.endsWith("?") }.takeIf { it >= 0 }
                ?: above.indexOfLast { it.contains("? ") }.takeIf { it >= 0 }
                ?: 0
            val question = above.getOrNull(questionAt) ?: "The agent needs your answer"
            val details = above.filterIndexed { i, _ -> i != questionAt }
            val hint = lines.drop(end + 1).take(3).firstOrNull { it.isNotBlank() }?.trim()
            return BlockedPrompt(question, details, rows, selected, hint, steps = tabsAbove(lines, start), multiSelect = multi)
        }

        /**
         * Claude Code's question form (AskUserQuestion), under its own footer: numbered rows with a
         * description under each, "Type something." for your own words, a rule, then "Chat about
         * this"; in a multiple choice a box on each row and a Submit (or Next) line before the rule,
         * which the cursor stops on too. Over it the question, under the tabs of a form of several.
         */
        private fun claudeQuestion(lines: List<String>): BlockedPrompt? {
            val hintAt = lines.indexOfLast { CLAUDE_ASK_HINT.containsMatchIn(it) }.takeIf { it >= 0 } ?: return null
            val from = maxOf(0, hintAt - 64)
            val found = (from until hintAt).mapNotNull { i -> CLAUDE_ROW.matchEntire(lines[i])?.let { i to it } }
            // The menu is the last run that starts at 1.
            val first = found.indexOfLast { it.second.groupValues[2] == "1" }.takeIf { it >= 0 } ?: return null
            val rows = found.drop(first)
            if (rows.size < 2 || rows.withIndex().any { (k, r) -> r.second.groupValues[2].toInt() != k + 1 }) return null
            // A multiple choice's Submit line between the last two rows takes the cursor; "Next" (older versions) does not.
            val submitAt = (rows[rows.size - 2].first + 1 until rows.last().first).firstOrNull { CLAUDE_SUBMIT.matches(lines[it]) }
            val submitLine = submitAt?.let { CLAUDE_SUBMIT.matchEntire(lines[it]) }
            val submitStop = submitLine?.groupValues?.get(2) == "Submit"
            val options = mutableListOf<PromptOption>()
            var selected = -1
            var cursor = -1
            rows.forEachIndexed { k, (at, m) ->
                var label = m.groupValues[3].trim()
                val tick = TICK.matchEntire(label)
                if (tick != null) label = tick.groupValues[3].trim()
                val row = if (submitStop && k == rows.size - 1) k + 1 else k
                // The description: the lines under the row, indented past its number, up to the next row or rule.
                val end = rows.getOrNull(k + 1)?.first ?: hintAt
                val description = (at + 1 until end).asSequence().map { lines[it] }
                    .takeWhile { it.isNotBlank() && !HARD_RULE.matches(it.trim()) && !CLAUDE_SUBMIT.matches(it) }
                    .map { it.trim() }.joinToString(" ").ifEmpty { null }
                val custom = CUSTOM.matches(label)
                options += PromptOption(label, k + 1, description, checked = if (tick != null && !custom) tick.groupValues[1] != " " else null, row = row)
                if (m.groupValues[1].isNotEmpty()) { selected = k; cursor = row }
            }
            if (submitStop && submitLine!!.groupValues[1].isNotEmpty()) cursor = rows.size - 1
            if (cursor < 0) return null
            val multi = options.any { it.checked != null }

            // The question: the text right over the first row, joined back where it wrapped.
            val text = mutableListOf<String>()
            var i = rows.first().first - 1
            while (i >= 0 && rows.first().first - i < 30) {
                val t = lines[i].trim()
                if (t.isEmpty()) { if (text.isNotEmpty()) break; i--; continue }
                if (CLAUDE_CHIP.containsMatchIn(t) || CLAUDE_TABS.containsMatchIn(t) || HARD_RULE.matches(t)) break
                text.add(0, t)
                i--
            }
            val question = text.joinToString(" ").ifEmpty { return null }
            return BlockedPrompt(
                question, emptyList(), options, selected, lines[hintAt].trim(),
                steps = tabsAbove(lines, rows.first().first), multiSelect = multi, cursor = cursor,
            )
        }

        /**
         * Claude's question tabs over a form of several questions, `←  ☒ Route  ☐ Author  ✔ Submit  →`:
         * ☐ unanswered, ☒ answered, ✔ the Submit step. The one asked now is the first unanswered,
         * or Submit once all are. Empty when there are no tabs, or only one question.
         */
        private fun tabsAbove(lines: List<String>, before: Int): List<PromptStep> {
            for (i in before - 1 downTo maxOf(0, before - 60)) {
                val t = lines[i].trim()
                if (!CLAUDE_TABS.containsMatchIn(t)) continue
                val tabs = Regex("([☐☒☑✔])\\s+(.+?)(?=\\s{2,}|\\s*→?\\s*$)").findAll(t.removePrefix("←").trim())
                    .map { it.groupValues[1] to it.groupValues[2].trim() }.toList()
                val questions = tabs.filter { it.first != "✔" }
                if (questions.size < 2) return emptyList()
                val current = questions.indexOfFirst { it.first == "☐" }
                return questions.mapIndexed { k, (mark, label) -> PromptStep(label, answered = mark != "☐", current = k == current) } +
                    PromptStep("Submit", answered = false, current = current < 0, submit = true)
            }
            return emptyList()
        }

        /** "Esc to cancel · Tab to amend" → Amend (Tab) and Cancel (Esc); Enter and the arrows are the menu's own. */
        private fun hintKeys(hint: String): List<HintKey> =
            hint.split('·', '•', '|', ',', ';').flatMap { it.split(Regex("\\s+or\\s+", RegexOption.IGNORE_CASE)) }
                .mapNotNull { part -> HINT_KEY.matchEntire(part.trim().trimEnd('.')) }
                .map { m ->
                    val key = when (val k = m.groupValues[1].lowercase()) {
                        "esc", "escape", "⎋" -> "esc"
                        "tab", "⇥" -> "tab"
                        "shift+tab", "⇧tab", "⇧ tab" -> "shift+tab"
                        else -> k
                    }
                    HintKey(m.groupValues[2].trim().replaceFirstChar { it.uppercase() }, key)
                }
                .distinctBy { it.key }
                // Cancel last, as the web UI puts it.
                .sortedBy { it.key == "esc" }

        /** The lines above the menu that explain it, joined back into sentences where they wrapped. */
        private fun context(lines: List<String>, menuStart: Int, width: Int): List<String> {
            val picked = mutableListOf<String>()
            var i = menuStart - 1
            while (i >= 0 && picked.size < 14) {
                val line = lines[i]
                val text = line.trim()
                if (HARD_RULE.matches(text) || CURSOR.matches(line) || text.startsWith("•") || text.startsWith("●") || BOX_EDGE.containsMatchIn(text)) break
                picked.add(0, line)
                i--
            }
            val out = mutableListOf<String>()
            var prev: String? = null
            for (line in picked) {
                val text = line.trim()
                if (text.isEmpty() || SOFT_RULE.matches(text)) {
                    prev = null
                    continue
                }
                val p = prev
                when {
                    p != null && p.length >= width -> out[out.size - 1] = out.last() + text
                    // A line indented deeper than the one above is its block (Claude's command under "Bash command"), not its rest.
                    p != null && text.first().isLowerCase() && !out.last().endsWith(".") && !out.last().endsWith(":") &&
                        line.length - line.trimStart().length <= p.length - p.trimStart().length ->
                        out[out.size - 1] = out.last() + " " + text
                    else -> out += text
                }
                prev = line
            }
            return out
        }

        /** Drops the side borders of a boxed dialog so its contents parse like any other screen. */
        private fun unbox(line: String): String {
            val t = line.trimEnd()
            val lead = t.indexOfFirst { !it.isWhitespace() }
            if (lead < 0) return ""
            if ((t[lead] == '│' || t[lead] == '┃') && t.length > lead + 1 && (t.last() == '│' || t.last() == '┃')) {
                return " ".repeat(lead) + t.substring(lead + 1, t.length - 1).trimEnd()
            }
            return t
        }
    }
}

data class PromptOption(
    val label: String,
    val number: Int? = null,
    val description: String? = null,
    /** In a multiple choice, whether its box is ticked now; null for a row with no box. */
    val checked: Boolean? = null,
    /** Its place in the order the arrow keys move through the menu. */
    val row: Int = 0,
)

/** One question of a form of several; [submit] is the last step, where the answers are sent. */
data class PromptStep(val label: String, val answered: Boolean, val current: Boolean, val submit: Boolean = false)

/** A key the agent's footer names, as a button: "Amend" for Tab, "Cancel" for Esc. */
data class HintKey(val label: String, val key: String)

/** What an option does: the card shows Yes first, a permanent allow behind the fingerprint, and "No, tell it…" opening the reply. */
enum class OptionRole { Yes, Always, No, TellInstead, Custom, Chat, Other }
