package fr.jd.audiobooks

import org.junit.Assert.*
import org.junit.Test

class ProgressBookmarksTest {
    @Test fun bookmarksSurviveWriteThenRead() {
        val bms = listOf(Bookmark("01.mp3", 12_000, 1, "Belle phrase"), Bookmark("02.mp3", 0, 2))
        val bytes = ProgressFile.encode("Dracula", ProgressFile.Data(1, "02.mp3", 5, 1.25f, false, 10L, bms))
        val d = ProgressFile.parse(bytes, now = 100L)!!
        assertEquals(bms, d.bookmarks)
        assertEquals(1.25f, d.speed, 0.0001f)
    }

    @Test fun oldFileWithoutBookmarksKeyMeansNull() {
        val d = ProgressFile.parse("""{"index":0,"pos":5,"speed":1.0,"updated":3}""".toByteArray(), now = 10L)!!
        assertNull(d.bookmarks) // « absent » : on garde les signets locaux
    }

    @Test fun emptyListIsDifferentFromAbsent() {
        val d = ProgressFile.parse("""{"index":0,"pos":5,"bookmarks":[]}""".toByteArray(), now = 10L)!!
        assertEquals(emptyList<Bookmark>(), d.bookmarks) // « aucun signet » : le fichier fait foi
    }

    @Test fun nullBookmarksAreNotWritten() {
        val bytes = ProgressFile.encode("x", ProgressFile.Data(0, null, 0, 1f, false, 1L))
        assertFalse(String(bytes).contains("bookmarks"))
    }

    @Test fun badEntriesAreSkippedNotTheWholeList() {
        val json = """{"index":0,"pos":0,"bookmarks":[
            {"f":"a.mp3","p":10,"t":1,"n":"ok"},
            {"p":10,"t":1},
            "pas un objet",
            {"f":"b.mp3","p":-50,"t":-1}
        ]}"""
        val l = ProgressFile.parse(json.toByteArray(), now = 10L)!!.bookmarks!!
        assertEquals(2, l.size)
        assertEquals("ok", l[0].note)
        assertEquals(0L, l[1].pos)     // valeurs négatives ramenées à 0
        assertEquals(0L, l[1].created)
    }

    @Test fun hugeListIsCapped() {
        val items = (0 until 1500).joinToString(",") { """{"f":"a","p":$it,"t":1}""" }
        val l = ProgressFile.parse("""{"index":0,"pos":0,"bookmarks":[$items]}""".toByteArray(), now = 10L)!!.bookmarks!!
        assertEquals(BookmarkCodec.MAX_COUNT, l.size)
    }
}
