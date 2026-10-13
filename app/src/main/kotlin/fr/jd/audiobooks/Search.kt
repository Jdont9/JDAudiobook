package fr.jd.audiobooks

/**
 * Recherche de livres par texte (Android Auto, Assistant : « lis Millenium »). Insensible aux accents et à la casse ;
 * tous les mots doivent se retrouver dans le chemin du livre (auteur/série/titre). À défaut, on garde les livres qui en
 * contiennent au moins la moitié, les plus proches d'abord.
 */
object SearchBooks {
    private fun words(s: String): List<String> = fold(s).split(Regex("[^a-z0-9]+")).filter { it.length >= 2 }

    fun find(lib: List<Book>, query: String, max: Int = 50): List<Book> {
        val q = words(query)
        if (q.isEmpty()) return emptyList()
        val scored = lib.mapNotNull { b ->
            val hay = words(b.path).joinToString(" ")
            val hits = q.count { hay.contains(it) }
            if (hits == 0) return@mapNotNull null
            val full = hits == q.size
            if (!full && hits * 2 < q.size) return@mapNotNull null
            val title = words(b.name).joinToString(" ")
            // plein > partiel ; titre qui commence par la demande > titre qui la contient > simple présence dans le chemin
            val score = (if (full) 100 else 0) + hits * 10 + (if (title.startsWith(q.first())) 5 else if (title.contains(q.first())) 2 else 0)
            b to score
        }
        return scored.sortedByDescending { it.second }.take(max).map { it.first }
    }
}
