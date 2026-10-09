package fr.jd.audiobooks

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.*
import java.io.File
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.*

// path : chemin relatif à la racine choisie (ex. "Millenium/Millenium 1 - ..."), identifiant stable du livre,
// aligné sur le format utilisé par Smart AudioBook Player pour pouvoir croiser ses données.
data class Book(val path: String, val name: String, val uris: List<String>, val names: List<String>, val cover: String? = null, val dir: String? = null)
data class Saved(val index: Int, val pos: Long, val speed: Float, val updated: Long = 0L)
data class Bookmark(val file: String, val pos: Long, val created: Long)
data class Stat(val book: String, val day: String, val wall: Long, val content: Long)
/** Minuscules sans accents : pour la recherche dans la bibliothèque. */
fun fold(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

/** Avancement du scan pendant qu'il tourne : nombre de dossiers déjà explorés et de livres déjà trouvés. */
data class ScanProgress(val folders: Int, val books: Int)

class Store(private val ctx: Context) {
    private val p = ctx.getSharedPreferences("p", 0)
    // Ancien emplacement du cache de la bibliothèque (préférences) : lu une seule fois pour migrer vers un fichier.
    private val lib = ctx.getSharedPreferences("lib", 0)

    private companion object {
        // Copie en mémoire du cache de la bibliothèque, partagée par toutes les instances de Store (appli, service) :
        // elle évite de relire et de reparser plusieurs Mo de JSON à chaque ouverture ou requête Android Auto.
        private var memKey: String? = null
        private var memBooks: List<Book>? = null
        private val memLock = Any()
    }
    var root: String? get() = p.getString("root", null); set(v) { p.edit().putString("root", v).apply() }

    // 4e champ = horodatage de la sauvegarde (absent des anciennes sauvegardes = 0) : sert à savoir si le fichier
    // de progression du dossier du livre est plus récent que ce qu'on a ici.
    fun save(path: String, i: Int, pos: Long, speed: Float, updated: Long = System.currentTimeMillis()) =
        p.edit().putString("s_$path", "$i|$pos|$speed|$updated").apply()
    fun load(path: String): Saved? = p.getString("s_$path", null)?.split("|")?.let {
        Saved(it[0].toInt(), it[1].toLong(), it[2].toFloat(), it.getOrNull(3)?.toLongOrNull() ?: 0L)
    }

    /** Applique le fichier de progression du dossier s'il est plus récent que la sauvegarde locale. */
    fun applyProgressFile(path: String, names: List<String>, d: ProgressFile.Data): Boolean {
        if (names.isEmpty()) return false
        val idx = d.file?.let { fn -> names.indexOf(fn) }?.takeIf { it >= 0 } ?: d.index.coerceIn(0, names.lastIndex)
        val cur = load(path)
        if (cur != null && d.updated <= cur.updated) return false
        save(path, idx, d.pos, d.speed, d.updated)
        setFinished(path, d.finished)
        return true
    }
    /** L'ordre des fichiers d'un livre a changé (tri naturel, fichier ajouté/supprimé) : les index enregistrés
     *  (position) sont retrouvés par nom de fichier pour continuer à pointer sur les bons fichiers. */
    fun remapIndices(path: String, oldNames: List<String>, newNames: List<String>) {
        if (oldNames == newNames) return
        fun map(i: Int): Int? = oldNames.getOrNull(i)?.let { n -> newNames.indexOf(n) }?.takeIf { it >= 0 }
        load(path)?.let { s -> map(s.index)?.let { ni -> if (ni != s.index) save(path, ni, s.pos, s.speed, s.updated) } }
    }
    fun hasSaved(path: String): Boolean = p.contains("s_$path")
    fun finished(path: String): Boolean = p.getBoolean("fin_$path", false)
    fun setFinished(path: String, v: Boolean) = p.edit().putBoolean("fin_$path", v).apply()

    // ---- Durées des fichiers d'un livre (en ms, <= 0 = inconnue). Calculées une seule fois en arrière-plan
    // puis gardées ici. La signature (hash des noms de fichiers) invalide automatiquement le cache si le
    // contenu du dossier change.
    fun durations(bk: Book): List<Long> {
        val unknown = List(bk.uris.size) { -1L }
        val raw = p.getString("dur_${bk.path}", null) ?: return unknown
        val sig = raw.substringBefore('|')
        if (sig != bk.names.hashCode().toString()) return unknown
        val l = raw.substringAfter('|').split(",").map { it.toLongOrNull() ?: -1L }
        return if (l.size == bk.uris.size) l else unknown
    }
    fun putDurations(bk: Book, l: List<Long>) =
        p.edit().putString("dur_${bk.path}", "${bk.names.hashCode()}|${l.joinToString(",")}").apply()

    // Petits réglages d'interface (cases à cocher du sélecteur de fichiers, etc.)
    fun flag(k: String, def: Boolean = false): Boolean = p.getBoolean("f_$k", def)
    fun setFlag(k: String, v: Boolean) = p.edit().putBoolean("f_$k", v).apply()

    /** Nettoyage unique des données des fonctions retirées (égaliseur, signets) dans les préférences. */
    fun cleanupLegacy() {
        if (p.getBoolean("cleanup_eq_marks", false)) return
        val e = p.edit()
        p.all.keys.filter { it == "eq_on" || it == "eq_levels" || it.startsWith("b_") }.forEach { e.remove(it) }
        e.putBoolean("cleanup_eq_marks", true).apply()
    }

    // Gain de volume (dB) par livre
    fun boost(path: String): Int = p.getInt("boost_$path", 0)
    fun setBoost(path: String, db: Int) = p.edit().putInt("boost_$path", db).apply()

    // Durée du saut avant/arrière (secondes)
    fun skipSeconds(): Int = p.getInt("skip_s", 30)
    fun setSkipSeconds(s: Int) = p.edit().putInt("skip_s", s).apply()

    // Dernier livre écouté : le widget s'en sert pour reprendre quand le lecteur est vide.
    fun lastBook(): String? = p.getString("last_book", null)
    fun setLastBook(path: String) { if (lastBook() != path) p.edit().putString("last_book", path).apply() }

    // Signets (par livre, repérés par nom de fichier pour survivre à un changement d'ordre)
    fun bookmarks(path: String): List<Bookmark> = try {
        val a = JSONArray(p.getString("bm_$path", null) ?: "[]")
        (0 until a.length()).map { i -> a.getJSONObject(i).let { Bookmark(it.getString("f"), it.getLong("p"), it.getLong("t")) } }
    } catch (e: Exception) { emptyList() }
    private fun putBookmarks(path: String, l: List<Bookmark>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("f", it.file).put("p", it.pos).put("t", it.created)) }
        p.edit().putString("bm_$path", a.toString()).apply()
    }
    fun addBookmark(path: String, b: Bookmark) = putBookmarks(path, bookmarks(path) + b)
    fun removeBookmark(path: String, b: Bookmark) = putBookmarks(path, bookmarks(path).filter { it != b })

    // Pochettes introuvables : on ne relance pas la recherche pour ces livres pendant 7 jours.
    fun coverTriedRecently(path: String): Boolean = System.currentTimeMillis() - p.getLong("cnf_$path", 0L) < 7L * 24 * 3600 * 1000
    fun markCoverTried(path: String) = p.edit().putLong("cnf_$path", System.currentTimeMillis()).apply()
    fun clearCoverTried(path: String) { if (p.contains("cnf_$path")) p.edit().remove("cnf_$path").apply() }

    // Statistiques : temps réel écouté et temps de contenu (tenant compte de la vitesse), par livre et par jour.
    // "t|" = mesuré en direct par l'appli ; "ti|" = importé depuis Smart AudioBook Player (granularité mensuelle,
    // jour fixé au 01 du mois), préfixe séparé pour ne jamais écraser une mesure réelle et rester idempotent.
    // Cumul en mémoire, écrit par paquets (flushTime) au lieu de réécrire les préférences chaque seconde.
    private val pendingTime = HashMap<String, LongArray>()
    fun addTime(path: String, wall: Long, content: Long) {
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        synchronized(pendingTime) {
            pendingTime.getOrPut("$path|$day") { LongArray(2) }.let { it[0] += wall; it[1] += content }
        }
    }
    fun flushTime() {
        val snap = synchronized(pendingTime) { HashMap(pendingTime).also { pendingTime.clear() } }
        if (snap.isEmpty()) return
        val e = p.edit()
        snap.forEach { (k, v) ->
            val key = "t|$k"
            val old = p.getString(key, "0,0")!!.split(",")
            e.putString(key, "${old[0].toLong() + v[0]},${old[1].toLong() + v[1]}")
        }
        e.apply()
    }
    fun importMonthlyStat(path: String, yearMonth: String, ms: Long) {
        p.edit().putString("ti|$path|${yearMonth}01", "$ms,$ms").apply()
    }
    fun stats(): List<Stat> = p.all.filterKeys { it.startsWith("t|") || it.startsWith("ti|") }.map { (k, v) ->
        val r = k.substringAfter('|'); val x = (v as String).split(",")
        Stat(r.substringBeforeLast('|'), r.substringAfterLast('|'), x[0].toLong(), x[1].toLong())
    }


    // ---- Cache de la bibliothèque : évite de tout re-scanner à chaque ouverture de l'appli.
    // Stocké dans un fichier JSON (filesDir) et gardé en mémoire ; invalidé si la racine change (clé incluant l'URI).
    private fun cacheKey() = "lib_cache_${root ?: ""}"
    private fun cacheFile() = File(ctx.filesDir, "library_${Integer.toHexString((root ?: "").hashCode())}.json")

    /** Disponible instantanément (sans lire le disque) si la bibliothèque a déjà été chargée dans ce processus. */
    fun cachedBooksInMemory(): List<Book>? = synchronized(memLock) { if (memKey == cacheKey()) memBooks else null }

    /** À appeler hors du thread principal la première fois (lecture + analyse du fichier). */
    fun cachedBooks(): List<Book>? {
        cachedBooksInMemory()?.let { return it }
        val text = readCacheText() ?: return null
        val list = parseBooks(text) ?: return null
        synchronized(memLock) { memKey = cacheKey(); memBooks = list }
        return list
    }

    private fun readCacheText(): String? {
        val f = cacheFile()
        if (f.exists()) return try { f.readText() } catch (e: Exception) { null }
        // Migration unique depuis les préférences (anciennes versions).
        val old = lib.getString(cacheKey(), null) ?: p.getString(cacheKey(), null) ?: return null
        try {
            writeCacheText(old)
            lib.edit().remove(cacheKey()).apply(); p.edit().remove(cacheKey()).apply()
        } catch (e: Exception) { }
        return old
    }

    private fun writeCacheText(text: String) {
        val f = cacheFile(); val tmp = File(f.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    private fun parseBooks(s: String): List<Book>? = try {
        val a = JSONArray(s)
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val uris = o.getJSONArray("u"); val names = o.getJSONArray("n")
            Book(
                o.getString("p"), o.getString("l"),
                (0 until uris.length()).map { uris.getString(it) },
                (0 until names.length()).map { names.getString(it) },
                if (o.has("c")) o.getString("c") else null,
                if (o.has("d")) o.getString("d") else null
            )
        }
    } catch (e: Exception) { null }

    private fun cacheBooks(books: List<Book>) {
        val a = JSONArray()
        books.forEach { bk ->
            a.put(JSONObject().apply {
                put("p", bk.path); put("l", bk.name)
                put("u", JSONArray(bk.uris)); put("n", JSONArray(bk.names))
                bk.cover?.let { put("c", it) }
                bk.dir?.let { put("d", it) }
            })
        }
        synchronized(memLock) { memKey = cacheKey(); memBooks = books }
        try { writeCacheText(a.toString()) } catch (e: Exception) { }
        lib.edit().remove(cacheKey()).apply(); p.edit().remove(cacheKey()).apply()
    }

    /** Mémorise une pochette (re)trouvée pour un livre dans le cache de la bibliothèque. */
    fun updateCover(path: String, uri: String) {
        val l = cachedBooks() ?: return
        cacheBooks(l.map { if (it.path == path) it.copy(cover = uri) else it })
    }

    /** Bibliothèque pour le service (Android Auto, notifications…) : le cache d'abord, un scan complet
     *  seulement s'il n'y en a pas encore. Avant, chaque requête d'un client média relançait un scan SAF
     *  complet en tâche de fond, en concurrence avec l'ouverture du livre dans l'appli. */
    fun library(): List<Book> = cachedBooks() ?: scan()

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
        val prev = cachedBooks()?.associateBy { it.path } ?: emptyMap() // ordre des fichiers avant ce scan
        var folders = 0
        var lastTick = 0L
        var sabpListedSeen = false // un position.sabp.dat est déjà apparu dans une liste de dossier
        var guessTries = 0

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
            val audio = files.filter { ext(it.name) in ext }.sortedWith(Comparator { a, b -> fileOrder(a.name, b.name) })
            if (audio.isNotEmpty()) {
                val im = files.filter { ext(it.name) in img }
                val cv = (im.firstOrNull { f -> listOf("cover", "folder", "front").any { f.name.lowercase().contains(it) } } ?: im.firstOrNull())
                    ?.let { uriFor(it.id) }
                val bk = Book(path, label, audio.map { uriFor(it.id) }, audio.map { it.name }, cv, dirId)
                out += bk
                prev[path]?.let { remapIndices(path, it.names, bk.names) }
                // Fichier de progression de l'appli (à côté des fichiers audio) : prioritaire. S'il existe et est
                // lisible, celui de Smart Player est ignoré ; sinon on retombe sur la logique Smart ci-dessous.
                var hasJd = false
                files.firstOrNull { it.name == ProgressFile.NAME }?.let { jf ->
                    val pj = try { resolver.openInputStream(Uri.parse(uriFor(jf.id)))?.use { it.readBytes() } } catch (e: Exception) { null }
                        ?.let { ProgressFile.parse(it) }
                    if (pj != null) { hasJd = true; applyProgressFile(path, bk.names, pj) }
                }
                // Le drapeau "Finished" est toujours relu (idempotent, il ne fait qu'ajouter l'état "lu").
                // Pour la position : on compare à ce que JD a déjà, et on n'importe que si Smart Player est
                // plus avancé (jamais de recul).
                val listed = files.firstOrNull { it.name == "position.sabp.dat" }?.let { uriFor(it.id) }
                if (listed != null) sabpListedSeen = true
                var bytes: ByteArray? = null
                if (!hasJd) {
                    if (listed != null) {
                        bytes = try { resolver.openInputStream(Uri.parse(listed))?.use { it.readBytes() } } catch (e: Exception) { null }
                    } else if (!sabpListedSeen && guessTries < 15) {
                        // Certains fournisseurs n'affichent pas ce fichier dans la liste : on tente l'URI directe, mais
                        // seulement sur les premiers livres (sinon c'est un appel qui échoue par livre, sur toute la bibliothèque).
                        guessTries++
                        bytes = try {
                            resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(treeUri, "$dirId/position.sabp.dat"))?.use { it.readBytes() }
                        } catch (e: Exception) { null }
                    }
                }
                if (bytes != null) {
                    SabpImport.parsePosition(bytes)?.let { sp ->
                        // L'index brut de Smart Player ne correspond pas forcément à l'ordre alphabétique
                        // qu'on utilise : on retrouve le bon fichier par son nom (présent dans le .dat)
                        // quand c'est possible, et on ne retombe sur l'index brut qu'en dernier recours.
                        val byName = sp.fileName?.let { fn -> audio.indexOfFirst { it.name == fn } }?.takeIf { it >= 0 }
                        val resolvedIndex = (byName ?: sp.queueIndex).coerceIn(0, audio.lastIndex)
                        val cur = load(path)
                        val more = cur == null || resolvedIndex > cur.index || (resolvedIndex == cur.index && sp.fileMs > cur.pos)
                        if (more) save(path, resolvedIndex, sp.fileMs, sp.speed)
                        if (sp.finished) setFinished(path, true)
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
