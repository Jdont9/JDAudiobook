@file:Suppress("UnsafeOptInUsageError")

package fr.jd.audiobooks

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Égaliseur paramétrique : une chaîne de filtres biquad (formules « Audio EQ Cookbook » de R. Bristow-Johnson)
 * appliquée au son AVANT l'effet de gain de volume (Boost). Même vocabulaire qu'Equalizer APO :
 * PK = cloche, LSC / HSC = plateau grave / aigu, HP / LP = passe-haut / passe-bas.
 * Contrairement à l'égaliseur du système (5 bandes qui varient selon le téléphone), le résultat est identique partout.
 */
enum class EqType { PK, LSC, HSC, HP, LP }

data class EqFilter(val type: EqType, val fc: Double, val gain: Double, val q: Double)

/** preamp : atténuation globale en dB (négative), qui évite l'écrêtage quand les filtres relèvent des fréquences. */
data class EqSetup(val preamp: Double, val filters: List<EqFilter>) {
    companion object { val FLAT = EqSetup(0.0, emptyList()) }
}

class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double)

object EqMath {
    /** Coefficients normalisés d'un filtre, ou null s'il est inutilisable (fréquence hors bande, Q nul…). */
    fun biquad(f: EqFilter, fs: Double): Biquad? {
        if (!(f.fc > 0.0) || f.fc >= fs * 0.49 || !(f.q > 0.0) || f.gain.isNaN()) return null
        val w0 = 2 * PI * f.fc / fs
        val c = cos(w0)
        val alpha = sin(w0) / (2 * f.q)
        val a = 10.0.pow(f.gain / 40)
        val sa = 2 * sqrt(a) * alpha
        // b0, b1, b2, a0, a1, a2
        val r = when (f.type) {
            EqType.PK -> doubleArrayOf(1 + alpha * a, -2 * c, 1 - alpha * a, 1 + alpha / a, -2 * c, 1 - alpha / a)
            EqType.LSC -> doubleArrayOf(
                a * ((a + 1) - (a - 1) * c + sa), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - sa),
                (a + 1) + (a - 1) * c + sa, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - sa
            )
            EqType.HSC -> doubleArrayOf(
                a * ((a + 1) + (a - 1) * c + sa), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - sa),
                (a + 1) - (a - 1) * c + sa, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - sa
            )
            EqType.HP -> doubleArrayOf((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
            EqType.LP -> doubleArrayOf((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + alpha, -2 * c, 1 - alpha)
        }
        return Biquad(r[0] / r[3], r[1] / r[3], r[2] / r[3], r[4] / r[3], r[5] / r[3])
    }

    /** Réponse en fréquence (dB) de la chaîne de filtres à [hz], préamp exclu : sert à dessiner la courbe. */
    fun responseDb(setup: EqSetup, hz: Double, fs: Double = 48000.0): Double {
        val w = 2 * PI * hz / fs
        val c1 = cos(w); val s1 = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
        var db = 0.0
        for (f in setup.filters) {
            val b = biquad(f, fs) ?: continue
            val nr = b.b0 + b.b1 * c1 + b.b2 * c2
            val ni = -(b.b1 * s1 + b.b2 * s2)
            val dr = 1 + b.a1 * c1 + b.a2 * c2
            val di = -(b.a1 * s1 + b.a2 * s2)
            val den = dr * dr + di * di
            if (den <= 0.0) continue
            db += 10 * log10(((nr * nr + ni * ni) / den).coerceAtLeast(1e-20))
        }
        return db
    }
}

/**
 * Courbe personnalisée : égaliseur graphique à 10 bandes (cloches de Q 1,41, soit environ une octave de large).
 * Le préampli est calculé automatiquement pour que le pic de la courbe ne dépasse jamais 0 dB (pas de saturation).
 */
object EqCustom {
    val bands = doubleArrayOf(31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
    val labels = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
    const val RANGE = 12.0
    private const val Q = 1.41

    fun flat(): List<Double> = List(bands.size) { 0.0 }

    /** Texte « 0,0,3.5,… » → 10 gains valides (valeur illisible = 0, hors plage = ramenée dans ±12). */
    fun parse(s: String?): List<Double> {
        val l = (s ?: "").split(",").map { (it.trim().toDoubleOrNull() ?: 0.0).coerceIn(-RANGE, RANGE) }
        return List(bands.size) { l.getOrElse(it) { 0.0 } }
    }

    fun serialize(g: List<Double>): String = g.joinToString(",")

    fun setup(gains: List<Double>): EqSetup {
        val f = bands.indices.mapNotNull { i ->
            val g = gains.getOrElse(i) { 0.0 }.coerceIn(-RANGE, RANGE)
            if (abs(g) < 0.05) null else EqFilter(EqType.PK, bands[i], g, Q)
        }
        if (f.isEmpty()) return EqSetup.FLAT
        val raw = EqSetup(0.0, f)
        val peak = (0..200).maxOf { EqMath.responseDb(raw, 20.0 * 1000.0.pow(it / 200.0)) }
        return EqSetup(-ceil(peak.coerceAtLeast(0.0) * 10) / 10.0, f)
    }
}

/** Lecture du format texte d'Equalizer APO / AutoEQ (« Preamp: -3.5 dB », « Filter 1: ON PK Fc 300 Hz Gain -2 dB Q 1.1 »). */
object EqApo {
    private val preamp = Regex("""^\s*Preamp\s*:\s*([-+]?[\d.]+)\s*dB""", RegexOption.IGNORE_CASE)
    private val filter = Regex(
        """^\s*Filter\s*\d*\s*:\s*(ON|OFF)\s+([A-Za-z]+)\s+Fc\s+([\d.]+)\s*Hz(?:\s+Gain\s+([-+]?[\d.]+)\s*dB)?(?:\s+Q\s+([\d.]+))?""",
        RegexOption.IGNORE_CASE
    )

