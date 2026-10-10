package fr.jd.audiobooks

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import kotlinx.coroutines.*
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
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO) // indépendant de l'UI et du service
    private val uriCache = HashMap<String, Uri>() // dossier -> uri du fichier, pour ne pas relister à chaque écriture

    /** Le fichier est lisible et modifiable à la main, donc on ne lui fait pas confiance : valeurs bornées
     *  (vitesse utilisable, position et index positifs, date pas plus d'un jour dans le futur). */
    fun parse(bytes: ByteArray, now: Long = System.currentTimeMillis()): Data? = try {
        JSONObject(String(bytes, Charsets.UTF_8)).let {
            Data(
                it.getInt("index").coerceAtLeast(0), it.optString("file", "").ifEmpty { null }, it.getLong("pos").coerceAtLeast(0L),
                safeSpeed(it.optDouble("speed", 1.0).toFloat()), it.optBoolean("finished", false),
                it.optLong("updated", 0L).coerceIn(0L, now + 24L * 3600 * 1000)
            )
        }
    } catch (e: Exception) { logw("$NAME illisible", e); null }

    private fun toBytes(bookName: String, d: Data): ByteArray = JSONObject()
        .put("app", "JD Audiobook Reader").put("version", 1).put("book", bookName)
        .put("index", d.index).put("file", d.file).put("pos", d.pos)
        .put("speed", d.speed.toDouble()).put("finished", d.finished).put("updated", d.updated)
        .toString(2).toByteArray(Charsets.UTF_8)

    /** Identifiant SAF du dossier du livre : mémorisé au scan, sinon déduit du premier fichier audio. */
    fun dirOf(bk: Book): String? = bk.dir ?: dirFromUri(bk.uris.firstOrNull())
    private fun dirFromUri(uri: String?): String? {
        if (uri == null) return null
        return try {
            DocumentsContract.getDocumentId(Uri.parse(uri)).substringBeforeLast('/', "").ifEmpty { null }
        } catch (e: Exception) { logw("dossier du livre illisible", e); null }
    }

    /** Infos attachées à chaque MediaItem : le service (qui sauvegarde la progression) n'a ainsi pas besoin de retrouver le livre. */
    fun extras(bk: Book): Bundle = Bundle().apply {
        putString("path", bk.path); putString("book", bk.name); dirOf(bk)?.let { putString("dir", it) }
    }

    private fun find(ctx: Context, tree: Uri, dirId: String): Uri? = findDoc(ctx, tree, dirId, NAME)

    /** URI du fichier [name] directement dans le dossier [dirId], ou null s'il n'existe pas. */
    fun findDoc(ctx: Context, tree: Uri, dirId: String, name: String): Uri? {
        val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId)
        val proj = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        ctx.contentResolver.query(kids, proj, null, null, null)?.use { c ->
            while (c.moveToNext()) if (c.getString(1) == name) return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
        }
        return null
    }

    /** Lit le fichier de progression du livre (null s'il n'existe pas ou n'est pas lisible). */
    fun read(ctx: Context, root: String?, bk: Book): Data? {
        if (root == null) return null
        val dir = dirOf(bk) ?: return null
        return try {
            val tree = Uri.parse(root)
            val uri = find(ctx, tree, dir)
            if (uri == null) null
            else {
                synchronized(lock) { uriCache[dir] = uri }
                ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.let { parse(it) }
            }
        } catch (e: Exception) { null }
    }

    /** L'appli a-t-elle encore le droit d'écrire dans le dossier choisi ? (les anciennes autorisations étaient en lecture seule) */
    fun canWrite(ctx: Context, root: String?): Boolean =
        root != null && ctx.contentResolver.persistedUriPermissions.any { it.uri.toString() == root && it.isWritePermission }

    /** Écrit (ou crée) le fichier dans le dossier du livre. À appeler hors du thread principal. */
    fun writeAsync(ctx: Context, root: String?, dir: String?, bookName: String, d: Data) {
        val app = ctx.applicationContext
        ioScope.launch { write(app, root, dir, bookName, d) }
    }

    fun write(ctx: Context, root: String?, dir: String?, bookName: String, d: Data): Boolean {
        if (root == null || dir == null) return false
        return synchronized(lock) { writeLocked(ctx, root, dir, bookName, d) }
    }

    private fun writeLocked(ctx: Context, root: String, dir: String, bookName: String, d: Data): Boolean {
        val tree = Uri.parse(root)
        val bytes = toBytes(bookName, d)
        val res = ctx.contentResolver
        for (attempt in 0..1) {
            try {
                var uri = uriCache[dir] ?: find(ctx, tree, dir)
                if (uri == null) {
                    uri = DocumentsContract.createDocument(
                        res, DocumentsContract.buildDocumentUriUsingTree(tree, dir), "application/octet-stream", NAME
                    )
                }
                if (uri == null) return false
                uriCache[dir] = uri
                val os = try { res.openOutputStream(uri, "wt") } catch (e: Exception) { res.openOutputStream(uri, "w") }
                if (os == null) return false
                os.use { it.write(bytes) }
                return true
            } catch (e: Exception) {
                logw("écriture de $NAME (essai ${attempt + 1})", e)
                uriCache.remove(dir) // fichier supprimé entre-temps : on le relocalise / recrée au 2e essai
            }
        }
        return false
    }
}
