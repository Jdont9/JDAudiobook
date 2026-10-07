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
    private class Cand(val title: String, val url: String, val alt: String? = null)

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

    /** Audible : API publique du catalogue (sans compte). */
    private fun audible(host: String, q: String): List<Cand> = try {
        val a = json("https://$host/1.0/catalog/products?keywords=${enc(q)}&num_results=8&products_sort_by=Relevance&response_groups=media,product_attrs")?.optJSONArray("products")
        (0 until (a?.length() ?: 0)).mapNotNull { i ->
            val o = a!!.getJSONObject(i)
            val imgs = o.optJSONObject("product_images") ?: return@mapNotNull null
            val best = imgs.keys().asSequence().maxByOrNull { it.toIntOrNull() ?: 0 } ?: return@mapNotNull null
            val u = imgs.optString(best)
            if (u.isEmpty()) null else Cand(o.optString("title"), u)
        }
    } catch (e: Exception) { emptyList() }

    private fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k)

    private fun postJson(url: String, body: String): JSONObject? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 10000; readTimeout = 10000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("Origin", "https://www.audiolib.fr")
            setRequestProperty("Referer", "https://www.audiolib.fr/")
            setRequestProperty("User-Agent", "JDAudiobook/1.0 (Android)")
        }
        try {
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (c.responseCode in 200..299) JSONObject(String(c.inputStream.use { it.readBytes() }, Charsets.UTF_8)) else null
        } finally { c.disconnect() }
    } catch (e: Exception) { null }

    /** Audiolib : même recherche que le site (Elasticsearch de Hachette), version allégée. Non officielle : peut changer. */
    private fun audiolib(q: String): List<Cand> = try {
        val qq = JSONObject.quote(q)
        fun m(field: String, boost: Int, prefix: Boolean) =
            """{"constant_score":{"filter":{"${if (prefix) "match_phrase_prefix" else "match"}":{"$field":$qq}},"boost":$boost}}"""
        val should = listOf(
            m("product__titre_de_couverture", 21, false), m("product__titre_de_couverture", 20, true),
            m("product__series", 13, false), m("term_serie_name", 13, false),
            m("product__intervenant__principal_full_name", 5, false)
        ).joinToString(",")
        val body = """{"index":"elasticsearch_index_hlrwf_prd_index_4","source":{"query":{"bool":{"must":[""" +
            """{"bool":{"should":[$should]}},{"terms":{"bundle":["hl_product"]}},""" +
            """{"multi_match":{"query":"4","fields":["site_id","field_sid"]}},{"multi_match":{"query":"fr","fields":["_language"]}}],""" +
            """"must_not":[{"term":{"node__field_restrict_access":true}}]}},"size":10,"sort":["_score"]}}"""
        val hits = postJson("https://api.hachette.fr/search", body)?.optJSONObject("hits")?.optJSONArray("hits")
        (0 until (hits?.length() ?: 0)).mapNotNull { i ->
            val src = hits!!.getJSONObject(i).optJSONObject("_source") ?: return@mapNotNull null
            val d = JSONObject(src.optJSONArray("decoupled_render")?.optString(0) ?: return@mapNotNull null)
            val title = listOf(d.str("titre_de_couverture"), d.str("libelle_de_tomaison"), d.str("serie_label")).filter { it.isNotEmpty() }.distinct().joinToString(" ")
            val uri = d.optJSONObject("image_de_couverture")?.str("uri").orEmpty().substringBefore('?')
            val hd = d.str("image_de_couverture_hd").takeIf { it.startsWith("http") }
            val small = if (uri.isNotEmpty()) "https://media.hachette.fr/fit-in/500x500/$uri" else null
            val main = hd ?: small ?: return@mapNotNull null
            Cand(title, main, if (hd != null) small else null)
        }
    } catch (e: Exception) { emptyList() }

    /** Image déclarée par une page web (balise og:image), ou null. */
    private fun metaImage(html: String): String? {
        val tag = Regex("<meta[^>]+(?:property|name)=[\"']og:image[\"'][^>]*>").find(html)?.value ?: return null
        val u = Regex("content=[\"']([^\"']+)[\"']").find(tag)?.groupValues?.get(1)?.replace("&amp;", "&") ?: return null
        return if (u.startsWith("//")) "https:$u" else u
    }

    private val stop = setOf("le", "la", "les", "un", "une", "des", "de", "du", "d", "l", "et", "the", "of", "a", "au", "aux", "en", "livre", "audio", "tome", "vol", "volume")

    private fun tokens(s: String): Set<String> =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ").trim().split(" ").filter { it.isNotEmpty() && it !in stop }
            .map { if (it.all(Char::isDigit)) it.trimStart('0').ifEmpty { "0" } else it }.toSet()

    /** Part des mots du titre cherché qui se retrouvent dans le titre trouvé (0..1). */
    private fun score(query: String, title: String): Double {
        val q = tokens(query); if (q.isEmpty()) return 0.0
        val t = tokens(title)
        val base = q.intersect(t).size.toDouble() / q.size
        // un numéro de tome demandé mais absent du résultat : probablement un autre volume
        return if (q.any { n -> n.all(Char::isDigit) && n !in t }) base * 0.6 else base
    }

    private fun cleanTitle(name: String) = name.replace('_', ' ')
        .replace(Regex("\\[[^\\]]*\\]"), " ")
        .replace(Regex("(?i)\\b(livre audio|audiobook|mp3|m4b|unabridged)\\b"), " ")
        .replace(Regex("\\s+"), " ").trim()

    /** Télécharge l'image, vérifie qu'elle est lisible et assez grande, la recompresse en JPEG. */
    private fun fetchImage(url: String): ByteArray? = http(url)?.let { toJpeg(it) }

    private fun toJpeg(raw: ByteArray): ByteArray? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, o)
        if (minOf(o.outWidth, o.outHeight) < 100) return null
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / s > 1024) s *= 2
        val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    /** Pochette depuis un lien collé à la main : image directe, ou page web (Audiolib, Audible, Babelio…) qui déclare son image (og:image). */
    fun fromUrl(raw: String): Found? {
        val url = raw.trim().takeIf { it.startsWith("http") } ?: return null
        val bytes = http(url) ?: return null
        toJpeg(bytes)?.let { return Found(it, "lien") }
        val img = metaImage(String(bytes, Charsets.UTF_8)) ?: return null
        return fetchImage(img)?.let { Found(it, "lien") }
    }

    /** Cherche la pochette d'un livre ; null si rien de suffisamment proche n'est trouvé. */
    fun find(bk: Book): Found? {
        val title = cleanTitle(bk.name)
        val parent = bk.path.substringBeforeLast('/', "").substringAfterLast('/')
        val queries = listOfNotNull(title, if (parent.isNotEmpty()) cleanTitle("$parent $title") else null)
        val sources = listOf<Pair<String, (String) -> List<Cand>>>(
            "iTunes" to ::itunes,
            "Audible" to { q -> audible("api.audible.fr", q) },
            "Audiolib" to ::audiolib,
            "Audible (US)" to { q -> audible("api.audible.com", q) },
            "Google Books" to ::google,
            "Open Library" to ::openLibrary
        )
        for (q in queries) for ((label, search) in sources) {
            val best = search(q).map { it to score(title, it.title) }.filter { it.second >= 0.5 }.maxByOrNull { it.second }?.first ?: continue
            (fetchImage(best.url) ?: best.alt?.let { fetchImage(it) })?.let { return Found(it, label) }
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

private class Entry(val bk: Book, val text: String, val ok: Boolean)

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
    val log = remember { mutableStateListOf<Entry>() }
    var pasteFor by remember { mutableStateOf<Book?>(null) }
    var link by remember { mutableStateOf("") }
    var linkErr by remember { mutableStateOf<String?>(null) }
    var linkBusy by remember { mutableStateOf(false) }

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
                                else -> "${m.size} livre(s) sur ${books.size} sans pochette. La recherche se fait sur Internet (iTunes, Audible, Audiolib, Google Books, Open Library) d'après le nom du dossier ; l'image est enregistrée en cover.jpg dans le dossier du livre."
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
                                items(log.asReversed()) { e ->
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                        Text(e.text, Modifier.weight(1f).padding(vertical = 2.dp), style = MaterialTheme.typography.bodySmall)
                                        if (!e.ok) TextButton({ pasteFor = e.bk; link = ""; linkErr = null }) { Text("Lien") }
                                    }
                                }
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
                        if (r == null) log += Entry(bk, "✗ ${bk.name} : introuvable", false)
                        else {
                            val uri = withContext(Dispatchers.IO) { CoverFetch.save(ctx, store, bk, r.jpeg) }
                            found++
                            onCover(bk.path, uri)
                            log += Entry(bk, "✓ ${bk.name} (${r.source})" + if (uri == null) " · gardée dans l'appli seulement" else "", true)
                        }
                    }
                    running = false; done = true
                }
            }) { Text("Rechercher") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Fermer") } }
    )

    pasteFor?.let { bk ->
        AlertDialog(
            onDismissRequest = { if (!linkBusy) pasteFor = null },
            title = { Text("Coller un lien") },
            text = {
                Column {
                    Text(
                        "Adresse de la page du livre (Audiolib, Audible, Babelio…) ou d'une image, pour « ${bk.name} ».",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        link, { link = it; linkErr = null }, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true,
                        isError = linkErr != null, supportingText = linkErr?.let { { Text(it) } }
                    )
                    if (linkBusy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(enabled = !linkBusy && link.isNotBlank(), onClick = {
                    linkBusy = true
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { CoverFetch.fromUrl(link) }
                        if (r == null) linkErr = "Aucune image trouvée sur ce lien."
                        else {
                            val uri = withContext(Dispatchers.IO) { CoverFetch.save(ctx, store, bk, r.jpeg) }
                            found++
                            onCover(bk.path, uri)
                            val i = log.indexOfFirst { it.bk.path == bk.path }
                            val e = Entry(bk, "✓ ${bk.name} (lien)" + if (uri == null) " · gardée dans l'appli seulement" else "", true)
                            if (i >= 0) log[i] = e else log += e
                            pasteFor = null
                        }
                        linkBusy = false
                    }
                }) { Text("Valider") }
            },
            dismissButton = { TextButton({ pasteFor = null }, enabled = !linkBusy) { Text("Annuler") } }
        )
    }
}
