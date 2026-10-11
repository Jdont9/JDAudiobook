package fr.jd.audiobooks

import org.junit.Assert.*
import org.junit.Test

class EqTest {
    @Test fun parsesEqualizerApoText() {
        val s = EqApo.parse("""
            Preamp: -3.5 dB
            Filter 1: ON HP Fc 80 Hz Q 0.71
            Filter 2: ON PK Fc 2800 Hz Gain 3.0 dB Q 1.10
            Filter 3: OFF PK Fc 7000 Hz Gain -2.0 dB Q 2.50
            Filter 4: ON XX Fc 100 Hz Gain 1 dB Q 1
            Ceci n'est pas un filtre
        """.trimIndent())
        assertEquals(-3.5, s.preamp, 0.0001)
        assertEquals(2, s.filters.size) // le filtre OFF et le type inconnu sont ignorés
        assertEquals(EqType.HP, s.filters[0].type)
        assertEquals(2800.0, s.filters[1].fc, 0.0001)
        assertEquals(3.0, s.filters[1].gain, 0.0001)
    }

    @Test fun parserNeverThrowsOnGarbage() {
        assertEquals(EqSetup.FLAT, EqApo.parse(""))
        assertEquals(EqSetup.FLAT, EqApo.parse("\u0000 Filter: ON PK Fc abc Hz"))
    }

    @Test fun peakFilterGivesItsGainAtCenterAndNothingFarAway() {
        val s = EqSetup(0.0, listOf(EqFilter(EqType.PK, 2800.0, 3.0, 1.1)))
        assertEquals(3.0, EqMath.responseDb(s, 2800.0), 0.01)
        assertEquals(0.0, EqMath.responseDb(s, 50.0), 0.05)
    }

    @Test fun shelvesReachTheirGainAndHighPassCutsRumble() {
        val low = EqSetup(0.0, listOf(EqFilter(EqType.LSC, 100.0, 3.5, 0.7)))
        assertEquals(3.5, EqMath.responseDb(low, 20.0), 0.1)
        assertEquals(0.0, EqMath.responseDb(low, 15000.0), 0.05)
        val high = EqSetup(0.0, listOf(EqFilter(EqType.HSC, 8000.0, 3.0, 0.7)))
        assertEquals(3.0, EqMath.responseDb(high, 20000.0), 0.1)
        val hp = EqSetup(0.0, listOf(EqFilter(EqType.HP, 80.0, 0.0, 0.71)))
        assertTrue(EqMath.responseDb(hp, 20.0) < -20.0)
        assertEquals(0.0, EqMath.responseDb(hp, 1000.0), 0.05)
    }

    @Test fun unusableFilterIsSkipped() {
        assertNull(EqMath.biquad(EqFilter(EqType.PK, 30000.0, 3.0, 1.0), 48000.0)) // au-dessus de Nyquist
        assertNull(EqMath.biquad(EqFilter(EqType.PK, 1000.0, 3.0, 0.0), 48000.0))  // Q nul
    }

    @Test fun everyPresetHasFiltersAndASafePreamp() {
        assertTrue(EqPresets.all.isNotEmpty())
        assertEquals(EqPresets.all.size, EqPresets.all.map { it.id }.toSet().size)
        for (p in EqPresets.all) {
            assertTrue(p.id, p.setup.filters.isNotEmpty())
            assertTrue(p.id, p.setup.preamp <= 0.0)
            // préampli suffisant : le pic de la courbe, une fois le préampli appliqué, ne dépasse pas 0 dB
            val peak = (0..200).maxOf { EqMath.responseDb(p.setup, 20.0 * 1000.0.pow(it / 200.0)) }
            assertTrue("${p.id} peak=$peak preamp=${p.setup.preamp}", peak + p.setup.preamp <= 0.5)
        }
    }

    private fun Double.pow(e: Double) = Math.pow(this, e)
}

class EqCustomTest {
    @Test fun flatCurveIsNeutral() {
        assertEquals(EqSetup.FLAT, EqCustom.setup(EqCustom.flat()))
    }

    @Test fun boostedCurveNeverClips() {
        val g = EqCustom.flat().toMutableList().also { it[5] = 12.0; it[6] = 12.0 }
        val s = EqCustom.setup(g)
        val peak = (0..400).maxOf { EqMath.responseDb(s, 20.0 * Math.pow(1000.0, it / 400.0)) }
        assertTrue("preamp=${s.preamp}", s.preamp < -11.0)
        assertTrue("peak+preamp=${peak + s.preamp}", peak + s.preamp <= 0.05)
    }

    @Test fun cutOnlyCurveNeedsNoPreamp() {
        val g = EqCustom.flat().toMutableList().also { it[3] = -6.0 }
        assertEquals(0.0, EqCustom.setup(g).preamp, 0.0001)
    }

    @Test fun parseIsTolerant() {
        assertEquals(EqCustom.flat(), EqCustom.parse(null))
        assertEquals(EqCustom.flat(), EqCustom.parse("abc,,x"))
        val p = EqCustom.parse("3.5,-20,99")
        assertEquals(3.5, p[0], 0.0)
        assertEquals(-12.0, p[1], 0.0) // ramené dans la plage
        assertEquals(12.0, p[2], 0.0)
        assertEquals(10, p.size)
        val g = listOf(0.0, 1.5, -3.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 12.0)
        assertEquals(g, EqCustom.parse(EqCustom.serialize(g)))
    }
}
