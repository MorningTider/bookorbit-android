package com.bookorbit.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderBridgeEventsTest {
    @Test
    fun `parses a tap`() {
        val e = ReaderBridge.parseEvent("""{"type":"tap","x":0.82}""")
        assertEquals(ReaderEvent.Tap(0.82), e)
    }

    @Test
    fun `parses a selection with its viewport rect`() {
        val e = ReaderBridge.parseEvent(
            """{"type":"selection","text":"undertaker","cfi":"epubcfi(/6/10!/4/2)","rect":{"left":1.5,"top":2.0,"right":30.0,"bottom":18.0}}""",
        ) as ReaderEvent.Selection
        assertEquals("undertaker", e.text)
        assertEquals("epubcfi(/6/10!/4/2)", e.cfi)
        assertEquals(ViewportRect(1.5, 2.0, 30.0, 18.0), e.rect)
    }

    @Test
    fun `selection without a cfi still parses so the host can ignore it`() {
        val e = ReaderBridge.parseEvent("""{"type":"selection","text":"x","cfi":null,"rect":null}""") as ReaderEvent.Selection
        assertNull(e.cfi)
        assertNull(e.rect)
    }

    @Test
    fun `parses selection cleared and annotation tap`() {
        assertTrue(ReaderBridge.parseEvent("""{"type":"selectionCleared"}""") === ReaderEvent.SelectionCleared)
        val tap = ReaderBridge.parseEvent("""{"type":"annotationTap","cfi":"epubcfi(/6/2)","rect":null}""") as ReaderEvent.AnnotationTap
        assertEquals("epubcfi(/6/2)", tap.cfi)
        assertNull(tap.rect)
    }

    @Test
    fun `a malformed rect is dropped rather than failing the event`() {
        val e = ReaderBridge.parseEvent("""{"type":"selection","text":"x","cfi":"c","rect":{"left":1}}""") as ReaderEvent.Selection
        assertNull(e.rect)
    }

    @Test
    fun `parses a batch of search results`() {
        val e = ReaderBridge.parseEvent(
            """{"type":"searchResults","label":"Chapter 2","items":[{"cfi":"epubcfi(/6/4)","pre":"the ","match":"beadle","post":" said"},{"pre":"no cfi"}]}""",
        ) as ReaderEvent.SearchResults
        assertEquals("Chapter 2", e.label)
        // The item without a cfi can't be jumped to, so it is dropped.
        assertEquals(listOf(SearchHit("epubcfi(/6/4)", "the ", "beadle", " said")), e.hits)
    }

    @Test
    fun `parses search progress and completion`() {
        assertEquals(ReaderEvent.SearchProgress(0.5), ReaderBridge.parseEvent("""{"type":"searchProgress","progress":0.5}"""))
        assertEquals(ReaderEvent.SearchDone(3, true), ReaderBridge.parseEvent("""{"type":"searchDone","total":3,"capped":true}"""))
        assertEquals(ReaderEvent.SearchDone(0, false), ReaderBridge.parseEvent("""{"type":"searchDone"}"""))
    }
    @Test
    fun `relocation includes the chapter href and automatic reader events`() {
        val event = ReaderBridge.parseEvent("""{"type":"relocate","fraction":0.5,"chapterTitle":"第二章","chapterHref":"chapter2.xhtml"}""") as ReaderEvent.Relocate
        assertEquals("chapter2.xhtml", event.chapterHref)
        assertEquals(ReaderEvent.AutoReadStopped, ReaderBridge.parseEvent("""{"type":"autoReadStopped"}"""))
        assertEquals(ReaderEvent.PageTurnRequested, ReaderBridge.parseEvent("""{"type":"pageTurnRequested"}"""))
    }

    @Test
    fun `legacy settings preserve typography while new controls use defaults`() {
        val settings = ReaderBridge.json.decodeFromString<ReaderSettings>("""{"fontSize":24,"lineHeight":1.9,"themeName":"sepia"}""")
        assertEquals(24, settings.fontSize)
        assertEquals(1.9, settings.lineHeight, 0.0001)
        assertEquals("sepia", settings.themeName)
        assertEquals(false, settings.pageCurl)
        assertEquals(false, settings.volumeKeyPaging)
        assertEquals(15, settings.autoPageIntervalSeconds)
    }

    @Test
    fun `turn completion and page edges are available before another input`() {
        assertEquals(
            ReaderEvent.PageTurnCompleted,
            ReaderBridge.parseEvent("""{"type":"pageTurnCompleted"}"""),
        )
        val event =
            ReaderBridge.parseEvent("""{"type":"relocate","atStart":true,"atEnd":false}""")
                as ReaderEvent.Relocate
        assertTrue(event.atStart)
        assertEquals(false, event.atEnd)
    }

    @Test
    fun `new turn preferences override legacy flags and retain safe defaults`() {
        val legacy =
            ReaderBridge.json.decodeFromString<ReaderSettings>(
                """{"pageCurl":true,"pageAnimation":false}"""
            )
        assertEquals("curl", legacy.turnEffect())
        assertEquals("plain", legacy.backgroundStyle)
        assertEquals("slide", legacy.copy(pageTurnEffect = "slide").turnEffect())
        assertEquals("none", legacy.copy(pageTurnEffect = "none").turnEffect())
        assertEquals("cover", legacy.copy(pageTurnEffect = "cover").turnEffect())
        assertEquals("scrolled", legacy.copy(flow = "scrolled").turnEffect())
        assertEquals("curl", legacy.copy(pageTurnEffect = "unknown").turnEffect())
    }
}
