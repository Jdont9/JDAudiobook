package fr.jd.audiobooks

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import kotlin.math.sqrt

/**
 * Détection de secousse, logique pure (testable) : [jolts] pics d'accélération d'au moins [threshold] g
 * en moins de [windowMs]. Deux pics à moins de [minGapMs] sont le même coup.
 */
class ShakeLogic(
    private val threshold: Float = 2.5f,
    private val windowMs: Long = 1500L,
    private val jolts: Int = 3,
    private val minGapMs: Long = 120L
) {
    private var first = 0L
    private var last = 0L
    private var count = 0

    /** g : accélération en multiples de la pesanteur ; now : heure monotone (ms). true = secousse reconnue. */
    fun onSample(g: Float, now: Long): Boolean {
        if (g < threshold) return false
        if (count > 0 && now - last < minGapMs) return false
        last = now
        if (count == 0 || now - first > windowMs) { first = now; count = 0 }
        count++
        if (count >= jolts) { count = 0; return true }
        return false
    }
}

/** Écoute l'accéléromètre tant qu'elle est démarrée (seulement quand une minuterie de sommeil tourne : pas de batterie perdue le reste du temps). */
class ShakeWatcher(ctx: Context, private val onShake: () -> Unit) : SensorEventListener {
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val logic = ShakeLogic()

    fun start(): Boolean {
        val m = sm ?: return false
        val s = m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return false
        return m.registerListener(this, s, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() { sm?.unregisterListener(this) }

    override fun onSensorChanged(e: SensorEvent) {
        val x = e.values[0]; val y = e.values[1]; val z = e.values[2]
        val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        if (logic.onSample(g, SystemClock.elapsedRealtime())) onShake()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

/** Courte vibration : confirme dans le noir que la secousse a été prise en compte. */
@Suppress("DEPRECATION")
fun buzz(ctx: Context) {
    try {
        (ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
            ?.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
    } catch (e: Exception) { logw("vibration impossible", e) }
}
