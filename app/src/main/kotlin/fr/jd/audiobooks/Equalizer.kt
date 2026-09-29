package fr.jd.audiobooks

import android.media.audiofx.Equalizer as SysEq

/** Enveloppe autour de android.media.audiofx.Equalizer, attachée à la session audio du lecteur. */
object Eq {
    var eq: SysEq? = null
        private set

    fun attach(sessionId: Int, store: Store) {
        eq?.release()
        val e = try { SysEq(0, sessionId) } catch (ex: Exception) { null } ?: return
        eq = e
        e.enabled = store.eqEnabled()
        store.eqLevels()?.let { levels ->
            levels.forEachIndexed { i, lv -> if (i < e.numberOfBands) try { e.setBandLevel(i.toShort(), lv) } catch (ex: Exception) {} }
        }
    }

    fun bands(): List<Triple<Short, Short, Short>> {
        val e = eq ?: return emptyList()
        val r = e.bandLevelRange
        return (0 until e.numberOfBands).map { i -> Triple(i.toShort(), r[0], r[1]) }
    }
    fun freq(b: Short) = eq?.getCenterFreq(b)?.let { it / 1000 } ?: 0
    fun level(b: Short) = eq?.getBandLevel(b) ?: 0
    fun setLevel(b: Short, l: Short) { eq?.setBandLevel(b, l) }
    fun setEnabled(v: Boolean) { eq?.enabled = v }
    fun presets(): List<String> = eq?.let { (0 until it.numberOfPresets).map { i -> it.getPresetName(i.toShort()) } } ?: emptyList()
    fun usePreset(i: Short) { eq?.usePreset(i) }
    fun snapshot(): List<Short> = eq?.let { (0 until it.numberOfBands).map { b -> it.getBandLevel(b.toShort()) } } ?: emptyList()
}
