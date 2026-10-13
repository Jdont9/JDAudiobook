package fr.jd.audiobooks

import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Sauvegarde chiffrée de tes données (positions, signets, réglages, statistiques) dans un fichier que tu gardes où tu veux,
 * sans passer par le cloud Android.
 *
 * Fichier = en-tête (« JDB1 », nombre d'itérations, sel de 16 octets, vecteur d'initialisation de 12 octets) puis le contenu
 * chiffré en AES-256-GCM. La clé vient du mot de passe par PBKDF2-HMAC-SHA256 (600 000 itérations). L'en-tête est authentifié :
 * le moindre octet modifié, ou un mauvais mot de passe, fait échouer le déchiffrement. Rien n'est jamais envoyé sur Internet.
 *
 * À la restauration, le contenu n'est PAS cru sur parole : seules les clés connues sont acceptées, avec le bon type, des
 * valeurs bornées et des tailles plafonnées (un fichier forgé ne peut ni injecter des réglages inconnus ni saturer la mémoire).
 */
object Backup {
    private const val MAGIC = "JDB1"
    private const val HEADER = 4 + 4 + 16 + 12
    const val ITERATIONS = 600_000
    const val MIN_ITERATIONS = 100_000      // en dessous : refusé (fichier affaibli)
    const val MAX_ITERATIONS = 5_000_000    // au-dessus : refusé (fichier forgé pour bloquer le téléphone)
    const val MAX_FILE_BYTES = 30_000_000   // fichier chiffré
    const val MAX_PLAIN_BYTES = 25_000_000  // contenu déchiffré
    const val MIN_PASSWORD = 8
    private const val MAX_ENTRIES = 200_000
    private const val MAX_KEY = 600
    private const val MAX_VALUE = 300_000

    // ---------- Chiffrement ----------

    private fun derive(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally { spec.clearPassword() }
    }

