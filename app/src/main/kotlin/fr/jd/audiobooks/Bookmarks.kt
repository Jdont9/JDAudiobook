package fr.jd.audiobooks

/** Export des signets en texte (Markdown), pur et testable. */
object BookmarkExport {
    /** Titre du livre, puis une ligne par signet : « - 1:02:03 — note (fichier) » (ou le nom du fichier s'il n'y a pas de note). */
    fun markdown(bookName: String, list: List<Bookmark>, time: (Long) -> String): String = buildString {
        append("# ").append(bookName.replace('\n', ' ')).append("\n\n")
        list.forEach { b ->
            append("- ").append(time(b.pos)).append(" — ")
            if (b.note.isEmpty()) append(b.file.replace('\n', ' '))
            else append(b.note.replace('\n', ' ')).append(" (").append(b.file.replace('\n', ' ')).append(")")
            append('\n')
        }
    }
}
