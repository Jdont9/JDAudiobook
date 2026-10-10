package fr.jd.audiobooks

import android.content.Context
import android.graphics.*
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

data class Chap(val startMs: Long, val title: String)

/**
 * Lit les chapitres d'un fichier audio :
 *  - .mp3 : balise ID3v2.3/2.4, une frame « CHAP » par chapitre (titre dans sa sous-frame TIT2) ;
 *  - .m4b/.m4a : atome Nero « chpl » (dans moov/udta), sinon piste texte QuickTime.
 * Le fichier n'est pas de confiance (il peut venir de n'importe où) : toutes les tailles et tous les compteurs lus
 * sont bornés avant d'allouer ou de boucler, et un fichier suspect donne simplement « pas de chapitres ».
 */
object Chapters {
    private const val MAX_MOOV = 32 * 1024 * 1024 // un moov normal fait quelques Mo ; au-delà, on ne le charge pas
    private const val MAX_CHAPTERS = 20_000
    private const val MAX_ID3_FRAME = 64 * 1024              // on ne lit que le début d'une frame CHAP (titre) : pas ses images
    private const val MAX_ID3_BUDGET = 16L * 1024 * 1024     // total lu dans les frames CHAP d'un fichier

    fun read(ctx: Context, uri: String): List<Chap> = try {
        ctx.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { pfd ->
            readChannel(FileInputStream(pfd.fileDescriptor).channel)
        } ?: emptyList()
    } catch (e: Exception) { logw("chapitres illisibles", e); emptyList() }

    private const val MIN_GAP_MS = 1_000L

    /**
     * Une liste de chapitres n'a de sens que si elle découpe vraiment le fichier : au moins 2 chapitres, séparés d'au moins
     * une seconde. Certains fichiers portent des chapitres bidon (un seul, ou tous dans la première seconde : l'écran les
     * affiche alors tous à 0:00:00) : on les ignore. Un chapitre trop proche du précédent est écarté (le premier reste).
     */
    internal fun usable(list: List<Chap>): List<Chap> {
        val kept = ArrayList<Chap>()
        for (c in list.sortedBy { it.startMs }) if (kept.isEmpty() || c.startMs - kept.last().startMs >= MIN_GAP_MS) kept += c
        return if (kept.size >= 2) kept else emptyList()
    }

    /** Choisit le lecteur selon le contenu (balise ID3 en tête = MP3), pas selon l'extension. */
    internal fun readChannel(ch: FileChannel): List<Chap> = try {
        usable(if (hasId3(ch)) readId3(ch) else readMp4(ch))
    } catch (e: Exception) { logw("chapitres illisibles", e); emptyList() }
    catch (e: OutOfMemoryError) { logw("chapitres : fichier trop gros", e); emptyList() }

    // ---- MP3 : ID3v2.3 / 2.4, frames CHAP ----

    private fun hasId3(ch: FileChannel): Boolean {
        if (ch.size() < 10) return false
        val b = ByteBuffer.allocate(3)
        return fill(ch, b, 0) && b.get(0) == 'I'.code.toByte() && b.get(1) == 'D'.code.toByte() && b.get(2) == '3'.code.toByte()
    }

    private fun syncsafe(b: ByteBuffer, at: Int): Int =
        ((b.get(at).toInt() and 0x7f) shl 21) or ((b.get(at + 1).toInt() and 0x7f) shl 14) or
            ((b.get(at + 2).toInt() and 0x7f) shl 7) or (b.get(at + 3).toInt() and 0x7f)

