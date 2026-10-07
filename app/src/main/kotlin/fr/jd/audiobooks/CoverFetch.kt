package fr.jd.audiobooks

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer

/** Recherche de pochettes sur Internet : iTunes (livres audio), puis Google Books, puis Open Library. */
object CoverFetch {
    class Found(val jpeg: ByteArray, val source: String)
    private class Cand(val title: String, val url: String)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun http(url: String): ByteArray? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 8000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "JDAudiobook/1.0 (Android)")
        }
        try { if (c.responseCode in 200..299) c.inputStream.use { it.readBytes() } else null } finally { c.disconnect() }
    } catch (e: Exception) { null }

    private fun json(url: String): JSONObject? = try { http(url)?.let { JSONObject(String(it, Charsets.UTF_8)) } } catch (e: Exception) { null }

    private fun itunes(q: String): List<Cand> = try {
        val a = json("https://itunes.apple.com/search?term=${enc(q)}&media=audiobook&country=fr&limit=8")?.optJSONArray("results")
        (0 until (a?.length() ?: 0)).mapNotNull { i ->
            val o = a!!.getJSONObject(i)
            val u = o.optString("artworkUrl100")
            if (u.isEmpty()) null else Cand(o.optString("collectionName"), u.replace(Regex("\\d+x\\d+bb"), "800x800bb"))
        }
    } catch (e: Exception) { emptyList() }

    private fun google(q: String): List<Cand> = try {
        val a = json("https://www.googleapis.com/books/v1/volumes?q=${enc(q)}&maxResults=8&printType=books")?.optJSONArray("items")
        (0 until (a?.length() ?: 0)).mapNotNull { i ->
            val v = a!!.getJSONObject(i).optJSONObject("volumeInfo") ?: return@mapNotNull null
            val u = v.optJSONObject("imageLinks")?.optString("thumbnail").orEmpty()
            if (u.isEmpty()) null else Cand(v.optString("title"), u.replace("http://", "https://").replace("&edge=curl", ""))
        }
    } catch (e: Exception) { emptyList() }

    private fun openLibrary(q: String): List<Cand> = try {
        val a = json("https://openlibrary.org/search.json?q=${enc(q)}&limit=8&fields=title,cover_i")?.optJSONArray("docs")
        (0 until (a?.length() ?: 0)).mapNotNull { i ->
            val o = a!!.getJSONObject(i)
            if (!o.has("cover_i")) null else Cand(o.optString("title"), "https://covers.openlibrary.org/b/id/${o.getLong("cover_i")}-L.jpg?default=false")
        }
    } catch (e: Exception) { emptyList() }

    private val stop = setOf("le", "la", "les", "un", "une", "des", "de", "du", "d", "l", "et", "the", "of", "a", "au", "aux", "en", "livre", "audio", "tome", "vol", "volume")

    private fun tokens(s: String): Set<String> =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ").trim().split(" ").filter { it.isNotEmpty() && it !in stop }.toSet()

    /** Part des mots du titre cherché qui se retrouvent dans le titre trouvé (0..1). */
    private fun score(query: String, title: String): Double {
        val q = tokens(query); if (q.isEmpty()) return 0.0
        return q.intersect(tokens(title)).size.toDouble() / q.size
    }

    private fun cleanTitle(name: String) = name.replace('_', ' ')
        .replace(Regex("\\[[^\\]]*\\]"), " ")
        .replace(Regex("(?i)\\b(livre audio|audiobook|mp3|m4b|unabridged)\\b"), " ")
        .replace(Regex("\\s+"), " ").trim()

    /** Télécharge l'image, vérifie qu'elle est lisible et assez grande, la recompresse en JPEG. */
    private fun fetchImage(url: String): ByteArray? {
        val raw = http(url) ?: return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, o)
        if (minOf(o.outWidth, o.outHeight) < 100) return null
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / s > 1024) s *= 2
        val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    /** Cherche la pochette d'un livre ; null si rien de suffisamment proche n'est trouvé. */
    fun find(bk: Book): Found? {
        val title = cleanTitle(bk.name)
        val parent = bk.path.substringBeforeLast('/', "").substringAfterLast('/')
        val queries = listOfNotNull(title, if (parent.isNotEmpty()) cleanTitle("$parent $title") else null)
        val sources = listOf<Pair<String, (String) -> List<Cand>>>("iTunes" to ::itunes, "Google Books" to ::google, "Open Library" to ::openLibrary)
        for (q in queries) for ((label, search) in sources) {
            val best = search(q).map { it to score(title, it.title) }.filter { it.second >= 0.5 }.maxByOrNull { it.second }?.first ?: continue
            fetchImage(best.url)?.let { return Found(it, label) }
        }
        return null
    }

    /** Enregistre la pochette : toujours dans le stockage de l'appli, et si possible aussi (cover.jpg) dans
     *  le dossier du livre. Renvoie l'URI du fichier écrit dans le dossier, ou null si l'écriture a échoué. */
    fun save(ctx: Context, store: Store, bk: Book, jpeg: ByteArray): String? {
        try { Covers.localFile(ctx, bk.path).writeBytes(jpeg) } catch (e: Exception) { }
        var uri: String? = null
        val root = store.root; val dir = bk.dir
        if (root != null && dir != null) try {
            val tree = Uri.parse(root)
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, dir)
            val doc = DocumentsContract.createDocument(ctx.contentResolver, parent, "image/jpeg", "cover.jpg")
            if (doc != null) {
                ctx.contentResolver.openOutputStream(doc, "w")?.use { it.write(jpeg) }
                uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getDocumentId(doc)).toString()
            }
        } catch (e: Exception) { uri = null }
        if (uri != null) store.updateCover(bk.path, uri)
        Covers.invalidate(bk.path)
        return uri
    }
}

