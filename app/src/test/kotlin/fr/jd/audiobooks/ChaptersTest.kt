package fr.jd.audiobooks

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

class ChaptersTest {
    private fun box(type: String, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + payload.size).putInt(8 + payload.size).put(type.toByteArray(Charsets.ISO_8859_1)).put(payload).array()

    private fun chpl(vararg chapters: Pair<Long, String>): ByteArray {
        val body = ByteBuffer.allocate(8 + 1 + chapters.sumOf { 8 + 1 + it.second.toByteArray().size })
        body.position(8)                       // version + flags + réservé
        body.put(chapters.size.toByte())
        for ((ms, title) in chapters) {
            body.putLong(ms * 10_000)          // unités de 100 ns
            body.put(title.toByteArray().size.toByte()).put(title.toByteArray())
        }
        return box("chpl", body.array())
    }

    private fun read(bytes: ByteArray): List<Chap> {
        val f = File.createTempFile("chap", ".m4b")
        try {
            f.writeBytes(bytes)
            RandomAccessFile(f, "r").use { return Chapters.readChannel(it.channel) }
        } finally { f.delete() }
    }

    @Test fun readsNeroChapters() {
        val file = box("ftyp", ByteArray(4)) + box("moov", box("udta", chpl(0L to "Intro", 60_000L to "Deux")))
        val chaps = read(file)
        assertEquals(2, chaps.size)
        assertEquals(Chap(0L, "Intro"), chaps[0])
        assertEquals(Chap(60_000L, "Deux"), chaps[1])
    }

    @Test fun hugeMoovSizeIsRejectedWithoutAllocating() {
        // moov annonçant ~4 Go dans un fichier minuscule : doit rendre une liste vide, pas planter en mémoire.
        val bytes = ByteBuffer.allocate(16).putInt(-16).put("moov".toByteArray()).putInt(0).putInt(0).array()
        assertTrue(read(bytes).isEmpty())
        // même chose avec la taille sur 64 bits (len == 1)
        val big = ByteBuffer.allocate(32).putInt(1).put("moov".toByteArray()).putLong(Long.MAX_VALUE).array()
        assertTrue(read(big).isEmpty())
    }

    @Test fun oversizedMoovIsSkipped() {
        // Fichier « creux » de 40 Mo dont le moov occupe tout : au-delà du plafond (32 Mo), on ne le charge pas.
        val f = File.createTempFile("chap", ".m4b")
        try {
            RandomAccessFile(f, "rw").use { raf ->
                raf.setLength(40_000_000L)
                raf.seek(0); raf.writeInt(40_000_000); raf.write("moov".toByteArray())
                assertTrue(Chapters.readChannel(raf.channel).isEmpty())
            }
        } finally { f.delete() }
    }

    @Test fun truncatedAndGarbageFilesGiveNoChapters() {
        assertTrue(read(ByteArray(0)).isEmpty())
        assertTrue(read(ByteArray(7) { 1 }).isEmpty())
        val full = box("moov", box("udta", chpl(0L to "Intro", 5_000L to "Suite")))
        assertTrue(read(full.copyOf(full.size - 6)).size <= 2) // tronqué : ne lève rien
        assertTrue(read(ByteArray(200) { (it * 31).toByte() }).isEmpty())
    }

    @Test fun absurdChapterCountIsBounded() {
        // chpl annonçant 255 chapitres alors que la boîte n'en contient aucun
        val body = ByteBuffer.allocate(9).apply { position(8); put(0xFF.toByte()) }.array()
        val file = box("moov", box("udta", box("chpl", body)))
        assertTrue(read(file).isEmpty())
    }

    // ---- MP3 : ID3v2 + frames CHAP ----

