package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.api.ApiClient
import com.makefunstuff.herdr.core.api.CopyMotion
import com.makefunstuff.herdr.core.api.HerdrApi
import com.makefunstuff.herdr.core.api.HerdrApiException
import com.makefunstuff.herdr.core.api.ScrollbackSearch
import com.makefunstuff.herdr.core.api.TextPoint
import com.makefunstuff.herdr.core.ssh.Duplex
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.InputStream
import java.io.OutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Scrollback, search, selection, links and layouts against a real herdr. Runs only when
 * HERDR_LIVE_SOCKET points at a herdr API socket on this machine; use a scratch session
 * (`herdr --session scratch server`), as it creates and closes a workspace there.
 */
class LiveTerminalExtrasTest {
    private val socket = System.getenv("HERDR_LIVE_SOCKET")

    private fun open(): Duplex {
        val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
        channel.connect(UnixDomainSocketAddress.of(socket))
        return object : Duplex {
            override val input: InputStream = Channels.newInputStream(channel)
            override val output: OutputStream = Channels.newOutputStream(channel)
            override fun close() = channel.close()
        }
    }

    @Test
    fun scrollbackSearchSelectionAndLinks() {
        if (socket == null) return
        runBlocking {
            val client = ApiClient { open() }
            val api = HerdrApi(client)
            // Focused: link.resolve only works while the pane is visible on the host.
            val created = client.call("workspace.create", buildJsonObject {
                put("label", JsonPrimitive("live-extras"))
                put("focus", JsonPrimitive(true))
            })
            val workspaceId = (created["workspace"] as kotlinx.serialization.json.JsonObject)["workspace_id"]!!.toString().trim('"')
            val tabId = (created["tab"] as kotlinx.serialization.json.JsonObject)["tab_id"]!!.toString().trim('"')
            val paneId = (created["root_pane"] as kotlinx.serialization.json.JsonObject)["pane_id"]!!.toString().trim('"')
            try {
                api.sendText(paneId, "for i in \$(seq 1 150); do echo row \$i; done; echo visit https://example.com/x here\r")
                withTimeout(10_000) {
                    while (!api.readPane(paneId, "recent", 5).text.contains("visit https")) delay(100)
                }

                // Scroll back and forward again.
                val scrolled = api.scrollPane(paneId, 40).scroll!!
                assertEquals(40, scrolled.offsetFromBottom)
                assertTrue(scrolled.maxOffsetFromBottom > 100, "max ${scrolled.maxOffsetFromBottom}")
                assertEquals(0, api.scrollPane(paneId, 0).scroll!!.offsetFromBottom)

                // Search from the bottom finds the newest match; stepping goes to older ones.
                val search = ScrollbackSearch(api, paneId)
                val first = search.find("row 1")
                val firstRow = first.currentMatch!!.start.row
                assertTrue(first.total >= 10, "total ${first.total}")
                val second = search.find("row 1")
                assertTrue(second.currentMatch!!.start.row < firstRow, "${second.currentMatch} after row $firstRow")

                // Select that match's line and read it back.
                val rev = api.contentRevision(paneId)
                val match = first.currentMatch!!
                val end = api.copyMotion(paneId, match.start, CopyMotion.LineEnd, rev).cursor
                val text = api.readSelection(paneId, match.start, end, rev)
                assertTrue(text.startsWith("row 1"), text)

                // The URL on the screen resolves and activates to its address.
                val screen = api.scrollPane(paneId, 0).scroll!!
                val visible = api.readPane(paneId, "visible").text.lines()
                val urlRow = visible.indexOfFirst { it.contains("https://example.com/x") }
                val col = visible[urlRow].indexOf("https") + 2
                assertTrue(urlRow in 0 until screen.viewportRows, "url row $urlRow")
                assertTrue(api.resolveLink(paneId, urlRow, col).isNotEmpty())
                val link = api.activateLink(paneId, urlRow, col)
                assertEquals("https://example.com/x", link.url)

                println("live extras ok: $first")
            } finally {
                client.call("workspace.close", buildJsonObject { put("workspace_id", JsonPrimitive(workspaceId)) })
            }
        }
    }

    @Test
    fun staleRevisionsAreRefused() {
        if (socket == null) return
        runBlocking {
            val api = HerdrApi(ApiClient { open() })
            val pane = api.snapshot().panes.firstOrNull() ?: return@runBlocking
            val e = assertFailsWith<HerdrApiException> {
                api.copySearch(pane.paneId, "x", backward = true, cursor = TextPoint(0, 0), contentRevision = Long.MAX_VALUE / 2)
            }
            assertEquals("stale_content", e.code)
        }
    }
}