    /** Les lignes inconnues, les filtres « OFF » et les types non gérés sont ignorés (jamais d'exception). */
    fun parse(text: String): EqSetup {
        var pre = 0.0
        val list = mutableListOf<EqFilter>()
        for (line in text.lines()) {
            preamp.find(line)?.let { pre = it.groupValues[1].toDoubleOrNull() ?: 0.0; return@let }
            val m = filter.find(line) ?: continue
            if (!m.groupValues[1].equals("ON", ignoreCase = true)) continue
            val type = runCatching { EqType.valueOf(m.groupValues[2].uppercase()) }.getOrNull() ?: continue
            val fc = m.groupValues[3].toDoubleOrNull() ?: continue
            val gain = m.groupValues[4].toDoubleOrNull() ?: 0.0
            val q = m.groupValues[5].toDoubleOrNull() ?: 0.707
            list += EqFilter(type, fc, gain, q)
        }
        return EqSetup(pre, list)
    }
}

class EqPreset(val id: String, val nameRes: Int, apo: String) {
    val setup: EqSetup = EqApo.parse(apo)
}

/**
 * Presets tirés de equalizerapoguide.com/equalizer-apo-settings, choisis pour l'écoute de livres audio
 * (les presets « musique » : rock, EDM, hip-hop, FPS… sont volontairement absents).
 * Pour en ajouter un : une ligne ici + son nom dans strings.xml (en et fr).
 */
object EqPresets {
    val all = listOf(
        EqPreset("speech", R.string.eq_p_speech, """
            Preamp: -3.5 dB
            Filter 1: ON HP Fc 80 Hz Q 0.71
            Filter 2: ON PK Fc 2800 Hz Gain 3.0 dB Q 1.10
            Filter 3: ON PK Fc 7000 Hz Gain -2.0 dB Q 2.50
        """),
        EqPreset("dialogue", R.string.eq_p_dialogue, """
            Preamp: -5.0 dB
            Filter 1: ON PK Fc 80 Hz Gain -5.0 dB Q 0.80
            Filter 2: ON PK Fc 1800 Hz Gain 3.0 dB Q 1.00
            Filter 3: ON PK Fc 3200 Hz Gain 3.5 dB Q 1.20
            Filter 4: ON PK Fc 8000 Hz Gain -2.0 dB Q 1.00
        """),
        EqPreset("old", R.string.eq_p_old, """
            Preamp: -4.0 dB
            Filter 1: ON LSC Fc 100 Hz Gain 3.0 dB Q 0.70
            Filter 2: ON PK Fc 3000 Hz Gain -2.0 dB Q 1.30
            Filter 3: ON HSC Fc 8000 Hz Gain 3.0 dB Q 0.70
        """),
        EqPreset("night", R.string.eq_p_night, """
            Preamp: -4.0 dB
            Filter 1: ON LSC Fc 100 Hz Gain 3.5 dB Q 0.70
            Filter 2: ON PK Fc 3000 Hz Gain 2.0 dB Q 1.00
            Filter 3: ON HSC Fc 10000 Hz Gain 2.5 dB Q 0.70
        """),
        EqPreset("fatigue", R.string.eq_p_fatigue, """
            Preamp: -1.0 dB
            Filter 1: ON HSC Fc 6000 Hz Gain -3.0 dB Q 0.70
            Filter 2: ON PK Fc 3500 Hz Gain -1.5 dB Q 1.50
        """),
        EqPreset("balanced", R.string.eq_p_balanced, """
            Preamp: -3.5 dB
            Filter 1: ON PK Fc 40 Hz Gain 2.0 dB Q 0.70
            Filter 2: ON PK Fc 300 Hz Gain -2.0 dB Q 1.10
            Filter 3: ON PK Fc 3000 Hz Gain -3.0 dB Q 1.40
            Filter 4: ON HSC Fc 10000 Hz Gain 2.0 dB Q 0.70
        """),
        EqPreset("earbuds", R.string.eq_p_earbuds, """
            Preamp: -4.5 dB
            Filter 1: ON PK Fc 150 Hz Gain 4.0 dB Q 1.00
            Filter 2: ON PK Fc 800 Hz Gain -2.5 dB Q 1.30
            Filter 3: ON HSC Fc 9000 Hz Gain -2.0 dB Q 0.70
        """),
        EqPreset("laptop", R.string.eq_p_laptop, """
            Preamp: -5.0 dB
            Filter 1: ON PK Fc 180 Hz Gain 4.5 dB Q 1.00
            Filter 2: ON PK Fc 900 Hz Gain -3.0 dB Q 1.50
            Filter 3: ON HSC Fc 8000 Hz Gain -2.5 dB Q 0.70
        """),
        EqPreset("closed", R.string.eq_p_closed, """
            Preamp: -2.0 dB
            Filter 1: ON LSC Fc 150 Hz Gain -2.5 dB Q 0.70
            Filter 2: ON PK Fc 1500 Hz Gain 2.0 dB Q 1.00
            Filter 3: ON PK Fc 7500 Hz Gain -2.0 dB Q 1.80
        """),
        EqPreset("open", R.string.eq_p_open, """
            Preamp: -3.5 dB
            Filter 1: ON LSC Fc 90 Hz Gain 3.0 dB Q 0.70
            Filter 2: ON PK Fc 3200 Hz Gain -1.5 dB Q 1.50
        """),
        EqPreset("iem", R.string.eq_p_iem, """
            Preamp: -1.5 dB
            Filter 1: ON PK Fc 8000 Hz Gain -4.0 dB Q 3.00
            Filter 2: ON PK Fc 5000 Hz Gain -1.5 dB Q 2.00
        """)
    )

