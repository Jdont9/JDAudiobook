package fr.jd.audiobooks

import org.junit.Assert.*
import org.junit.Test

class ShakeLogicTest {
    private val t0 = 1_000_000L

    @Test fun threeJoltsInWindowIsAShake() {
        val s = ShakeLogic()
        assertFalse(s.onSample(3f, t0))
        assertFalse(s.onSample(3f, t0 + 300))
        assertTrue(s.onSample(3f, t0 + 600))
    }

    @Test fun gentleMovementNeverTriggers() {
        val s = ShakeLogic()
        for (i in 0 until 200) assertFalse(s.onSample(1.6f, t0 + i * 20L)) // marche, retournement dans le lit
    }

    @Test fun oneLongPeakCountsOnce() {
        val s = ShakeLogic()
        // un seul coup : plusieurs mesures rapprochées au-dessus du seuil
        for (i in 0 until 5) assertFalse(s.onSample(3f, t0 + i * 20L))
    }

    @Test fun slowJoltsDoNotAdd() {
        val s = ShakeLogic()
        assertFalse(s.onSample(3f, t0))
        assertFalse(s.onSample(3f, t0 + 1000))
        assertFalse(s.onSample(3f, t0 + 2100)) // le premier coup est sorti de la fenêtre
    }

    @Test fun canShakeAgainAfterwards() {
        val s = ShakeLogic()
        s.onSample(3f, t0); s.onSample(3f, t0 + 300)
        assertTrue(s.onSample(3f, t0 + 600))
        assertFalse(s.onSample(3f, t0 + 900))
        assertFalse(s.onSample(3f, t0 + 1200))
        assertTrue(s.onSample(3f, t0 + 1500))
    }
}

class BookmarkCodecTest {
    @Test fun roundTripKeepsNotes() {
        val l = listOf(Bookmark("a.mp3", 1000, 5, "Belle citation"), Bookmark("b.mp3", 0, 6))
        assertEquals(l, BookmarkCodec.decode(BookmarkCodec.encode(l)))
    }

    @Test fun oldBookmarksWithoutNoteStillLoad() {
        val l = BookmarkCodec.decode("""[{"f":"a.mp3","p":42,"t":7}]""")
        assertEquals(listOf(Bookmark("a.mp3", 42, 7, "")), l)
    }

    @Test fun emptyNoteIsNotWritten() {
        assertFalse(BookmarkCodec.encode(listOf(Bookmark("a", 1, 2))).contains("\"n\""))
    }

    @Test fun garbageGivesEmptyList() {
        assertTrue(BookmarkCodec.decode("pas du json").isEmpty())
        assertTrue(BookmarkCodec.decode(null).isEmpty())
    }

    @Test fun overlongNoteIsCut() {
        val long = "x".repeat(500)
        val l = BookmarkCodec.decode(BookmarkCodec.encode(listOf(Bookmark("a", 1, 2, long))))
        assertEquals(BookmarkCodec.MAX_NOTE, l[0].note.length)
    }
}
