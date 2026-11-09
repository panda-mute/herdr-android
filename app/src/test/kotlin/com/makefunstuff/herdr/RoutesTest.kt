package com.makefunstuff.herdr

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Every screen in [Routes] is one something opens. A screen nothing navigates to any more is lost to the people using
 * the app while it still builds and its own tests pass: HERD-12 took the last ways into the terminal out with the
 * workspace tools, and nothing noticed. Read from the sources, as a route is only text.
 */
class RoutesTest {
    @Test fun everyRouteIsOpenedFromSomewhere() {
        val main = File("src/main/kotlin")
        val sources = main.walkTopDown().filter { it.isFile && it.extension == "kt" }.associate { it.relativeTo(main).path to it.readText() }
        val nav = sources.entries.single { it.key.endsWith("/AppNav.kt") }.value
        assertEquals("Routes nothing opens", emptyList<String>(), RouteCheck.orphans(nav, sources.values))
    }

    @Test fun everyRouteHasItsScreen() {
        val nav = File("src/main/kotlin/com/makefunstuff/herdr/AppNav.kt").readText()
        val shown = Regex("""composable\(Routes\.(\w+)\)""").findAll(nav).map { it.groupValues[1] }.toSet()
        assertEquals("Routes no screen draws", emptyList<String>(), RouteCheck.routes(nav).map { it.name }.filter { it !in shown })
    }

    @Test fun aRouteNothingOpensIsFound() {
        val nav = """
            object Routes {
                const val TASKS = "tasks"
                const val QUIET = "settings/quiet"
                const val TERMINAL = "terminal/{mid}/{pane}"
                const val AGENT = "agent/{mid}/{pane}"
                const val CODE_AT = "code/{mid}/at?${'$'}{CodeSpot.ARGS}&tab={tab}"
                fun terminal(mid: String, pane: String) = "terminal/${'$'}mid/${'$'}{Uri.encode(pane)}"
                fun agent(mid: String, pane: String) = "agent/${'$'}mid/${'$'}{Uri.encode(pane)}"
                fun codeAt(spot: CodeSpot, tab: CheckoutTab? = null) = "code/${'$'}{spot.machine}/at?${'$'}{spot.query()}"
            }

            fun NavGraphBuilder.screens() {
                composable(Routes.TERMINAL) { }
                composable(Routes.QUIET) { }
            }
        """.trimIndent()
        val other = """
            fun a(nav: NavController) = nav.navigate(Routes.agent(mid, pane))
            fun b() { if (route == Routes.QUIET) Unit; Links.open(Routes.TASKS); val r = route in setOf(Routes.AGENT, Routes.TERMINAL) }
            fun c() = panes.open(nav, Routes.codeAt(spot))
        """.trimIndent()
        // The terminal's route only drawn and compared, quiet hours only compared: neither opens.
        assertEquals(listOf("QUIET", "TERMINAL"), RouteCheck.orphans(nav, listOf(nav, other)))
    }
}

/** Finds [Routes] entries nothing in the app's sources opens. */
object RouteCheck {
    data class Route(val name: String, val pattern: String)

    /** The `const val`s of `object Routes`, by their name and route pattern. */
    fun routes(nav: String): List<Route> =
        Regex("""const val (\w+) = "([^"]*)"""").findAll(routesObject(nav)).map { Route(it.groupValues[1], it.groupValues[2]) }.toList()

    /**
     * Routes nothing opens. A route with a builder (`fun terminal(mid, pane)`, its literal the pattern's shape) is
     * opened where the builder is called; one without, where its constant is handed to a navigating call
     * (`navigate`, `goTop`, `go`, `open`, `Start`, the start destination), not where a route is only compared or drawn.
     */
    fun orphans(nav: String, sources: Collection<String>): List<String> {
        val routes = routesObject(nav)
        val builders = Regex("""fun (\w+)\([^)]*\)[^=]*=[^"]*"([^"]*)"""").findAll(routes).map { it.groupValues[1] to shape(it.groupValues[2], builder = true) }.toList()
        // Everything but the Routes object itself.
        val code = sources.map { if (it === nav || it == nav) it.replace(routes, "") else it }.joinToString("\n")
        return routes(nav).filter { r ->
            val builder = builders.firstOrNull { it.second == shape(r.pattern, builder = false) }?.first
            if (builder != null) !Regex("""\bRoutes\.$builder\(""").containsMatchIn(code)
            else !Regex("""(\b(navigate|goTop|go|open|Start)\(\s*(nav,\s*)?|startDestination\s*=\s*)Routes\.${r.name}\b""").containsMatchIn(code)
        }.map { it.name }.sorted()
    }

    /** `terminal/{mid}/{pane}` and `"terminal/$mid/${Uri.encode(pane)}"` alike: `terminal/ * / *`, its query left out. */
    private fun shape(route: String, builder: Boolean): String {
        val path = route.substringBefore('?')
        return if (builder) path.replace(Regex("""\$\{[^}]*\}"""), "*").replace(Regex("""\$\w+"""), "*")
        else path.replace(Regex("""\{[^}]*\}"""), "*")
    }

    private fun routesObject(nav: String): String {
        val start = nav.indexOf("object Routes {")
        require(start >= 0) { "No object Routes" }
        // Its closing brace is the first one at the start of a line.
        val end = nav.indexOf("\n}", start)
        return nav.substring(start, if (end < 0) nav.length else end)
    }
}
