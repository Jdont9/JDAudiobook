package fr.jd.audiobooks

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

class SafeHttpTest {
    private fun blocked(ip: String) = SafeHttp.isBlocked(InetAddress.getByName(ip))

    @Test fun privateAndLocalAddressesAreBlocked() {
        listOf("127.0.0.1", "10.1.2.3", "172.16.0.5", "192.168.1.1", "169.254.169.254", "100.64.0.1", "0.0.0.0", "224.0.0.1", "::1", "fe80::1", "fd12:3456::1")
            .forEach { assertTrue("$it devrait être refusée", blocked(it)) }
    }

    @Test fun publicAddressesAreAllowed() {
        listOf("8.8.8.8", "93.184.216.34", "2606:4700:4700::1111").forEach { assertFalse("$it devrait passer", blocked(it)) }
    }

    @Test fun onlyHttpsWithoutCredentialsIsAllowed() {
        assertFalse(SafeHttp.allowed(URL("http://8.8.8.8/x")))
        assertFalse(SafeHttp.allowed(URL("https://user:pw@8.8.8.8/x")))
        assertFalse(SafeHttp.allowed(URL("https://127.0.0.1/x")))
        assertFalse(SafeHttp.allowed(URL("https://192.168.0.10/x")))
        assertTrue(SafeHttp.allowed(URL("https://8.8.8.8/x")))
    }
}

class CoverNameTest {
    @Test fun nameIs128BitHexAndDependsOnSaltAndPath() {
        val a = Covers.artName("sel", "Auteur/Livre 1")
        assertTrue(Regex("[0-9a-f]{32}\\.jpg").matches(a))
        assertEquals(a, Covers.artName("sel", "Auteur/Livre 1"))
        assertNotEquals(a, Covers.artName("sel", "Auteur/Livre 2"))
        assertNotEquals(a, Covers.artName("autre", "Auteur/Livre 1"))
    }
}

class BackupTest {
    private val pw = "correct horse battery".toCharArray()
    // Peu d'itérations : on teste la logique, pas la lenteur de PBKDF2.
    private val fast = Backup.MIN_ITERATIONS

    private val snapshot = mapOf(
        "p" to mapOf<String, Any?>(
            "root" to "content://secret", "fin_a/b" to true, "boost_a/b" to 6, "skip_s" to 45, "f_auto_next" to true,
            "bm_a/b" to """[{"f":"01.mp3","p":1000,"t":5,"n":"idée"}]""", "inconnu" to "x", "cnf_a/b" to 1L
        ),
        "pos" to mapOf<String, Any?>("s_a/b" to "2|5000|1.25|99", "last_book" to "a/b"),
        "stats" to mapOf<String, Any?>("t|a/b|20260101" to "1000,2000")
    )

    @Test fun roundTrip() {
        val enc = Backup.encrypt(Backup.toJson(snapshot), pw, fast)
        val r = Backup.parse(Backup.decrypt(enc, pw)!!)!!
        assertEquals(true, r.p["fin_a/b"]); assertEquals(6, r.p["boost_a/b"]); assertEquals(45, r.p["skip_s"])
        assertEquals("2|5000|1.25|99", r.pos["s_a/b"]); assertEquals("1000,2000", r.stats["t|a/b|20260101"])
    }

    @Test fun unknownAndSensitiveKeysAreNeverExported() {
        val r = Backup.parse(Backup.toJson(snapshot))!!
        assertFalse(r.p.containsKey("root")); assertFalse(r.p.containsKey("inconnu")); assertFalse(r.p.containsKey("cnf_a/b"))
    }

    @Test fun wrongPasswordAndTamperingFail() {
        val enc = Backup.encrypt("secret".toByteArray(), pw, fast)
        assertNull(Backup.decrypt(enc, "mauvais mot de passe".toCharArray()))
        for (i in listOf(0, 5, 20, 40, enc.size - 1)) {
            val bad = enc.copyOf(); bad[i] = (bad[i].toInt() xor 1).toByte()
            assertNull("octet $i modifié", Backup.decrypt(bad, pw))
        }
        assertNull(Backup.decrypt(enc.copyOf(10), pw))
    }

    @Test fun forgedIterationCountIsRejectedBeforeDerivingTheKey() {
        val enc = Backup.encrypt("x".toByteArray(), pw, fast)
        val forged = enc.copyOf()
        java.nio.ByteBuffer.wrap(forged).putInt(4, Int.MAX_VALUE) // énorme : aurait bloqué le téléphone
        assertNull(Backup.decrypt(forged, pw))
        java.nio.ByteBuffer.wrap(forged).putInt(4, 1)              // trop faible
        assertNull(Backup.decrypt(forged, pw))
    }