    fun encrypt(plain: ByteArray, password: CharArray, iterations: Int = ITERATIONS): ByteArray {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val header = ByteBuffer.allocate(HEADER).put(MAGIC.toByteArray(Charsets.US_ASCII)).putInt(iterations).put(salt).put(iv).array()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, derive(password, salt, iterations), GCMParameterSpec(128, iv))
        c.updateAAD(header)
        return header + c.doFinal(plain)
    }

    /** Contenu déchiffré, ou null : mauvais mot de passe, fichier modifié ou abîmé, ou qui n'est pas une sauvegarde. */
    fun decrypt(data: ByteArray, password: CharArray): ByteArray? {
        if (data.size < HEADER + 16 || data.size > MAX_FILE_BYTES) return null
        val b = ByteBuffer.wrap(data)
        val magic = ByteArray(4).also { b.get(it) }
        if (String(magic, Charsets.US_ASCII) != MAGIC) return null
        val iterations = b.int
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) return null
        val salt = ByteArray(16).also { b.get(it) }
        val iv = ByteArray(12).also { b.get(it) }
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, derive(password, salt, iterations), GCMParameterSpec(128, iv))
            c.updateAAD(data, 0, HEADER)
            c.doFinal(data, HEADER, data.size - HEADER).takeIf { it.size <= MAX_PLAIN_BYTES }
        } catch (e: GeneralSecurityException) { null }
    }

    // ---------- Contenu ----------

    private enum class T { S, I, B }

    /** Clés acceptées (liste blanche) et leur type : tout le reste est ignoré, à l'export comme à l'import. */
    private fun typeOf(store: String, key: String): T? = when (store) {
        "p" -> when {
            key.startsWith("fin_") || key.startsWith("f_") -> T.B
            key.startsWith("boost_") || key == "skip_s" || key == "sleep_rw" -> T.I
            key.startsWith("bm_") || key.startsWith("eqp_") || key == "eqc" -> T.S
            else -> null
        }
        "pos" -> if (key.startsWith("s_") || key == "last_book") T.S else null
        "stats" -> if (key.startsWith("t|") || key.startsWith("ti|")) T.S else null
        else -> null
    }

    /** « wall,content » (statistiques) -> deux compteurs ; valeur illisible = 0. */
    internal fun pair(raw: String?): LongArray {
        val x = raw?.split(",")
        return longArrayOf(x?.getOrNull(0)?.toLongOrNull() ?: 0L, x?.getOrNull(1)?.toLongOrNull() ?: 0L)
    }

    private fun encodeValue(t: T, v: Any?): String? = when {
        t == T.S && v is String -> "s:$v"
        t == T.I && v is Int -> "i:$v"
        t == T.B && v is Boolean -> "b:$v"
        else -> null
    }

    /** Instantané des préférences (Store.backupSnapshot) -> JSON clair (à chiffrer ensuite). Le dossier choisi n'en fait pas partie. */
    fun toJson(snapshot: Map<String, Map<String, Any?>>, now: Long = System.currentTimeMillis()): ByteArray {
        val root = JSONObject().put("app", "JD Audiobook Reader").put("format", 1).put("created", now)
        for ((store, map) in snapshot) {
            val o = JSONObject()
            for ((k, v) in map) {
                val t = typeOf(store, k) ?: continue
                encodeValue(t, v)?.let { o.put(k, it) }
            }
            root.put(store, o)
        }
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    class Restored(val p: Map<String, Any>, val pos: Map<String, Any>, val stats: Map<String, Any>) {
        val count get() = p.size + pos.size + stats.size
    }

    /** JSON clair -> données validées ; null si ce n'est pas une sauvegarde de cette appli. Les entrées invalides sont ignorées. */
    fun parse(plain: ByteArray): Restored? = try {
        val root = JSONObject(String(plain, Charsets.UTF_8))
        if (root.optInt("format", 0) != 1) null
        else {
            var total = 0
            fun section(store: String): Map<String, Any> {
                val o = root.optJSONObject(store) ?: return emptyMap()
                val out = HashMap<String, Any>()
                val keys = o.keys()
                while (keys.hasNext() && total < MAX_ENTRIES) {
                    val k = keys.next()
                    total++
                    if (k.length > MAX_KEY) continue
                    val t = typeOf(store, k) ?: continue
                    val raw = o.optString(k, "")
                    if (raw.length < 2 || raw.length > MAX_VALUE || raw[1] != ':') continue
                    val body = raw.substring(2)
                    val value: Any? = when (t) {
                        T.S -> if (raw[0] == 's') validString(store, k, body) else null
                        T.I -> if (raw[0] == 'i') body.toIntOrNull()?.let { clampInt(k, it) } else null
                        T.B -> if (raw[0] == 'b') body.toBooleanStrictOrNull() else null
                    }
                    if (value != null) out[k] = value
                }
                return out
            }
            Restored(section("p"), section("pos"), section("stats"))
        }
    } catch (e: Exception) { logw("sauvegarde illisible", e); null }

    private fun clampInt(key: String, v: Int): Int = when {
        key.startsWith("boost_") -> v.coerceIn(0, 24)
        key == "skip_s" -> v.coerceIn(5, 300)
        key == "sleep_rw" -> v.coerceIn(0, 30)
        else -> v
    }

    private val statRe = Regex("\\d{1,15},\\d{1,15}")

    private fun validString(store: String, key: String, v: String): String? = when {
        store == "pos" && key.startsWith("s_") -> v.takeIf { parseSaved(it) != null }
        store == "stats" -> v.takeIf { statRe.matches(it) }
        store == "p" && key.startsWith("bm_") -> BookmarkCodec.encode(BookmarkCodec.decode(v)) // normalisé et borné
        else -> v
    }

    // ---------- Assemblage ----------

    fun export(snapshot: Map<String, Map<String, Any?>>, password: CharArray): ByteArray = encrypt(toJson(snapshot), password)

    /** Fichier chiffré -> données validées ; null si mot de passe faux, fichier abîmé ou pas une sauvegarde. */
    fun import(file: ByteArray, password: CharArray): Restored? = decrypt(file, password)?.let { parse(it) }
}
