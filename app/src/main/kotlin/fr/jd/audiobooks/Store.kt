package fr.jd.audiobooks

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.*
import java.text.SimpleDateFormat
import java.util.*

// path : chemin relatif à la racine choisie (ex. "Millenium/Millenium 1 - ..."), identifiant stable du livre,
// aligné sur le format utilisé par Smart AudioBook Player pour pouvoir croiser ses données.
data class Book(val path: String, val name: String, val uris: List<String>, val names: List<String>, val cover: String? = null)
data class Mark(val index: Int, val pos: Long, val label: String)
data class Saved(val index: Int, val pos: Long, val speed: Float)
data class Stat(val book: String, val day: String, val wall: Long, val content: Long)
/** Avancement du scan pendant qu'il tourne : nombre de dossiers déjà explorés et de livres déjà trouvés. */
data class ScanProgress(val folders: Int, val books: Int)

class Store(private val ctx: Context) {
    private val p = ctx.getSharedPreferences("p", 0)
    var root: String? get() = p.getString("root", null); set(v) { p.edit().putString("root", v).apply() }

    fun save(path: String, i: Int, pos: Long, speed: Float) =
        p.edit().putString("s_$path", "$i|$pos|$speed").apply()
    fun load(path: String): Saved? = p.getString("s_$path", null)?.split("|")?.let { Saved(it[0].toInt(), it[1].toLong(), it[2].toFloat()) }
    fun hasSaved(path: String): Boolean = p.contains("s_$path")

    fun marks(path: String): List<Mark> {
        val a = JSONArray(p.getString("b_$path", "[]"))
        return (0 until a.length()).map { a.getJSONObject(it).let { o -> Mark(o.getInt("i"), o.getLong("p"), o.getString("l")) } }
    }
    fun putMarks(path: String, l: List<Mark>) {
        val a = JSONArray(); l.forEach { a.put(JSONObject().put("i", it.index).put("p", it.pos).put("l", it.label)) }
        p.edit().putString("b_$path", a.toString()).apply()
    }

    // Statistiques : temps réel écouté et temps de contenu (tenant compte de la vitesse), par livre et par jour.
    // "t|" = mesuré en direct par l'appli ; "ti|" = importé depuis Smart AudioBook Player (granularité mensuelle,
    // jour fixé au 01 du mois), préfixe séparé pour ne jamais écraser une mesure réelle et rester idempotent.
    fun addTime(path: String, wall: Long, content: Long) {
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val k = "t|$path|$day"
        val v = p.getString(k, "0,0")!!.split(",")
        p.edit().putString(k, "${v[0].toLong() + wall},${v[1].toLong() + content}").apply()
    }
    fun importMonthlyStat(path: String, yearMonth: String, ms: Long) {
        p.edit().putString("ti|$path|${yearMonth}01", "$ms,$ms").apply()
    }
    fun stats(): List<Stat> = p.all.filterKeys { it.startsWith("t|") || it.startsWith("ti|") }.map { (k, v) ->
        val r = k.substringAfter('|'); val x = (v as String).split(",")
        Stat(r.substringBeforeLast('|'), r.substringAfterLast('|'), x[0].toLong(), x[1].toLong())
    }

    fun eqEnabled(): Boolean = p.getBoolean("eq_on", false)
    fun setEqEnabled(v: Boolean) = p.edit().putBoolean("eq_on", v).apply()
    fun eqLevels(): List<Short>? = p.getString("eq_levels", null)?.split(",")?.map { it.toShort() }
    fun setEqLevels(l: List<Short>) = p.edit().putString("eq_levels", l.joinToString(",")).apply()

