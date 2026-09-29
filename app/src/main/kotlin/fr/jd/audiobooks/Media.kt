package fr.jd.audiobooks

import android.content.Context
import android.graphics.*
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

data class Chap(val startMs: Long, val title: String)

/** Lit les chapitres Nero (atome chpl dans moov/udta), le format de la plupart des .m4b. */
object Chapters {
    fun read(ctx: Context, uri: String): List<Chap> = try {
        ctx.contentResolver.openFileDescriptor(Uri.parse(uri), "r")!!.use { pfd ->
            val ch = FileInputStream(pfd.fileDescriptor).channel
            var pos = 0L
            val size = ch.size()
            val h = ByteBuffer.allocate(16)
            var res: List<Chap> = emptyList()
            while (pos + 8 <= size) {
                h.clear(); ch.read(h, pos); h.flip()
                var len = h.int.toLong() and 0xffffffffL
                val type = ByteArray(4).also { h.get(it) }.toString(Charsets.ISO_8859_1)
                var hdr = 8
                if (len == 1L) { len = h.long; hdr = 16 }
                if (len == 0L) len = size - pos
                if (len < 8) break
                if (type == "moov") {
                    val b = ByteBuffer.allocate((len - hdr).toInt())
                    ch.read(b, pos + hdr); b.flip()
                    res = find(b.duplicate())
                    if (res.isEmpty()) res = quicktime(ch, b)
                    break
                }
                pos += len
            }
            res
        }
    } catch (e: Exception) { emptyList() }


    // ---- Chapitres QuickTime : piste texte référencée par tref/chap ----
    private class Bx(val type: String, val s: Int, val e: Int)

    private fun kids(b: ByteBuffer, s: Int, e: Int): List<Bx> {
        val out = ArrayList<Bx>(); var p = s
        while (p + 8 <= e) {
            val len = b.getInt(p); if (len < 8) break
            out += Bx(String(ByteArray(4) { b.get(p + 4 + it) }, Charsets.ISO_8859_1), p + 8, minOf(p + len, e)); p += len
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
        val n = b.getInt(stsz.s + 8)
        val fixed = b.getInt(stsz.s + 4)
        val sizes = IntArray(n) { if (fixed != 0) fixed else b.getInt(stsz.s + 12 + 4 * it) }
        val starts = LongArray(n); var i = 0; var tt = 0L
        for (x in 0 until b.getInt(stts.s + 4)) {
            val cnt = b.getInt(stts.s + 8 + 8 * x); val dl = b.getInt(stts.s + 12 + 8 * x)
            repeat(cnt) { if (i < n) { starts[i++] = tt * 1000 / ts; tt += dl } }
        }
        val offs = LongArray(n); val nch = b.getInt(co.s + 4); val nsc = b.getInt(stsc.s + 4)
        var sm = 0; var e = 0
        for (c in 1..nch) {
            while (e + 1 < nsc && b.getInt(stsc.s + 8 + 12 * (e + 1)) <= c) e++
            val per = b.getInt(stsc.s + 12 + 12 * e)
            var off = if (co.type == "co64") b.getLong(co.s + 8 + 8 * (c - 1)) else b.getInt(co.s + 8 + 4 * (c - 1)).toLong() and 0xffffffffL
            repeat(per) { if (sm < n) { offs[sm] = off; off += sizes[sm]; sm++ } }
        }
        return (0 until n).mapNotNull { j ->
            if (sizes[j] < 2 || sizes[j] > 4096) return@mapNotNull null
            val buf = ByteBuffer.allocate(sizes[j]); ch.read(buf, offs[j]); buf.flip()
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
            if (type == "udta") {
                val sub = b.duplicate(); sub.limit(end)
                val r = find(sub); if (r.isNotEmpty()) return r
            } else if (type == "chpl") {
                b.position(b.position() + 8) // version+flags, réservé
                val n = b.get().toInt() and 0xff
                return (0 until n).map {
                    val t = b.long / 10_000
                    val l = b.get().toInt() and 0xff
                    Chap(t, String(ByteArray(l).also { a -> b.get(a) }, Charsets.UTF_8))
                }
            }
            b.position(end)
        }
        return emptyList()
    }
}

/** Pochette : image du dossier (cover/folder/front) sinon image intégrée au premier fichier. */
object Covers {
    private val cache = LruCache<String, Bitmap>(16)

    suspend fun get(ctx: Context, bk: Book): Bitmap? = withContext(Dispatchers.IO) {
        cache.get(bk.path) ?: load(ctx, bk)?.also { cache.put(bk.path, it) }
    }

    private fun load(ctx: Context, bk: Book): Bitmap? {
        val data: ByteArray = try {
            bk.cover?.let { c -> ctx.contentResolver.openInputStream(Uri.parse(c))?.use { it.readBytes() } }
                ?: MediaMetadataRetriever().run {
                    try { setDataSource(ctx, Uri.parse(bk.uris[0])); embeddedPicture } finally { release() }
                }
        } catch (e: Exception) { null } ?: return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, o)
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / s > 512) s *= 2
        return BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = s })
    }

    fun jpeg(b: Bitmap): ByteArray = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
}
