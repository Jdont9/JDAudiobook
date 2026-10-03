package fr.jd.audiobooks

import android.media.audiofx.LoudnessEnhancer

/**
 * Gain de volume supplémentaire en décibels (au-delà du volume maximum du téléphone), pour les livres
 * enregistrés trop bas. Réglé par livre, appliqué par le service à chaque changement de livre.
 */
object Boost {
    val levels = listOf(0, 3, 6, 9, 12)
    private var enhancer: LoudnessEnhancer? = null
    private var gainDb = 0

    /** Appelé quand le lecteur crée/change sa session audio. */
    fun attach(sessionId: Int) {
        try { enhancer?.release() } catch (e: Exception) { }
        enhancer = try { LoudnessEnhancer(sessionId) } catch (e: Exception) { null }
        apply()
    }

    fun set(db: Int) {
        gainDb = db
        apply()
    }

    private fun apply() {
        val e = enhancer ?: return
        try {
            e.setTargetGain(gainDb * 100) // millibels
            e.enabled = gainDb > 0
        } catch (ex: Exception) { }
    }

    fun release() {
        try { enhancer?.release() } catch (e: Exception) { }
        enhancer = null
    }
}