    private fun u32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xff) shl 24) or ((b[at + 1].toLong() and 0xff) shl 16) or
            ((b[at + 2].toLong() and 0xff) shl 8) or (b[at + 3].toLong() and 0xff)

    /** Annule la « désynchronisation » ID3 : tout 0xFF 0x00 redevient 0xFF. */
    private fun deunsync(b: ByteArray): ByteArray {
        val o = java.io.ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) {
            o.write(b[i].toInt())
            if (b[i] == 0xFF.toByte() && i + 1 < b.size && b[i + 1] == 0.toByte()) i++
            i++
        }
        return o.toByteArray()
    }

    private fun readId3(ch: FileChannel): List<Chap> {
        val size = ch.size()
        val h = ByteBuffer.allocate(10)
        if (!fill(ch, h, 0)) return emptyList()
        val major = h.get(3).toInt() and 0xff
        if (major != 3 && major != 4) return emptyList() // ID3v2.2 n'a pas de chapitres
        val flags = h.get(5).toInt() and 0xff
        val tagEnd = minOf(10L + syncsafe(h, 6).toLong(), size)
        val tagUnsync = major == 3 && (flags and 0x80) != 0
        var pos = 10L
        if ((flags and 0x40) != 0) { // en-tête étendu : on le saute
            val e = ByteBuffer.allocate(4)
            if (!fill(ch, e, pos)) return emptyList()
            val extSize = if (major == 4) syncsafe(e, 0).toLong() else (e.getInt(0).toLong() and 0xffffffffL) + 4
            if (extSize < 4 || pos + extSize > tagEnd) return emptyList()
            pos += extSize
        }
        val out = ArrayList<Chap>()
        val fh = ByteBuffer.allocate(10)
        var frames = 0
        var budget = MAX_ID3_BUDGET
        while (pos + 10 <= tagEnd && frames++ < 100_000 && out.size < MAX_CHAPTERS) {
            fh.clear()
            if (!fill(ch, fh, pos)) break
            if (fh.get(0).toInt() == 0) break // zone de remplissage : plus de frames
            val idBytes = ByteArray(4) { fh.get(it) }
            if (idBytes.any { !(it in 'A'.code.toByte()..'Z'.code.toByte() || it in '0'.code.toByte()..'9'.code.toByte()) }) break // plus une frame valide
            val fsize = (if (major == 4) syncsafe(fh, 4) else fh.getInt(4)).toLong()
            if (fsize < 0 || pos + 10 + fsize > tagEnd) break
            val fmt = fh.get(9).toInt() and 0xff
            if (String(idBytes, Charsets.ISO_8859_1) == "CHAP" && budget > 0) {
                // v2.3 : 0x80 compression, 0x40 chiffrement, 0x20 groupe ; v2.4 : 0x40 groupe, 0x08 compression, 0x04 chiffrement,
                // 0x02 désynchronisation, 0x01 taille ajoutée devant les données.
                val unusable = if (major == 4) (fmt and 0x0C) != 0 else (fmt and 0xC0) != 0
                if (!unusable && fsize > 0) {
                    val take = minOf(fsize, MAX_ID3_FRAME.toLong()).toInt()
                    val raw = ByteBuffer.allocate(take)
                    if (!fill(ch, raw, pos + 10)) break
                    budget -= take
                    var body = raw.array()
                    if (tagUnsync || (major == 4 && (fmt and 0x02) != 0)) body = deunsync(body)
                    var skip = 0
                    if ((major == 4 && (fmt and 0x40) != 0) || (major == 3 && (fmt and 0x20) != 0)) skip += 1
                    if (major == 4 && (fmt and 0x01) != 0) skip += 4
                    if (skip < body.size) parseChap(body, skip, major)?.let { out += it }
                }
            }
            pos += 10 + fsize
        }
        return out.sortedBy { it.startMs }
    }

    /** Corps d'une frame CHAP : identifiant (texte + 0), début/fin en ms, deux décalages, puis sous-frames (TIT2 = titre). */
    private fun parseChap(b: ByteArray, from: Int, major: Int): Chap? {
        var p = from
        while (p < b.size && b[p] != 0.toByte()) p++
        p++ // le 0 final
        if (p + 16 > b.size) return null
        val start = u32(b, p)
        p += 16
        var title: String? = null; var subtitle: String? = null
        while (p + 10 <= b.size) {
            if (b[p].toInt() == 0) break
            val sid = String(b, p, 4, Charsets.ISO_8859_1)
            val ssize = if (major == 4) ((b[p + 4].toInt() and 0x7f) shl 21) or ((b[p + 5].toInt() and 0x7f) shl 14) or
                    ((b[p + 6].toInt() and 0x7f) shl 7) or (b[p + 7].toInt() and 0x7f)
                else ((b[p + 4].toInt() and 0xff) shl 24) or ((b[p + 5].toInt() and 0xff) shl 16) or
                    ((b[p + 6].toInt() and 0xff) shl 8) or (b[p + 7].toInt() and 0xff)
            val end = p + 10 + ssize
            if (ssize < 0 || end < p || end > b.size) break // sous-frame (une image, souvent) plus grande que ce qu'on a lu
            if (sid == "TIT2" && title == null) title = text(b, p + 10, end)
            else if (sid == "TIT3" && subtitle == null) subtitle = text(b, p + 10, end)
            p = end
        }
        return Chap(start, (title ?: subtitle).orEmpty())
    }

    /** Frame de texte ID3 : un octet d'encodage (0 latin-1, 1 UTF-16 avec BOM, 2 UTF-16BE, 3 UTF-8), puis le texte. */
    private fun text(b: ByteArray, from: Int, to: Int): String {
        if (from >= to) return ""
        var s = from + 1
        val cs = when (b[from].toInt()) {
            1 -> when {
                to - s >= 2 && b[s] == 0xFE.toByte() && b[s + 1] == 0xFF.toByte() -> { s += 2; Charsets.UTF_16BE }
                to - s >= 2 && b[s] == 0xFF.toByte() && b[s + 1] == 0xFE.toByte() -> { s += 2; Charsets.UTF_16LE }
                else -> Charsets.UTF_16LE
            }
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        return String(b, s, (to - s).coerceAtLeast(0), cs).substringBefore('\u0000').trim()
    }

    // ---- MP4 / M4B ----

    private fun readMp4(ch: FileChannel): List<Chap> = try {
        var pos = 0L
        val size = ch.size()
        var res: List<Chap> = emptyList()
        while (pos + 8 <= size) {
            val h = ByteBuffer.allocate(16)
            h.limit(minOf(16L, size - pos).toInt())
            if (!fill(ch, h, pos)) break
            h.flip()
            var len = h.int.toLong() and 0xffffffffL
            val type = ByteArray(4).also { h.get(it) }.toString(Charsets.ISO_8859_1)
            var hdr = 8
            if (len == 1L) { if (h.remaining() < 8) break; len = h.long; hdr = 16 }
            if (len == 0L || pos + len > size) len = size - pos // « jusqu'à la fin », ou fichier tronqué
            if (len < hdr) break
            if (type == "moov") {
                val body = len - hdr
                if (body <= 0 || body > MAX_MOOV) break // taille absurde : fichier corrompu ou piégé
                val b = ByteBuffer.allocate(body.toInt())
                if (!fill(ch, b, pos + hdr)) break
                b.flip()
                res = find(b.duplicate())
                if (res.isEmpty()) res = quicktime(ch, b)
                break
            }
            pos += len
        }
        res
    } catch (e: Exception) { logw("chapitres illisibles", e); emptyList() }
    catch (e: OutOfMemoryError) { logw("chapitres : fichier trop gros", e); emptyList() }

    /** Lit exactement ce que le tampon peut contenir (un seul read() peut rendre moins) ; false si le fichier finit avant. */
    private fun fill(ch: FileChannel, b: ByteBuffer, at: Long): Boolean {
        var p = at
        while (b.hasRemaining()) {
            val n = ch.read(b, p)
            if (n <= 0) return false
            p += n
        }
        return true
    }

    // ---- Chapitres QuickTime : piste texte référencée par tref/chap ----
    private class Bx(val type: String, val s: Int, val e: Int)

    private fun kids(b: ByteBuffer, s: Int, e: Int): List<Bx> {
        val out = ArrayList<Bx>(); var p = s
        while (p + 8 <= e) {
            val len = b.getInt(p); if (len < 8) break
            out += Bx(String(ByteArray(4) { b.get(p + 4 + it) }, Charsets.ISO_8859_1), p + 8, minOf(p + len, e).coerceAtLeast(p + 8))
            if (p + len < p) break // débordement : boîte absurde
            p += len
        }
        return out
    }

    private fun path(b: ByteBuffer, from: Bx, vararg n: String): Bx? {
        var c = from
        for (x in n) c = kids(b, c.s, c.e).firstOrNull { it.type == x } ?: return null
        return c
    }

    private fun quicktime(ch: FileChannel, b: ByteBuffer): List<Chap> {
        val traks = kids(b, 0, b.limit()).filter { it.type == "trak" }
        fun id(t: Bx) = path(b, t, "tkhd")?.let { b.getInt(it.s + if (b.get(it.s).toInt() == 1) 20 else 12) }
        val target = traks.firstNotNullOfOrNull { path(b, it, "tref", "chap")?.let { c -> b.getInt(c.s) } } ?: return emptyList()
        val t = traks.firstOrNull { id(it) == target } ?: return emptyList()
        val mdhd = path(b, t, "mdia", "mdhd") ?: return emptyList()
        val ts = b.getInt(mdhd.s + if (b.get(mdhd.s).toInt() == 1) 20 else 12).toLong().coerceAtLeast(1)
        val stbl = path(b, t, "mdia", "minf", "stbl") ?: return emptyList()
        val k = kids(b, stbl.s, stbl.e).associateBy { it.type }
        val stts = k["stts"]; val stsz = k["stsz"]; val stsc = k["stsc"]; val co = k["stco"] ?: k["co64"]
        if (stts == null || stsz == null || stsc == null || co == null) return emptyList()
        // Chaque compteur annoncé par le fichier doit tenir dans la boîte qui le contient.
        val n = b.getInt(stsz.s + 8)
        val fixed = b.getInt(stsz.s + 4)
        if (n < 0 || n > MAX_CHAPTERS) return emptyList()
        if (fixed == 0 && stsz.s + 12L + 4L * n > stsz.e) return emptyList()
        val nts = b.getInt(stts.s + 4)
        if (nts < 0 || stts.s + 8L + 8L * nts > stts.e) return emptyList()
        val nsc = b.getInt(stsc.s + 4)
        if (nsc < 1 || stsc.s + 8L + 12L * nsc > stsc.e) return emptyList()
        val nch = b.getInt(co.s + 4)
        val coSize = if (co.type == "co64") 8L else 4L
        if (nch < 0 || co.s + 8L + coSize * nch > co.e) return emptyList()
        val sizes = IntArray(n) { if (fixed != 0) fixed else b.getInt(stsz.s + 12 + 4 * it) }
        val starts = LongArray(n); var i = 0; var tt = 0L
        for (x in 0 until nts) {
            val cnt = b.getInt(stts.s + 8 + 8 * x); val dl = b.getInt(stts.s + 12 + 8 * x)
            var left = minOf(cnt.toLong().coerceAtLeast(0L), (n - i).toLong()).toInt()
            while (left-- > 0) { starts[i++] = tt * 1000 / ts; tt += dl }
        }
        val offs = LongArray(n)
        var sm = 0; var e = 0
        for (c in 1..nch) {
            while (e + 1 < nsc && b.getInt(stsc.s + 8 + 12 * (e + 1)) <= c) e++
            var per = b.getInt(stsc.s + 12 + 12 * e)
            var off = if (co.type == "co64") b.getLong(co.s + 8 + 8 * (c - 1)) else b.getInt(co.s + 8 + 4 * (c - 1)).toLong() and 0xffffffffL
            while (per > 0 && sm < n) { offs[sm] = off; off += sizes[sm]; sm++; per-- }
            if (sm >= n) break
        }
        val fileSize = ch.size()
        return (0 until n).mapNotNull { j ->
            if (sizes[j] < 2 || sizes[j] > 4096) return@mapNotNull null
            if (offs[j] < 0 || offs[j] + sizes[j] > fileSize) return@mapNotNull null
            val buf = ByteBuffer.allocate(sizes[j])
            if (!fill(ch, buf, offs[j])) return@mapNotNull null
            buf.flip()
            val len = (buf.short.toInt() and 0xffff).coerceAtMost(buf.remaining())
            val raw = ByteArray(len).also { buf.get(it) }
            val cs = when {
                len >= 2 && raw[0] == 0xFE.toByte() && raw[1] == 0xFF.toByte() -> Charsets.UTF_16BE
                len >= 2 && raw[0] == 0xFF.toByte() && raw[1] == 0xFE.toByte() -> Charsets.UTF_16LE
                else -> Charsets.UTF_8
            }
            Chap(starts[j], String(raw, cs).trimStart('\uFEFF'))
        }
    }

    private fun find(b: ByteBuffer): List<Chap> {
        while (b.remaining() >= 8) {
            val start = b.position()
            val len = b.int
            val type = ByteArray(4).also { b.get(it) }.toString(Charsets.ISO_8859_1)
            if (len < 8) break
            val end = start + len
            if (end < start || end > b.limit()) break // boîte plus grande que son contenant : on s'arrête
            if (type == "udta") {
                val sub = b.duplicate(); sub.limit(end)
                val r = find(sub); if (r.isNotEmpty()) return r
            } else if (type == "chpl") {
                if (b.remaining() < 9) break
                b.position(b.position() + 8) // version+flags, réservé
                val n = b.get().toInt() and 0xff
                val out = ArrayList<Chap>(n)
                for (c in 0 until n) {
                    if (b.position() + 9 > end) break
                    val t = b.long / 10_000
                    val l = b.get().toInt() and 0xff
                    if (b.position() + l > end) break
                    out += Chap(t, String(ByteArray(l).also { a -> b.get(a) }, Charsets.UTF_8))
                }
                return out
            }
            b.position(end)
        }
        return emptyList()
    }
}