    private fun syncsafe(n: Int) = byteArrayOf(((n shr 21) and 0x7f).toByte(), ((n shr 14) and 0x7f).toByte(), ((n shr 7) and 0x7f).toByte(), (n and 0x7f).toByte())
    private fun be32(n: Long) = byteArrayOf((n shr 24).toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
    private fun unsync(b: ByteArray): ByteArray { val o = java.io.ByteArrayOutputStream(); for (x in b) { o.write(x.toInt()); if (x == 0xFF.toByte()) o.write(0) }; return o.toByteArray() }

    private fun frame(id: String, body: ByteArray, v4: Boolean, flags: Int = 0): ByteArray =
        id.toByteArray(Charsets.ISO_8859_1) + (if (v4) syncsafe(body.size) else be32(body.size.toLong())) + byteArrayOf(0, flags.toByte()) + body

    private fun tit2(text: ByteArray, encoding: Int, v4: Boolean) = frame("TIT2", byteArrayOf(encoding.toByte()) + text, v4)

    private fun chap(startMs: Long, subframes: ByteArray, v4: Boolean, unsyncBody: Boolean = false): ByteArray {
        val body = "chp".toByteArray() + 0.toByte() + be32(startMs) + be32(startMs + 1000) + be32(0xFFFFFFFFL) + be32(0xFFFFFFFFL) + subframes
        return frame("CHAP", if (unsyncBody) unsync(body) else body, v4)
    }

    private fun id3(major: Int, frames: ByteArray, flags: Int = 0, padding: Int = 0): ByteArray =
        "ID3".toByteArray() + byteArrayOf(major.toByte(), 0, flags.toByte()) + syncsafe(frames.size + padding) + frames + ByteArray(padding)

    @Test fun readsId3v23ChaptersSortedWithLatin1AndUtf16Titles() {
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Chapitre é".toByteArray(Charsets.UTF_16LE)
        val frames = frame("TALB", byteArrayOf(0) + "Livre".toByteArray(), false) +
            chap(60_000L, tit2(utf16, 1, false), false) +      // volontairement avant le premier
            chap(0L, tit2("Intro".toByteArray(Charsets.ISO_8859_1), 0, false), false)
        val chaps = read(id3(3, frames, padding = 100) + ByteArray(500))
        assertEquals(listOf(Chap(0L, "Intro"), Chap(60_000L, "Chapitre é")), chaps)
    }

    @Test fun readsId3v24Utf8TitleAndMissingTitle() {
        val frames = chap(1_000L, tit2("Été".toByteArray(Charsets.UTF_8), 3, true), true) +
            chap(90_000L, ByteArray(0), true) // pas de TIT2 : titre vide (l'appli met « Chapitre N »)
        assertEquals(listOf(Chap(1_000L, "Été"), Chap(90_000L, "")), read(id3(4, frames)))
    }

    @Test fun unsynchronisedTagIsDecoded() {
        // 255 ms = 00 00 00 FF : l'octet FF reçoit un 00 derrière lui dans une balise « désynchronisée »
        val frames = chap(255L, tit2("A".toByteArray(), 0, false), false, unsyncBody = true)
        assertEquals(listOf(Chap(255L, "A")), read(id3(3, frames, flags = 0x80)))
    }

    @Test fun id3WithoutUsableChaptersGivesNothing() {
        assertTrue(read(id3(2, ByteArray(40))).isEmpty())                                   // ID3v2.2 : pas de CHAP
        assertTrue(read(id3(3, frame("TALB", byteArrayOf(0, 65), false))).isEmpty())        // aucune frame CHAP
        assertTrue(read("ID3".toByteArray()).isEmpty())                                     // en-tête tronqué
    }

    @Test fun absurdId3SizesAreRejected() {
        // frame annonçant ~268 Mo dans une balise de 20 octets
        val bad = "CHAP".toByteArray() + syncsafe(0x0FFFFFFF) + byteArrayOf(0, 0) + ByteArray(5)
        assertTrue(read(id3(4, bad)).isEmpty())
        // taille de balise énorme dans un fichier minuscule
        val huge = "ID3".toByteArray() + byteArrayOf(4, 0, 0) + syncsafe(0x0FFFFFFF) + ByteArray(30)
        assertTrue(read(huge).isEmpty())
        // octets aléatoires derrière l'en-tête
        assertTrue(read(id3(3, ByteArray(300) { (it * 37 + 11).toByte() })).isEmpty())
    }
}

