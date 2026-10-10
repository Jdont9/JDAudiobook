package fr.jd.audiobooks

import org.junit.Assert.*
import org.junit.Test

class HelpersTest {
    @Test fun naturalOrderComparesNumbersByValue() {
        assertTrue(naturalCompare("2", "10") < 0)
        assertTrue(naturalCompare("file 9", "file 10") < 0)
        assertEquals(0, naturalCompare("abc", "abc"))
        assertTrue(naturalCompare("01", "1") == 0)
    }

    @Test fun fileOrderIgnoresExtension() {
        // « Complet.opus » doit passer avant « Complet 2.opus »
        assertTrue(fileOrder("Complet.opus", "Complet 2.opus") < 0)
        assertTrue(fileOrder("Book 2.mp3", "Book 10.mp3") < 0)
    }

    @Test fun shortNamesStripsCommonPrefixWithoutCuttingNumbers() {
        val (prefix, shorts) = shortNames(listOf("Book 01.mp3", "Book 02.mp3"))
        assertEquals("Book ", prefix)
        assertEquals(listOf("01.mp3", "02.mp3"), shorts)
        val single = shortNames(listOf("Only.mp3"))
        assertEquals("", single.first)
    }

    @Test fun foldRemovesAccentsAndCase() {
        assertEquals("elephant", fold("Éléphant"))
    }

    @Test fun coverScoreRejectsOtherVolume() {
        assertEquals(1.0, CoverFetch.score("Harry Potter 2", "Harry Potter 2 - La Chambre des secrets"), 0.0001)
        assertTrue(CoverFetch.score("Harry Potter 2", "Harry Potter 3") < 0.5)
        assertEquals(0.0, CoverFetch.score("", "Anything"), 0.0)
    }

    @Test fun sabpParserRejectsGarbage() {
        assertNull(SabpImport.parsePosition(byteArrayOf(1, 2, 3)))
        assertNull(SabpImport.parsePosition(ByteArray(0)))
    }

    // ---- Robustesse face aux données lues dans des fichiers modifiables ou corrompus ----

    @Test fun safeSpeedFallsBackToOne() {
        assertEquals(1f, safeSpeed(Float.NaN), 0f)
        assertEquals(1f, safeSpeed(0f), 0f)
        assertEquals(1f, safeSpeed(-2f), 0f)
        assertEquals(1f, safeSpeed(Float.POSITIVE_INFINITY), 0f)
        assertEquals(1f, safeSpeed(50f), 0f)
        assertEquals(1.5f, safeSpeed(1.5f), 0f)
    }

    @Test fun parseSavedNeverThrows() {
        assertNull(parseSaved(null))
        assertNull(parseSaved(""))
        assertNull(parseSaved("abc|def"))
        assertNull(parseSaved("3"))
        assertEquals(Saved(2, 5000L, 1f, 7L), parseSaved("2|5000|0.0|7"))      // vitesse 0 -> 1
        assertEquals(Saved(0, 0L, 1f, 0L), parseSaved("-4|-9|NaN"))            // négatifs ramenés à 0, NaN -> 1
        assertEquals(Saved(1, 10L, 1.25f, 99L), parseSaved("1|10|1.25|99"))
    }

    @Test fun progressFileIsSanitized() {
        val bad = """{"index":-3,"file":"a.mp3","pos":-5,"speed":0,"finished":true,"updated":99999999999999}"""
        val d = ProgressFile.parse(bad.toByteArray(), now = 1_000L)!!
        assertEquals(0, d.index)
        assertEquals(0L, d.pos)
        assertEquals(1f, d.speed, 0f)
        assertTrue(d.finished)
        assertEquals(1_000L + 24L * 3600 * 1000, d.updated) // pas plus d'un jour dans le futur
        val neg = ProgressFile.parse("""{"index":1,"pos":2,"speed":-1}""".toByteArray())!!
        assertEquals(1f, neg.speed, 0f)
        val ok = ProgressFile.parse("""{"index":1,"pos":2,"speed":1.75,"updated":5}""".toByteArray(), now = 10L)!!
        assertEquals(1.75f, ok.speed, 0f)
        assertEquals(5L, ok.updated)
        assertNull(ProgressFile.parse("pas du json".toByteArray()))
        assertNull(ProgressFile.parse("""{"pos":2}""".toByteArray())) // « index » manquant
    }

    @Test fun monthStartGroupsDays() {
        assertEquals("20240301", monthStart("20240315"))
        assertEquals("20240301", monthStart("20240301"))
        assertEquals("bizarre", monthStart("bizarre"))
    }

    @Test fun readCappedStopsAtLimit() {
        assertArrayEquals(ByteArray(10) { 1 }, ByteArray(10) { 1 }.inputStream().readCapped(10))
        assertNull(ByteArray(11) { 1 }.inputStream().readCapped(10))
    }
}