/** Durée d'un fichier audio, lue dans ses en-têtes (sans le décoder). -1 si illisible. */
object Durations {
    fun probe(ctx: Context, uri: String): Long = try {
        MediaMetadataRetriever().run {
            try {
                setDataSource(ctx, Uri.parse(uri))
                extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 } ?: -1L
            } finally { release() }
        }
    } catch (e: Exception) { logw("durée illisible", e); -1L }
}

/** Pochette : image du dossier (cover/folder/front), sinon image intégrée au premier fichier,
 *  sinon pochette téléchargée et gardée dans le stockage de l'appli. */
object Covers {
    private val cache = LruCache<String, Bitmap>(16)
    /** Incrémenté quand une pochette change : les écrans qui l'affichent se rechargent. */
    var version by androidx.compose.runtime.mutableStateOf(0)

    fun localFile(ctx: Context, path: String): java.io.File =
        java.io.File(java.io.File(ctx.filesDir, "covers").also { it.mkdirs() }, Integer.toHexString(path.hashCode()) + ".jpg")

    fun invalidate(path: String, ctx: Context? = null) {
        cache.remove(path)
        ctx?.let { artFile(it, path).delete() }
        version++
    }

    const val ART_AUTHORITY = "fr.jd.audiobooks.art"

    private fun artFile(ctx: Context, path: String): File =
        File(File(ctx.filesDir, "art").also { it.mkdirs() }, Integer.toHexString(path.hashCode()) + ".jpg")