@Composable
fun MissingCoversDialog(books: List<Book>, store: Store, onCover: (String, String?) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var missing by remember { mutableStateOf<List<Book>?>(null) }
    var checked by remember { mutableStateOf(0) }
    var running by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var index by remember { mutableStateOf(0) }
    var found by remember { mutableStateOf(0) }
    val log = remember { mutableStateListOf<String>() }

    // Analyse : quels livres n'ont ni image dans leur dossier, ni pochette intégrée, ni pochette déjà téléchargée ?
    LaunchedEffect(Unit) {
        missing = withContext(Dispatchers.IO) {
            books.filter { bk -> (!Covers.hasCover(ctx, bk)).also { checked++ } }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pochettes manquantes") },
        text = {
            Column {
                val m = missing
                when {
                    m == null -> {
                        Text("Analyse… $checked/${books.size}", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    m.isEmpty() -> Text("Tous les livres ont déjà une pochette.", style = MaterialTheme.typography.bodyMedium)
                    else -> {
                        Text(
                            when {
                                done -> "Terminé : $found pochette(s) trouvée(s), ${m.size - found} introuvable(s)."
                                running -> "Recherche ${index + 1}/${m.size} · ${m[index.coerceIn(0, m.lastIndex)].name}"
                                else -> "${m.size} livre(s) sur ${books.size} sans pochette. La recherche se fait sur Internet (iTunes, Google Books, Open Library) d'après le nom du dossier ; l'image est enregistrée en cover.jpg dans le dossier du livre."
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (running) {
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(progress = { index.toFloat() / m.size }, modifier = Modifier.fillMaxWidth())
                        }
                        if (log.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            LazyColumn(Modifier.heightIn(max = 260.dp)) {
                                items(log.asReversed()) { Text(it, Modifier.padding(vertical = 2.dp), style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val m = missing
            if (m != null && m.isNotEmpty() && !running && !done) TextButton({
                running = true
                scope.launch {
                    m.forEachIndexed { i, bk ->
                        index = i
                        val r = withContext(Dispatchers.IO) { CoverFetch.find(bk) }
                        if (r == null) log += "✗ ${bk.name} : introuvable"
                        else {
                            val uri = withContext(Dispatchers.IO) { CoverFetch.save(ctx, store, bk, r.jpeg) }
                            found++
                            onCover(bk.path, uri)
                            log += "✓ ${bk.name} (${r.source})" + if (uri == null) " · gardée dans l'appli seulement" else ""
                        }
                    }
                    running = false; done = true
                }
            }) { Text("Rechercher") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Fermer") } }
    )
}
