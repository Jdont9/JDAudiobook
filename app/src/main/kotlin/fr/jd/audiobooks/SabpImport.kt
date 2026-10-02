package fr.jd.audiobooks

import android.content.Context
import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/** Lit les fichiers laissés par Smart AudioBook Player pour reprendre l'historique. */
object SabpImport {

    // fileName (tiré du .dat lui-même) fait foi pour retrouver le bon fichier : l'index brut de Smart
    // Player ne correspond pas forcément à l'ordre alphabétique qu'on utilise pour trier les fichiers.
    data class SabpPos(val queueIndex: Int, val fileMs: Long, val speed: Float, val finished: Boolean, val fileName: String?)

    /**
     * position.sabp.dat : un objet Java sérialisé (classe BookDataBackup), un fichier par dossier de livre.
     * La disposition des 18 champs de cette classe n'a pas changé depuis des années ; on repère la fin de
     * l'en-tête de sérialisation (juste après le nom du dernier champ, "mSkipStartEndSettings") puis on lit
     * les champs primitifs à des offsets fixes plutôt que de réimplémenter un désérialiseur Java complet.
     */
    fun parsePosition(bytes: ByteArray): SabpPos? {
        try {
            val anchor = indexOf(bytes, "mSkipStartEndSettings".toByteArray(), 0)
            if (anchor < 0) return null
            val marker = indexOf(bytes, byteArrayOf(0x78, 0x70), anchor) // "xp" = fin de l'en-tête
            if (marker < 0) return null
            val off = marker + 2
            fun i32(o: Int) = ((bytes[o].toInt() and 0xff) shl 24) or ((bytes[o + 1].toInt() and 0xff) shl 16) or
                ((bytes[o + 2].toInt() and 0xff) shl 8) or (bytes[o + 3].toInt() and 0xff)
            val queuePos = i32(off)                 // mBookQueuePosition : quel fichier de la playlist
            val filePos = i32(off + 12)              // mFilePosition : position EN SECONDES dans ce fichier
            // (pas en millisecondes malgré la convention Android habituelle — vérifié contre l'appli réelle :
            // une valeur de 468 correspond à "7:48" affiché dans Smart Player, soit 468 secondes).
            val speedBits = i32(off + 24)            // mPlaybackSpeed : float
            val speed = Float.fromBits(speedBits)
            // mBookState (enum) est sérialisé juste après, comme une chaîne UTF précédée de sa longueur
            // sur 2 octets : on cherche directement ce motif plutôt que de désérialiser l'enum en entier.
            val finished = indexOf(bytes, byteArrayOf(0x00, 0x08) + "Finished".toByteArray(), off) >= 0
            val fileName = extractFileName(bytes, off + 45)
            return SabpPos(queuePos, filePos.toLong().coerceAtLeast(0) * 1000, if (speed in 0.1f..5f) speed else 1f, finished, fileName)
        } catch (e: Exception) { return null }
    }

    /**
     * Après les 45 octets de champs primitifs viennent, dans l'ordre : mBookCreationTime (Date),
     * mBookState (enum, chaîne), mCoverName (chaîne ou null), mEqualizerLevels (objet ou null),
     * mFileName (chaîne ou null) — c'est ce dernier qu'on veut. On repère la fin du bloc Date+enum via
     * le motif fixe "xpt" qui termine toujours la description de l'enum BookState, puis on avance champ
     * par champ en ne sachant lire que deux cas : une chaîne (0x74 + longueur sur 2 octets + texte) ou
     * un null (0x70). Dès qu'un champ ne correspond à aucun des deux, on abandonne proprement (position/
     * vitesse restent valables, seul le nom de fichier manque).
     */
    private fun extractFileName(bytes: ByteArray, from: Int): String? {
        try {
            val enumEnd = indexOf(bytes, "xpt".toByteArray(), from)
            if (enumEnd < 0) return null
            var p = enumEnd + 3
            fun readLen() = ((bytes[p].toInt() and 0xff) shl 8) or (bytes[p + 1].toInt() and 0xff)
            // chaîne de l'état (ex. "Started", "Finished")
            run { val len = readLen(); p += 2 + len }
            // mCoverName
            when (bytes[p].toInt() and 0xff) {
                0x74 -> { p += 1; val len = readLen(); p += 2 + len }
                0x70 -> p += 1
                else -> return null
            }
            // mEqualizerLevels
            when (bytes[p].toInt() and 0xff) {
                0x74 -> { p += 1; val len = readLen(); p += 2 + len }
                0x70 -> p += 1
                else -> return null
            }
            // mFileName
            return when (bytes[p].toInt() and 0xff) {
                0x74 -> { p += 1; val len = readLen(); String(bytes, p + 2, len, Charsets.UTF_8) }
                else -> null
            }
        } catch (e: Exception) { return null }
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray, from: Int): Int {
        outer@ for (i in from..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    /** statistics.xml : <book><path>...</path><time>YYYY-MM secondes</time>...</book> */
    fun parseStatistics(input: InputStream): Map<String, List<Pair<String, Long>>> {
        val out = LinkedHashMap<String, MutableList<Pair<String, Long>>>()
        val parser = Xml.newPullParser()
        parser.setInput(input, "UTF-8")
        var path: String? = null
        var text = ""
        var ev = parser.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> { if (parser.name == "book") path = null; text = "" }
                XmlPullParser.TEXT -> text += parser.text
                XmlPullParser.END_TAG -> when (parser.name) {
                    "path" -> path = text.trim()
                    "time" -> {
                        val t = text.trim().split(" ", limit = 2)
                        if (t.size == 2 && path != null) {
                            val sec = t[1].toLongOrNull()
                            if (sec != null) out.getOrPut(path!!) { mutableListOf() }.add(t[0] to sec)
                        }
                    }
                }
            }
            ev = parser.next()
        }
        return out
    }

    private fun norm(s: String) = s.trim().trimStart('/').replace('\\', '/').lowercase()

    /** Importe statistics.xml : associe chaque <path> à un livre scanné (comparaison exacte, puis normalisée), écrit les totaux mensuels. */
    fun importStatistics(ctx: Context, store: Store, uri: Uri, books: List<Book>): Pair<Int, Int> {
        val parsed = ctx.contentResolver.openInputStream(uri)?.use { parseStatistics(it) } ?: return 0 to 0
        val byPath = books.associateBy { it.path }
        val byNorm = books.associateBy { norm(it.path) }
        var matched = 0
        parsed.forEach { (path, months) ->
            val bk = byPath[path] ?: byNorm[norm(path)]
            if (bk != null) {
                matched++
                months.forEach { (ym, sec) -> store.importMonthlyStat(bk.path, ym, sec * 1000) }
            }
        }
        return matched to parsed.size
    }
}
