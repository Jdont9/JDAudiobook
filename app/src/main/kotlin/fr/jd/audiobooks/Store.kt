package fr.jd.audiobooks

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.*
import java.text.SimpleDateFormat
import java.util.*

// path : chemin relatif à la racine choisie (ex. "Millenium/Millenium 1 - ..."), identifiant stable du livre,
// aligné sur le format utilisé par Smart AudioBook Player pour pouvoir croiser ses données.
data class Book(val path: String, val name: String, val uris: List<String>, val names: List<String>, val cover: String? = null, val dir: String? = null)
data class Mark(val index: Int, val pos: Long, val label: String)
data class Saved(val index: Int, val pos: Long, val speed: Float, val updated: Long = 0L)
data class Stat(val book: String, val day: String, val wall: Long, val content: Long)
/** Avancement du scan pendant qu'il tourne : nombre de dossiers déjà explorés et de livres déjà trouvés. */
data class ScanProgress(val folders: Int, val books: Int)

class Store(private val ctx: Context) {
    private val p = ctx.getSharedPreferences("p", 0)
    // Le cache de la bibliothèque (toutes les URIs de tous les livres : parfois plusieurs Mo) vit dans son propre
    // fichier. Avant, il partageait celui des positions : chaque sauvegarde de position réécrivait tout ça.
    private val lib = ctx.getSharedPreferences("lib", 0)
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
     *  (position, signets) sont retrouvés par nom de fichier pour continuer à pointer sur les bons fichiers. */
    fun remapIndices(path: String, oldNames: List<String>, newNames: List<String>) {
        if (oldNames == newNames) return
        fun map(i: Int): Int? = oldNames.getOrNull(i)?.let { n -> newNames.indexOf(n) }?.takeIf { it >= 0 }
        load(path)?.let { s -> map(s.index)?.let { ni -> if (ni != s.index) save(path, ni, s.pos, s.speed, s.updated) } }
        val ms = marks(path)
        if (ms.isNotEmpty()) putMarks(path, ms.map { m -> m.copy(index = map(m.index) ?: m.index) })
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

    fun eqEnabled(): Boolean = p.getBoolean("eq_on", false)
    fun setEqEnabled(v: Boolean) = p.edit().putBoolean("eq_on", v).apply()
    fun eqLevels(): List<Short>? = p.getString("eq_levels", null)?.split(",")?.map { it.toShort() }
    fun setEqLevels(l: List<Short>) = p.edit().putString("eq_levels", l.joinToString(",")).apply()

    // ---- Cache de la bibliothèque : évite de tout re-scanner à chaque ouverture de l'appli.
    // Invalidé automatiquement si la racine change (clé incluant l'URI de la racine).
    private fun cacheKey() = "lib_cache_${root ?: ""}"
    fun cachedBooks(): List<Book>? {
        var s = lib.getString(cacheKey(), null)
        if (s == null) { // migration unique depuis l'ancien emplacement
            val old = p.getString(cacheKey(), null) ?: return null
            lib.edit().putString(cacheKey(), old).apply()
            p.edit().remove(cacheKey()).apply()
            s = old
        }
        return try {
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
    }
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
        lib.edit().putString(cacheKey(), a.toString()).apply()
        p.edit().remove(cacheKey()).apply()
    }

    /** Bibliothèque pour le service (Android Auto, notifications…) : le cache d'abord, un scan complet
     *  seulement s'il n'y en a pas encore. Avant, chaque requête d'un client média relançait un scan SAF
     *  complet en tâche de fond, en concurrence avec l'ouverture du livre dans l'appli. */
    fun library(): List<Book> = cachedBooks() ?: scan()

    // ---- Diagnostic Smart Player : rempli à chaque scan(), lu par l'UI juste après pour savoir
    // précisément où ça coince (fichier introuvable / illisible / déjà à jour) plutôt que de deviner.
    var lastSabpDiag: String? = null
        private set

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
        var sabpFound = 0
        var sabpParsed = 0
        var sabpImported = 0
        var sabpFinished = 0

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

        var foundDump: String? = null
        var milleniumDump: String? = null
        var firstDump: String? = null

        fun dumpFor(label: String, dirId: String, kids: List<Kid>, listed: String?, guessedId: String, errListed: String?, errGuessed: String?, bytes: ByteArray?) = buildString {
            appendLine("Livre : $label")
            appendLine("Dossier (id) : $dirId")
            appendLine("Fichiers vus par le scan (${kids.size}) :")
            kids.forEach { appendLine("  • ${it.name}${if (it.isDir) " [dossier]" else ""}") }
            appendLine("position.sabp.dat dans la liste ? ${if (listed != null) "oui" else "non"}")
            appendLine("URI devinée : $guessedId")
            appendLine("Ouverture via liste : ${if (listed == null) "n/a" else if (errListed == null && bytes != null) "OK" else errListed ?: "échec sans exception"}")
            appendLine("Ouverture via URI devinée : ${if (errGuessed == null && bytes != null && listed == null) "OK" else errGuessed ?: (if (listed != null) "non tentée (déjà trouvé via liste)" else "échec sans exception")}")
        }

        fun visit(dirId: String, path: String, label: String) {
            folders++
            val kids = children(dirId)
            val files = kids.filter { !it.isDir }
            val audio = files.filter { ext(it.name) in ext }.sortedWith(Comparator { a, b -> naturalCompare(a.name.lowercase(), b.name.lowercase()) })
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
                val guessedId = "$dirId/position.sabp.dat"
                val guessed = try { DocumentsContract.buildDocumentUriUsingTree(treeUri, guessedId).toString() } catch (e: Exception) { null }
                var bytes: ByteArray? = null
                var errListed: String? = null
                var errGuessed: String? = null
                if (listed != null && !hasJd) {
                    try { bytes = resolver.openInputStream(Uri.parse(listed))?.use { it.readBytes() } } catch (e: Exception) { errListed = e.toString() }
                }
                if (bytes == null && guessed != null && !hasJd) {
                    try { bytes = resolver.openInputStream(Uri.parse(guessed))?.use { it.readBytes() } } catch (e: Exception) { errGuessed = e.toString() }
                }
                // Trois vidages ciblés plutôt qu'un seul pris au hasard : le premier dossier où le fichier
                // est réellement présent dans la liste (pour voir un cas qui marche), le dossier "Millenium"
                // s'il existe (celui dont on a de vrais exemples de .dat), et sinon le tout premier livre,
                // en dernier recours.
                if (foundDump == null && listed != null) foundDump = dumpFor(label, dirId, kids, listed, guessedId, errListed, errGuessed, bytes)
                if (milleniumDump == null && path.contains("millenium", ignoreCase = true)) milleniumDump = dumpFor(label, dirId, kids, listed, guessedId, errListed, errGuessed, bytes)
                if (firstDump == null) firstDump = dumpFor(label, dirId, kids, listed, guessedId, errListed, errGuessed, bytes)
                if (bytes != null) {
                    sabpFound++
                    SabpImport.parsePosition(bytes)?.let { sp ->
                        sabpParsed++
                        // L'index brut de Smart Player ne correspond pas forcément à l'ordre alphabétique
                        // qu'on utilise : on retrouve le bon fichier par son nom (présent dans le .dat)
                        // quand c'est possible, et on ne retombe sur l'index brut qu'en dernier recours.
                        val byName = sp.fileName?.let { fn -> audio.indexOfFirst { it.name == fn } }?.takeIf { it >= 0 }
                        val resolvedIndex = (byName ?: sp.queueIndex).coerceIn(0, audio.lastIndex)
                        val cur = load(path)
                        val more = cur == null || resolvedIndex > cur.index || (resolvedIndex == cur.index && sp.fileMs > cur.pos)
                        if (more) { save(path, resolvedIndex, sp.fileMs, sp.speed); sabpImported++ }
                        if (sp.finished) { setFinished(path, true); sabpFinished++ }
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
        lastSabpDiag = "Smart Player : $sabpFound fichier(s) position.sabp.dat trouvé(s), " +
            "$sabpParsed décodé(s), $sabpImported position(s) importée(s), $sabpFinished marqué(s) lu(s)\n\n" +
            (foundDump ?: milleniumDump ?: firstDump ?: "(aucun livre trouvé pour le vidage détaillé)")
        return out
    }
}
