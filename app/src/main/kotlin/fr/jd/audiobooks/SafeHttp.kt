package fr.jd.audiobooks

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.HttpURLConnection
import java.net.URL

/**
 * Requêtes HTTP(S) sortantes de l'appli (pochettes, recherche) avec quelques garde-fous :
 *  - HTTPS uniquement (jamais de clair) ;
 *  - jamais vers une adresse locale ou privée (loopback, réseau local, lien-local, CGNAT, fc00::/7…) : un lien collé ou une
 *    redirection ne peut pas faire sonder le routeur ou un serveur du réseau local ;
 *  - redirections suivies à la main (5 au plus), chacune revérifiée ;
 *  - taille de la réponse plafonnée.
 * Limite connue : l'adresse est vérifiée avant la connexion, pas pendant (réassociation DNS théorique) ; l'impact reste faible
 * puisque la réponse n'est jamais montrée, seulement décodée comme image ou comme JSON.
 */
object SafeHttp {
    class Resp(val code: Int, val body: ByteArray)

    private const val USER_AGENT = "JDAudiobook/1.5 (Android)"

    /** Vrai si l'adresse n'est pas joignable depuis Internet : à refuser. */
    internal fun isBlocked(a: InetAddress): Boolean {
        if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return true
        val b = a.address
        return when (a) {
            is Inet6Address -> (b[0].toInt() and 0xFE) == 0xFC // fc00::/7 (adresses locales uniques)
            is Inet4Address -> {
                val x = b[0].toInt() and 0xFF; val y = b[1].toInt() and 0xFF
                x == 0 || (x == 100 && y in 64..127) || (x == 198 && y in 18..19) || x >= 240 // « ce réseau », CGNAT, tests, réservé
            }
            else -> true
        }
    }

    /** L'URL est-elle autorisée ? https, un hôte, et aucune de ses adresses n'est privée. */
    internal fun allowed(u: URL): Boolean {
        if (u.protocol != "https" || u.host.isNullOrBlank() || u.userInfo != null) return false
        return try { InetAddress.getAllByName(u.host).let { l -> l.isNotEmpty() && l.none { isBlocked(it) } } }
        catch (e: Exception) { false }
    }

    /** GET (ou POST si [body] est fourni) ; null en cas d'erreur, de refus, de réponse non 2xx ou trop grosse. À appeler hors du thread principal. */
    fun request(
        url: String, maxBytes: Int, body: ByteArray? = null, headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 8000, maxRedirects: Int = 5
    ): Resp? {
        var cur = url
        for (hop in 0..maxRedirects) {
            val u = try { URL(cur) } catch (e: Exception) { return null }
            if (!allowed(u)) { logw("requête refusée (HTTPS public uniquement) : ${u.host}"); return null }
            val c = u.openConnection() as HttpURLConnection
            try {
                c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs; c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", USER_AGENT)
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                if (body != null) { c.requestMethod = "POST"; c.doOutput = true; c.outputStream.use { it.write(body) } }
                val code = c.responseCode
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    if (body != null) return null // on ne rejoue pas un POST ailleurs
                    val loc = c.getHeaderField("Location") ?: return null
                    cur = URL(u, loc).toString()
                    continue
                }
                if (code !in 200..299) return null
                if (c.contentLengthLong > maxBytes) return null
                val bytes = c.inputStream.use { it.readCapped(maxBytes) } ?: return null
                return Resp(code, bytes)
            } catch (e: Exception) {
                logw("requête HTTP échouée", e); return null
            } finally { c.disconnect() }
        }
        return null // trop de redirections
    }
}
