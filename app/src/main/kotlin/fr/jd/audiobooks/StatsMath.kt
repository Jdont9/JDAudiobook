package fr.jd.audiobooks

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Calculs des statistiques d'écoute (purs, testables) : séries de jours, calendrier de chaleur, export CSV. */
object StatsMath {
    private fun fmt() = SimpleDateFormat("yyyyMMdd", Locale.US)

    /** Jour « yyyyMMdd » décalé de [delta] jours (négatif = passé). */
    fun dayOffset(from: Date, delta: Int): String =
        fmt().format(Calendar.getInstance().apply { time = from; add(Calendar.DAY_OF_YEAR, delta) }.time)

    /**
     * Série en cours : nombre de jours consécutifs avec de l'écoute, en finissant aujourd'hui ; si rien aujourd'hui, en finissant hier
     * (la série n'est pas perdue tant que la journée n'est pas finie). [listened] = jours « yyyyMMdd » avec de l'écoute.
     */
    fun currentStreak(listened: Set<String>, today: Date): Int {
        var d = if (dayOffset(today, 0) in listened) 0 else -1
        var n = 0
        while (dayOffset(today, d) in listened) { n++; d-- }
        return n
    }

    /** Plus longue série de jours consécutifs. */
    fun longestStreak(listened: Set<String>): Int {
        if (listened.isEmpty()) return 0
        val f = fmt()
        val days = listened.mapNotNull { runCatching { f.parse(it)?.time }.getOrNull() }.sorted()
        var best = 0; var run = 0; var prev = Long.MIN_VALUE
        for (t in days) {
            run = if (prev != Long.MIN_VALUE && Math.round((t - prev) / 86_400_000.0) == 1L) run + 1 else 1
            best = maxOf(best, run); prev = t
        }
        return best
    }

    /** Niveau d'intensité 0..4 d'un jour (0 = rien) d'après le temps écouté, relatif au meilleur jour affiché. */
    fun level(ms: Long, max: Long): Int = when {
        ms <= 0 || max <= 0 -> 0
        else -> (1 + (ms * 3 / max).toInt()).coerceIn(1, 4)
    }

    /** Export CSV : livre, jour, temps réel écouté (s), contenu écouté (s). Cellules protégées contre l'injection de formules. */
    fun csv(stats: List<Stat>): String = buildString {
        append("book,day,listened_seconds,content_seconds\n")
        stats.sortedWith(compareBy({ it.day }, { it.book })).forEach {
            append(cell(it.book)).append(',').append(it.day).append(',').append(it.wall / 1000).append(',').append(it.content / 1000).append('\n')
        }
    }

    internal fun cell(s: String): String {
        // Un tableur interprète une cellule qui commence par = + - @ comme une formule : on la neutralise.
        val safe = if (s.isNotEmpty() && s[0] in "=+-@\t\r") "'$s" else s
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }
}