    fun find(id: String): EqPreset? = all.firstOrNull { it.id == id }
}

/**
 * Processeur audio inséré dans la chaîne de sortie d'ExoPlayer (PCM 16 bits ou flottant). Toujours actif, mais il se
 * contente de recopier le son quand aucun preset n'est choisi (ne change pas la chaîne en cours de lecture).
 * Les réglages sont posés depuis le fil principal et pris en compte par le fil audio au prochain bloc.
 */
class EqProcessor : BaseAudioProcessor() {
    @Volatile private var wanted: EqSetup = EqSetup.FLAT
    @Volatile private var dirty = true
    private var bq: Array<Biquad> = emptyArray()
    private var gain = 1.0
    private var identity = true
    private var z1 = DoubleArray(0)
    private var z2 = DoubleArray(0)

    fun set(s: EqSetup) { wanted = s; dirty = true }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT)
            throw UnhandledAudioFormatException(inputAudioFormat) // autre format : le processeur est simplement ignoré
        dirty = true
        return inputAudioFormat
    }

    private fun rebuild() {
        dirty = false
        val s = wanted
        val ch = inputAudioFormat.channelCount
        bq = s.filters.mapNotNull { EqMath.biquad(it, inputAudioFormat.sampleRate.toDouble()) }.toTypedArray()
        gain = 10.0.pow(s.preamp / 20)
        identity = bq.isEmpty() && abs(s.preamp) < 0.01
        z1 = DoubleArray(bq.size * ch)
        z2 = DoubleArray(bq.size * ch)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (dirty) rebuild()
        val fb = inputAudioFormat.bytesPerFrame
        val frames = inputBuffer.remaining() / fb
        if (frames == 0) { inputBuffer.position(inputBuffer.limit()); return }
        val out = replaceOutputBuffer(frames * fb)
        if (identity) {
            val lim = inputBuffer.limit()
            inputBuffer.limit(inputBuffer.position() + frames * fb)
            out.put(inputBuffer)
            inputBuffer.limit(lim)
        } else {
            val ch = inputAudioFormat.channelCount
            val is16 = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT
            val n = bq.size
            for (f in 0 until frames) for (c in 0 until ch) {
                var x = (if (is16) inputBuffer.getShort() / 32768.0 else inputBuffer.getFloat().toDouble()) * gain
                for (i in 0 until n) {
                    val b = bq[i]
                    val k = i * ch + c
                    val y = b.b0 * x + z1[k]               // forme directe transposée II
                    z1[k] = b.b1 * x - b.a1 * y + z2[k]
                    z2[k] = b.b2 * x - b.a2 * y
                    x = y
                }
                if (is16) out.putShort((x * 32768.0).roundToInt().coerceIn(-32768, 32767).toShort())
                else out.putFloat(x.toFloat())
            }
        }
        out.flip()
    }