    /** URI (servie par CoverProvider) d'une petite copie JPEG de la pochette ; null si le livre n'en a pas.
     *  Sert d'artwork aux MediaItem à la place de l'image elle-même, recopiée avant dans chaque fichier. */
    suspend fun artUri(ctx: Context, bk: Book): Uri? = withContext(Dispatchers.IO) {
        val f = artFile(ctx, bk.path)
        if (!f.isFile) {
            val bmp = get(ctx, bk) ?: return@withContext null
            try { f.writeBytes(jpeg(bmp)) } catch (e: Exception) { logw("pochette réduite non écrite", e); return@withContext null }
        }
        Uri.parse("content://$ART_AUTHORITY/${f.name}?v=${f.lastModified()}")
    }

    suspend fun get(ctx: Context, bk: Book): Bitmap? = withContext(Dispatchers.IO) {
        cache.get(bk.path) ?: load(ctx, bk)?.also { cache.put(bk.path, it) }
    }

    /** Vrai si le livre a déjà une pochette quelque part (dossier, fichier audio ou téléchargée). */
    fun hasCover(ctx: Context, bk: Book): Boolean {
        if (bk.cover != null || localFile(ctx, bk.path).exists()) return true
        return try {
            MediaMetadataRetriever().run {
                try { setDataSource(ctx, Uri.parse(bk.uris[0])); embeddedPicture != null } finally { release() }
            }
        } catch (e: Exception) { logw("pochette intégrée illisible", e); false }
    }

    /** Marque posée quand une pochette choisie à la main n'a pas pu être écrite dans le dossier du livre : la copie de
     *  l'appli doit alors passer avant l'ancienne image du dossier. */
    fun pinFile(ctx: Context, path: String): File = File(localFile(ctx, path).path + ".pin")

    private fun load(ctx: Context, bk: Book): Bitmap? {
        val local = localFile(ctx, bk.path)
        fun readLocal(): ByteArray? = local.takeIf { it.exists() }?.let { try { it.readBytes() } catch (e: Exception) { null } }
        var data: ByteArray? = if (pinFile(ctx, bk.path).exists()) readLocal() else null
        if (data == null) data = try {
            bk.cover?.let { c -> ctx.contentResolver.openInputStream(Uri.parse(c))?.use { it.readBytes() } }
                ?: MediaMetadataRetriever().run {
                    try { setDataSource(ctx, Uri.parse(bk.uris[0])); embeddedPicture } finally { release() }
                }
        } catch (e: Exception) { null }
        if (data == null) data = readLocal()
        if (data == null) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, o)
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / s > 512) s *= 2
        return BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = s })
    }

    fun jpeg(b: Bitmap): ByteArray = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
}
