package fr.jd.audiobooks

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONObject

/**
 * Fichier de progression de l'appli, écrit dans le dossier du livre, à côté des fichiers audio (comme le
 * position.sabp.dat de Smart AudioBook Player, mais lisible : c'est du JSON). Il suit le livre s'il est copié
 * ou synchronisé sur un autre appareil. Quand il existe, il a priorité sur celui de Smart Player.
 */
object ProgressFile {
    const val NAME = "position.jd.json"

    data class Data(val index: Int, val file: String?, val pos: Long, val speed: Float, val finished: Boolean, val updated: Long)

    private val lock = Any()
    private val uriCache = HashMap<String, Uri>() // dossier -> uri du fichier, pour ne pas relister à chaque écriture

    fun parse(bytes: ByteArray): Data? = try {
        JSONObject(String(bytes, Charsets.UTF_8)).let {
            Data(
                it.getInt("index"), it.optString("file", "").ifEmpty { null }, it.getLong("pos"),
                it.optDouble("speed", 1.0).toFloat(), it.optBoolean("finished", false), it.optLong("updated", 0L)
            )
        }
    } catch (e: Exception) { null }

    private fun toBytes(bookName: String, d: Data): ByteArray = JSONObject()
        .put("app", "JD Audiobook Reader").put("version", 1).put("book", bookName)
        .put("index", d.index).put("file", d.file).put("pos", d.pos)
        .put("speed", d.speed.toDouble()).put("finished", d.finished).put("updated", d.updated)
        .toString(2).toByteArray(Charsets.UTF_8)

    /** Identifiant SAF du dossier du livre : mémorisé au scan, sinon déduit du premier fichier audio. */
    private fun dirId(bk: Book): String? = bk.dir ?: try {
        DocumentsContract.getDocumentId(Uri.parse(bk.uris[0])).substringBeforeLast('/', "").ifEmpty { null }
    } catch (e: Exception) { null }

    private fun find(ctx: Context, tree: Uri, dirId: String): Uri? {
        val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId)
        val proj = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        ctx.contentResolver.query(kids, proj, null, null, null)?.use { c ->
            while (c.moveToNext()) if (c.getString(1) == NAME) return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
        }
        return null
    }

    /** Lit le fichier de progression du livre (null s'il n'existe pas ou n'est pas lisible). */
    fun read(ctx: Context, root: String?, bk: Book): Data? = try {
        val tree = Uri.parse(root ?: return null)
        val dir = dirId(bk) ?: return null
        val uri = find(ctx, tree, dir)
        if (uri == null) null
        else {
            synchronized(lock) { uriCache[dir] = uri }
            ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.let { parse(it) }
        }
    } catch (e: Exception) { null }

    /** L'appli a-t-elle encore le droit d'écrire dans le dossier choisi ? (les anciennes autorisations étaient en lecture seule) */
    fun canWrite(ctx: Context, root: String?): Boolean =
        root != null && ctx.contentResolver.persistedUriPermissions.any { it.uri.toString() == root && it.isWritePermission }

    /** Écrit (ou crée) le fichier dans le dossier du livre. À appeler hors du thread principal. */
    fun write(ctx: Context, root: String?, bk: Book, d: Data): Boolean = synchronized(lock) {
        try {
            val tree = Uri.parse(root ?: return false)
            val dir = dirId(bk) ?: return false
            val bytes = toBytes(bk.name, d)
            val res = ctx.contentResolver
            for (attempt in 0..1) {
                try {
                    val uri = uriCache[dir] ?: find(ctx, tree, dir)
                        ?: DocumentsContract.createDocument(res, DocumentsContract.buildDocumentUriUsingTree(tree, dir), "application/octet-stream", NAME)
                        ?: return false
                    uriCache[dir] = uri
                    val os = try { res.openOutputStream(uri, "wt") } catch (e: Exception) { res.openOutputStream(uri, "w") }
                    os?.use { it.write(bytes) } ?: return false
                    return true
                } catch (e: Exception) { uriCache.remove(dir) } // fichier supprimé entre-temps : on relocalise / recrée une fois
            }
            false
        } catch (e: Exception) { false }
    }
}
