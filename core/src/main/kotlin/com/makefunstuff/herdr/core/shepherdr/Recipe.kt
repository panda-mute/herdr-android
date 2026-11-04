package com.makefunstuff.herdr.core.shepherdr

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * A recipe artifact (Shepherdr 3.1, `core/recipe.js`): what an agent shows the person, written once as data
 * (`recipe.json` beside its pictures) and drawn natively by each side. The app draws it in Compose as the web app
 * draws it in HTML; a block of a type it doesn't know is [RecipeBlock.Unknown], shown as one quiet line.
 */
data class Recipe(val blocks: List<RecipeBlock>)

sealed interface RecipeBlock {
    /** What notes and answers point at: its own, else `b1`, `b2`… in order (`withIds`). */
    val id: String

    data class Heading(override val id: String, val text: String, val level: Int = 2) : RecipeBlock
    data class Text(override val id: String, val md: String) : RecipeBlock
    data class Bullets(override val id: String, val items: List<String>, val ordered: Boolean = false) : RecipeBlock
    data class Table(override val id: String, val columns: List<Column>, val rows: List<List<String>>) : RecipeBlock {
        data class Column(val label: String, val align: String? = null)
    }
    data class Chart(override val id: String, val kind: String, val labels: List<String>, val series: List<Series>) : RecipeBlock {
        data class Series(val name: String, val values: List<Double>)
    }
    data class Stats(override val id: String, val items: List<Stat>) : RecipeBlock {
        data class Stat(val label: String, val value: String, val note: String? = null)
    }
    data class Image(override val id: String, val src: String, val caption: String? = null, val w: Int? = null, val h: Int? = null) : RecipeBlock
    data class Pair(override val id: String, val before: Image, val after: Image) : RecipeBlock
    data class Code(override val id: String, val text: String, val lang: String? = null) : RecipeBlock
    data class Callout(override val id: String, val tone: String, val title: String?, val md: String?) : RecipeBlock
    data class Columns(override val id: String, val columns: List<List<RecipeBlock>>) : RecipeBlock
    data class Tabs(override val id: String, val tabs: List<Tab>) : RecipeBlock {
        data class Tab(val label: String, val blocks: List<RecipeBlock>)
    }
    data class Details(override val id: String, val summary: String, val blocks: List<RecipeBlock>) : RecipeBlock
    data class Divider(override val id: String) : RecipeBlock
    data class Choice(override val id: String, val question: String, val options: List<Option>) : RecipeBlock {
        data class Option(val label: String, val description: String? = null)
    }
    data class Form(override val id: String, val title: String?, val fields: List<Field>, val submit: String?) : RecipeBlock {
        /** [kind] `text`, `textarea`, `select` or `checkbox`. */
        data class Field(val name: String, val label: String?, val kind: String, val options: List<String>)
    }
    data class Button(override val id: String, val label: String, val reply: String) : RecipeBlock
    data class Unknown(override val id: String, val type: String) : RecipeBlock
}

/** Reading recipes, and the words their answers and notes are sent in (`core/recipe.js`'s, word for word). */
object Recipes {
    const val RECIPE = 1

    /** [json] (`recipe.json`) as a [Recipe]; what doesn't read is skipped, never the whole. */
    fun parse(json: String): Recipe {
        val o = runCatching { ShepherdrJson.parseToJsonElement(json) as? JsonObject }.getOrNull()
            ?: throw java.io.IOException("Its recipe.json isn't a JSON object")
        var n = 0
        fun walk(list: JsonElement?): List<RecipeBlock> = (list as? JsonArray).orEmpty().mapNotNull { e ->
            val b = e as? JsonObject ?: return@mapNotNull null
            block(b, (b["id"] as? JsonPrimitive)?.contentOrNull ?: "b${++n}", ::walk)
        }
        return Recipe(walk(o["blocks"]))
    }

