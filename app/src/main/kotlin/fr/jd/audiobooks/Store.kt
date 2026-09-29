package fr.jd.audiobooks

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.*
import java.text.SimpleDateFormat
import java.util.*

// path : chemin relatif à la racine choisie (ex. "Millenium/Millenium 1 - ..."), identifiant stable du livre,
// aligné sur le format utilisé par Smart AudioBook Player pour pouvoir croiser ses données.
data class Book(val path: String, val name: String, val uris: List<String>, val names: List<String>, val cover: String? = null)
data class Mark(val index: Int, val pos: Long, val label: String)
data class Saved(val index: Int, val pos: Long, val speed: Float)
data class Stat(val book: String, val day: String, val wall: Long, val content: Long)

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

    // Parcourt l'arborescence à profondeur illimitée : tout dossier qui contient directement des fichiers
    // audio est un livre (même logique que Smart AudioBook Player), ce qui permet de retrouver les mêmes
    // chemins relatifs que dans son statistics.xml.
    fun scan(): List<Book> {
        val r = DocumentFile.fromTreeUri(ctx, Uri.parse(root ?: return emptyList())) ?: return emptyList()
        val ext = setOf("mp3", "m4b", "m4a", "ogg", "opus", "flac", "wav")
        val img = setOf("jpg", "jpeg", "png", "webp")
        fun DocumentFile.ext() = name?.substringAfterLast('.', "")?.lowercase()
        val out = mutableListOf<Book>()

        fun visit(d: DocumentFile, path: String, label: String) {
            val kids = d.listFiles()
            val files = kids.filter { it.isFile }
            val audio = files.filter { it.ext() in ext }.sortedBy { it.name?.lowercase() }
            if (audio.isNotEmpty()) {
                val im = files.filter { it.ext() in img }
                val cv = (im.firstOrNull { f -> listOf("cover", "folder", "front").any { f.name?.lowercase()?.contains(it) == true } }
                    ?: im.firstOrNull())?.uri?.toString()
                val bk = Book(path, label, audio.map { it.uri.toString() }, audio.map { it.name ?: "" }, cv)
                out += bk
                // Reprise automatique depuis Smart AudioBook Player : n'importe que si l'appli n'a pas encore
                // sa propre position pour ce livre, pour ne jamais écraser une progression déjà faite ici.
                if (!hasSaved(path)) {
                    files.firstOrNull { it.name == "position_sabp.dat" }?.let { f ->
                        val bytes = try { ctx.contentResolver.openInputStream(f.uri)?.use { it.readBytes() } } catch (e: Exception) { null }
                        bytes?.let { SabpImport.parsePosition(it) }?.let { sp ->
                            save(path, sp.queueIndex.coerceIn(0, audio.lastIndex), sp.fileMs, sp.speed)
                        }
                    }
                }
            }
            kids.filter { it.isDirectory }.sortedBy { it.name?.lowercase() }.forEach { sub ->
                visit(sub, if (path.isEmpty()) (sub.name ?: "?") else "$path/${sub.name ?: "?"}", sub.name ?: "?")
            }
        }
        visit(r, "", r.name ?: "Racine")
        return out
    }
}