    @Test fun parseIgnoresInvalidAndOutOfRangeEntries() {
        val json = """{"format":1,"p":{"boost_x":"i:999","skip_s":"i:1","fin_x":"b:maybe","f_ok":"b:true","bm_x":"s:pas du json","f_bad":"s:true","zzz":"s:1"},
            "pos":{"s_x":"s:n'importe quoi","s_y":"s:0|10|1.0|5"},"stats":{"t|x|20260101":"s:12,34","t|x|20260102":"s:abc"}}"""
        val r = Backup.parse(json.toByteArray())!!
        assertEquals(24, r.p["boost_x"]); assertEquals(5, r.p["skip_s"])
        assertFalse(r.p.containsKey("fin_x")); assertEquals(true, r.p["f_ok"]); assertFalse(r.p.containsKey("f_bad")); assertFalse(r.p.containsKey("zzz"))
        assertEquals("[]", r.p["bm_x"])
        assertFalse(r.pos.containsKey("s_x")); assertTrue(r.pos.containsKey("s_y"))
        assertTrue(r.stats.containsKey("t|x|20260101")); assertFalse(r.stats.containsKey("t|x|20260102"))
    }

    @Test fun notABackupIsRejected() {
        assertNull(Backup.parse("pas du json".toByteArray()))
        assertNull(Backup.parse("""{"format":2}""".toByteArray()))
    }
}

class StatsMathTest {
    private val f = SimpleDateFormat("yyyyMMdd", Locale.US)
    private fun d(s: String) = f.parse(s)!!

    @Test fun currentStreakCountsBackFromTodayOrYesterday() {
        val days = setOf("20260110", "20260111", "20260112")
        assertEquals(3, StatsMath.currentStreak(days, d("20260112")))
        assertEquals(3, StatsMath.currentStreak(days, d("20260113"))) // rien aujourd'hui : la série d'hier compte encore
        assertEquals(0, StatsMath.currentStreak(days, d("20260115")))
        assertEquals(0, StatsMath.currentStreak(emptySet(), d("20260115")))
    }

    @Test fun longestStreak() {
        assertEquals(3, StatsMath.longestStreak(setOf("20260110", "20260111", "20260112", "20260120", "20260121")))
        assertEquals(2, StatsMath.longestStreak(setOf("20260228", "20260301"))) // passage de mois
        assertEquals(0, StatsMath.longestStreak(emptySet()))
    }

    @Test fun levels() {
        assertEquals(0, StatsMath.level(0, 100)); assertEquals(1, StatsMath.level(1, 100)); assertEquals(4, StatsMath.level(100, 100))
    }

    @Test fun csvEscapesAndNeutralizesFormulas() {
        val csv = StatsMath.csv(listOf(Stat("=HYPERLINK(\"x\")", "20260101", 61_000, 122_000), Stat("A, B", "20260102", 1000, 1000)))
        assertTrue(csv.startsWith("book,day,listened_seconds,content_seconds\n"))
        assertTrue(csv.contains("\"'=HYPERLINK(\"\"x\"\")\",20260101,61,122"))
        assertTrue(csv.contains("\"A, B\",20260102,1,1"))
    }
}

class SearchBooksTest {
    private fun b(path: String) = Book(path, path.substringAfterLast('/'), emptyList(), emptyList())
    private val lib = listOf(b("Millenium/Millenium 1 - Les hommes qui n'aimaient pas les femmes"), b("Millenium/Millenium 2 - La fille qui rêvait"), b("Zola/Germinal"), b("Hugo/Les Misérables"))

    @Test fun ignoresAccentsAndCase() = assertEquals("Zola/Germinal", SearchBooks.find(lib, "GERMINAL").first().path)
    @Test fun allWordsMustMatchFirst() {
        assertEquals("Millenium/Millenium 2 - La fille qui rêvait", SearchBooks.find(lib, "millenium revait").first().path)
        assertEquals("Hugo/Les Misérables", SearchBooks.find(lib, "miserables").first().path)
    }
    @Test fun emptyOrNoiseQueryGivesNothing() { assertTrue(SearchBooks.find(lib, "").isEmpty()); assertTrue(SearchBooks.find(lib, "xyzzy").isEmpty()) }
}

class BookmarkExportTest {
    @Test fun markdownHasTitleAndOneLinePerBookmark() {
        val md = BookmarkExport.markdown("Mon livre", listOf(Bookmark("01.mp3", 65_000, 0, "belle phrase"), Bookmark("02.mp3", 5_000, 0))) { "t${it / 1000}" }
        assertEquals("# Mon livre\n\n- t65 — belle phrase (01.mp3)\n- t5 — 02.mp3\n", md)
    }

    @Test fun newlinesInNotesCannotBreakTheList() {
        val md = BookmarkExport.markdown("L", listOf(Bookmark("f", 0, 0, "a\n- faux signet"))) { "t" }
        assertEquals(1, md.lines().count { it.startsWith("- ") })
    }
}

class CertFingerprintTest {
    @Test fun formatsSha256AsColonSeparatedUppercaseHex() {
        val fp = Updater.fingerprint(byteArrayOf(1, 2, 3))
        assertEquals(32, fp.split(":").size)
        assertTrue(fp.matches(Regex("([0-9A-F]{2}:){31}[0-9A-F]{2}")))
    }
}