    private fun str(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun words(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.contentOrNull
    private fun list(o: JsonObject?, k: String) = (o?.get(k) as? JsonArray).orEmpty()

    /** A cell, a stat's value: words, or a number as it was written (`92`, not `92.0`). */
    private fun cell(e: JsonElement?): String = when (e) {
        is JsonPrimitive -> e.contentOrNull.orEmpty()
        null -> ""
        else -> e.toString()
    }

    private fun image(o: JsonObject?, id: String) = RecipeBlock.Image(
        id, str(o, "src").orEmpty(), str(o, "caption"), (o?.get("w") as? JsonPrimitive)?.intOrNull, (o?.get("h") as? JsonPrimitive)?.intOrNull,
    )

    private fun block(b: JsonObject, id: String, walk: (JsonElement?) -> List<RecipeBlock>): RecipeBlock = when (val type = str(b, "type").orEmpty()) {
        "heading" -> RecipeBlock.Heading(id, str(b, "text").orEmpty(), ((b["level"] as? JsonPrimitive)?.intOrNull ?: 2).coerceIn(1, 3))
        "text" -> RecipeBlock.Text(id, str(b, "md").orEmpty())
        "list" -> RecipeBlock.Bullets(id, list(b, "items").map(::cell), (b["ordered"] as? JsonPrimitive)?.booleanOrNull == true)
        "table" -> RecipeBlock.Table(
            id,
            list(b, "columns").map { c -> if (c is JsonObject) RecipeBlock.Table.Column(str(c, "label").orEmpty(), str(c, "align")) else RecipeBlock.Table.Column(cell(c)) },
            list(b, "rows").map { r -> (r as? JsonArray).orEmpty().map(::cell) },
        )
        "chart" -> RecipeBlock.Chart(
            id, str(b, "kind") ?: "bar", list(b, "labels").map(::cell),
            list(b, "series").mapNotNull { s -> (s as? JsonObject)?.let { RecipeBlock.Chart.Series(str(it, "name").orEmpty(), list(it, "values").mapNotNull { v -> (v as? JsonPrimitive)?.doubleOrNull }) } },
        )
        "stats" -> RecipeBlock.Stats(id, list(b, "items").mapNotNull { x -> (x as? JsonObject)?.let { RecipeBlock.Stats.Stat(words(it, "label").orEmpty(), cell(it["value"]), str(it, "note")) } })
        "image" -> image(b, id)
        "pair" -> RecipeBlock.Pair(id, image(b["before"] as? JsonObject, "$id.before"), image(b["after"] as? JsonObject, "$id.after"))
        "code" -> RecipeBlock.Code(id, str(b, "text").orEmpty(), str(b, "lang"))
        "callout" -> RecipeBlock.Callout(id, str(b, "tone")?.takeIf { it in TONES } ?: "info", str(b, "title"), str(b, "md"))
        "columns" -> RecipeBlock.Columns(id, list(b, "columns").map { walk(it) })
        "tabs" -> RecipeBlock.Tabs(id, list(b, "tabs").mapNotNull { t -> (t as? JsonObject)?.let { RecipeBlock.Tabs.Tab(str(it, "label").orEmpty(), walk(it["blocks"])) } })
        "details" -> RecipeBlock.Details(id, str(b, "summary").orEmpty(), walk(b["blocks"]))
        "divider" -> RecipeBlock.Divider(id)
        "choice" -> RecipeBlock.Choice(id, str(b, "question").orEmpty(), list(b, "options").mapNotNull { x -> (x as? JsonObject)?.let { RecipeBlock.Choice.Option(str(it, "label").orEmpty(), str(it, "description")) } })
        "form" -> RecipeBlock.Form(
            id, str(b, "title"),
            list(b, "fields").mapNotNull { f -> (f as? JsonObject)?.let { RecipeBlock.Form.Field(str(it, "name").orEmpty(), str(it, "label"), str(it, "kind")?.takeIf { k -> k in FIELDS } ?: "text", list(it, "options").map(::cell)) } },
            str(b, "submit"),
        )
        "button" -> RecipeBlock.Button(id, str(b, "label").orEmpty(), str(b, "reply").orEmpty())
        else -> RecipeBlock.Unknown(id, type)
    }

    val TONES = listOf("info", "good", "warn", "bad")
    val FIELDS = listOf("text", "textarea", "select", "checkbox")

    /** A block's words for a note, so its agent knows what was pointed at ("the table “Coverage”"). */
    fun blockName(b: RecipeBlock): String {
        val (what, name) = when (b) {
            is RecipeBlock.Heading -> "heading" to b.text
            is RecipeBlock.Text -> "text" to null
            is RecipeBlock.Bullets -> "list" to null
            is RecipeBlock.Table -> "table" to null
            is RecipeBlock.Chart -> "chart" to null
            is RecipeBlock.Stats -> "numbers" to null
            is RecipeBlock.Image -> "picture" to b.caption
            is RecipeBlock.Pair -> "before and after" to null
            is RecipeBlock.Code -> "code" to b.text
            is RecipeBlock.Callout -> "callout" to b.title
            is RecipeBlock.Columns -> "columns" to null
            is RecipeBlock.Tabs -> "tabs" to null
            is RecipeBlock.Details -> "folded section" to b.summary
            is RecipeBlock.Divider -> "line" to null
            is RecipeBlock.Choice -> "choice" to b.question
            is RecipeBlock.Form -> "form" to b.title
            is RecipeBlock.Button -> "button" to b.label
            is RecipeBlock.Unknown -> "block" to null
        }
        return if (name != null) "the $what “${name.take(60)}”" else "the $what ${b.id}".trim()
    }

    /** What a choice sends its agent: plain words, as the person would write them. */
    fun choiceReply(b: RecipeBlock.Choice, label: String) = "${b.question}: $label"

    /** What a form sends its agent: its title, then a line per field. */
    fun formReply(b: RecipeBlock.Form, values: Map<String, Any?>): String {
        val lines = b.fields.map { f ->
            val v = values[f.name]
            val said = if (f.kind == "checkbox") (if (v == true) "yes" else "no") else v?.toString()?.trim().orEmpty().ifEmpty { "(empty)" }
            "${f.label ?: f.name}: $said"
        }
        return (listOf(b.title ?: b.submit ?: "Form") + lines).joinToString("\n")
    }

    /** A bar chart's bars: each one's series, its place along the chart ([i]), label, value and height as 0–1 of the tallest. */
    data class Bar(val series: Int, val i: Int, val label: String, val value: Double, val h: Double)

    fun bars(c: RecipeBlock.Chart): List<Bar> {
        val max = maxOf(1e-9, c.series.flatMap { it.values }.maxOfOrNull { maxOf(0.0, it) } ?: 0.0)
        return c.series.flatMapIndexed { si, s -> s.values.mapIndexed { i, v -> Bar(si, i, c.labels.getOrNull(i).orEmpty(), v, maxOf(0.0, v) / max) } }
    }

    /** A pie's slices: each one's label, value and share of the whole. */
    fun slices(c: RecipeBlock.Chart): List<Triple<String, Double, Double>> {
        val vals = c.series.firstOrNull()?.values.orEmpty().map { maxOf(0.0, it) }
        val sum = vals.sum().takeIf { it > 0 } ?: 1.0
        return vals.mapIndexed { i, v -> Triple(c.labels.getOrNull(i).orEmpty(), v, v / sum) }
    }

    /** The pictures a recipe names, for reading them before it shows. */
    fun pictures(blocks: List<RecipeBlock>): List<String> = blocks.flatMap { b ->
        when (b) {
            is RecipeBlock.Image -> listOf(b.src)
            is RecipeBlock.Pair -> listOf(b.before.src, b.after.src)
            is RecipeBlock.Columns -> b.columns.flatMap(::pictures)
            is RecipeBlock.Tabs -> b.tabs.flatMap { pictures(it.blocks) }
            is RecipeBlock.Details -> pictures(b.blocks)
            else -> emptyList()
        }
    }.filter { it.isNotEmpty() && !it.contains("..") && !it.startsWith("/") }.distinct()
}
