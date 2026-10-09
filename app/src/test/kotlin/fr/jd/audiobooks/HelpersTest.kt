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
}