    override fun onFlush() {
        z1.fill(0.0); z2.fill(0.0)
        dirty = true
    }

    override fun onReset() {
        bq = emptyArray(); z1 = DoubleArray(0); z2 = DoubleArray(0)
        dirty = true
    }
}

/** Réglage courant de l'égaliseur : un preset (ou rien) par livre, appliqué par le service à chaque changement de livre. */
object Eq {
    /** Identifiant « courbe personnalisée » (les autres identifiants sont ceux de EqPresets). */
    const val CUSTOM = "custom"
    private var proc: EqProcessor? = null
    private var current: EqSetup = EqSetup.FLAT
    private var selected = ""
    private var customGains: List<Double> = EqCustom.flat()

    private fun setupFor(id: String): EqSetup =
        if (id == CUSTOM) EqCustom.setup(customGains) else EqPresets.find(id)?.setup ?: EqSetup.FLAT

    /** Appelé à la création du lecteur : le processeur est branché dans la sortie audio. */
    fun newProcessor(): EqProcessor = EqProcessor().also { it.set(current); proc = it }

    /** id vide ou inconnu = égaliseur coupé. */
    fun select(id: String) {
        selected = id
        current = setupFor(id)
        proc?.set(current)
    }

    /** Nouvelle courbe personnalisée ; entendue tout de suite si elle est la courbe choisie. */
    fun setCustom(gains: List<Double>) {
        customGains = gains
        if (selected == CUSTOM) { current = setupFor(CUSTOM); proc?.set(current) }
    }

    fun release() { proc = null }
}

/** Fabrique de rendu qui ajoute l'égaliseur à la sortie audio (le reste est identique à ExoPlayer par défaut). */
class EqRenderersFactory(context: Context, private val processor: EqProcessor) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
        DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(processor))
            .build()
}