    // ---- Cache de la bibliothèque : évite de tout re-scanner à chaque ouverture de l'appli.
    // Invalidé automatiquement si la racine change (clé incluant l'URI de la racine).
    private fun cacheKey() = "lib_cache_${root ?: ""}"
    fun cachedBooks(): List<Book>? {
        val s = p.getString(cacheKey(), null) ?: return null
        return try {
            val a = JSONArray(s)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val uris = o.getJSONArray("u"); val names = o.getJSONArray("n")
                Book(
                    o.getString("p"), o.getString("l"),
                    (0 until uris.length()).map { uris.getString(it) },
                    (0 until names.length()).map { names.getString(it) },
                    if (o.has("c")) o.getString("c") else null
                )
            }
        } catch (e: Exception) { null }
    }
    private fun cacheBooks(books: List<Book>) {
        val a = JSONArray()
        books.forEach { bk ->
            a.put(JSONObject().apply {
                put("p", bk.path); put("l", bk.name)
                put("u", JSONArray(bk.uris)); put("n", JSONArray(bk.names))
                bk.cover?.let { put("c", it) }
            })
        }
        p.edit().putString(cacheKey(), a.toString()).apply()
    }

    // ---- Scan de l'arborescence : profondeur illimitée, tout dossier qui contient directement des
    // fichiers audio est un livre (même logique que Smart AudioBook Player). Utilise directement
    // DocumentsContract (une seule requête par dossier) plutôt que DocumentFile, qui fait un appel
    // binder séparé par fichier pour chaque propriété lue — sur une grosse bibliothèque, ça se compte
    // en dizaines de milliers d'appels et c'est ça qui rend le scan interminable.
    fun scan(onProgress: ((ScanProgress) -> Unit)? = null): List<Book> {
        val treeUri = Uri.parse(root ?: return emptyList())
        val rootId = try { DocumentsContract.getTreeDocumentId(treeUri) } catch (e: Exception) { return emptyList() }
        val ext = setOf("mp3", "m4b", "m4a", "ogg", "opus", "flac", "wav")
        val img = setOf("jpg", "jpeg", "png", "webp")
        val resolver = ctx.contentResolver
        val out = mutableListOf<Book>()
        var folders = 0
        var lastTick = 0L

        data class Kid(val id: String, val name: String, val isDir: Boolean)

        fun children(dirId: String): List<Kid> {
            val kids = mutableListOf<Kid>()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirId)
            val proj = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )
            try {
                resolver.query(childrenUri, proj, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        kids += Kid(c.getString(0), c.getString(1) ?: "", c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR)
                    }
                }
            } catch (e: Exception) { }
            return kids
        }
        fun ext(name: String) = name.substringAfterLast('.', "").lowercase()
        fun uriFor(id: String) = DocumentsContract.buildDocumentUriUsingTree(treeUri, id).toString()

        fun visit(dirId: String, path: String, label: String) {
            folders++
            val kids = children(dirId)
            val files = kids.filter { !it.isDir }
            val audio = files.filter { ext(it.name) in ext }.sortedBy { it.name.lowercase() }
            if (audio.isNotEmpty()) {
                val im = files.filter { ext(it.name) in img }
                val cv = (im.firstOrNull { f -> listOf("cover", "folder", "front").any { f.name.lowercase().contains(it) } } ?: im.firstOrNull())
                    ?.let { uriFor(it.id) }
                val bk = Book(path, label, audio.map { uriFor(it.id) }, audio.map { it.name }, cv)
                out += bk
                if (!hasSaved(path)) {
                    files.firstOrNull { it.name == "position_sabp.dat" }?.let { f ->
                        val bytes = try { resolver.openInputStream(Uri.parse(uriFor(f.id)))?.use { it.readBytes() } } catch (e: Exception) { null }
                        bytes?.let { SabpImport.parsePosition(it) }?.let { sp ->
                            save(path, sp.queueIndex.coerceIn(0, audio.lastIndex), sp.fileMs, sp.speed)
                        }
                    }
                }
            }
            val now = System.currentTimeMillis()
            if (onProgress != null && now - lastTick > 120) { lastTick = now; onProgress(ScanProgress(folders, out.size)) }
            kids.filter { it.isDir }.sortedBy { it.name.lowercase() }.forEach { sub ->
                visit(sub.id, if (path.isEmpty()) sub.name else "$path/${sub.name}", sub.name)
            }
        }
        val rootName = root?.let { Uri.parse(it).lastPathSegment?.substringAfterLast(':') } ?: "Racine"
        visit(rootId, "", rootName)
        onProgress?.invoke(ScanProgress(folders, out.size))
        cacheBooks(out)
        return out
    }
}
