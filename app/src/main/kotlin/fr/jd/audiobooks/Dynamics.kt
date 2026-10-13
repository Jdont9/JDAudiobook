package fr.jd.audiobooks

import android.media.audiofx.DynamicsProcessing
import android.os.Build

/**
 * Compression dynamique de la voix (option, Android 9+) : rapproche les passages faibles et forts, pour écouter en voiture, dans
 * le bruit ou la nuit sans toucher au volume à chaque chuchotement ou éclat de voix. Un limiteur évite toute saturation.
 * Branchée sur la session audio du lecteur, comme le gain de volume (Boost). Si le téléphone ne sait pas le faire, l'option
 * est sans effet (aucune erreur).
 */
object Dynamics {
    @Volatile var enabled = false
    private var dp: DynamicsProcessing? = null

    /** Appelé quand le lecteur crée/change sa session audio. */
    fun attach(sessionId: Int) {
        release()
        if (Build.VERSION.SDK_INT < 28) return
        dp = try { build(sessionId) } catch (e: Throwable) { logw("DynamicsProcessing indisponible", e); null }
        apply()
    }

    private fun build(sessionId: Int): DynamicsProcessing {
        // Étage « compresseur multibande » avec une seule bande couvrant tout le spectre, puis un limiteur.
        val cfg = DynamicsProcessing.Config.Builder(DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION, 2, false, 0, true, 1, false, 0, true).build()
        val d = DynamicsProcessing(0, sessionId, cfg)
        // activé, coupure, attaque, relâchement (ms), ratio, seuil, coude, seuil de porte (dB), expansion, gain avant, gain après (dB)
        d.setMbcBandAllChannelsTo(0, DynamicsProcessing.MbcBand(true, 20000f, 10f, 250f, 3f, -32f, 6f, -90f, 1f, 0f, 6f))
        // activé, groupe, attaque, relâchement (ms), ratio, seuil, gain après (dB)
        d.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -2f, 0f))
        return d
    }

    fun set(on: Boolean) { enabled = on; apply() }

    private fun apply() {
        try { dp?.enabled = enabled } catch (e: Throwable) { logw("DynamicsProcessing", e) }
    }

    fun release() {
        try { dp?.release() } catch (e: Throwable) { logw("DynamicsProcessing", e) }
        dp = null
    }
}
