package fr.jd.audiobooks

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * Sert les pochettes (petits JPEG de filesDir/art) par URI : la notification, l'écran de verrouillage, Android Auto
 * et le widget les chargent via cette URI. Avant, l'image entière était recopiée dans chaque fichier de la playlist
 * (300 fichiers = 300 copies envoyées aux autres processus). Lecture seule, noms de fichiers strictement contrôlés.
 */
class CoverProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        if (mode != "r" || !Regex("[0-9a-f]{1,8}\\.jpg").matches(name)) throw FileNotFoundException()

        val baseDir = File(context!!.filesDir, "art").canonicalFile
        val requested = File(baseDir, name).canonicalFile

        val basePath = baseDir.path + File.separator
        if (!requested.path.startsWith(basePath) || !requested.isFile) throw FileNotFoundException()

        return ParcelFileDescriptor.open(requested, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "image/jpeg"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
