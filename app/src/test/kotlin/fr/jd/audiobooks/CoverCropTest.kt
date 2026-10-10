package fr.jd.audiobooks

import org.junit.Assert.*
import org.junit.Test

class CoverCropTest {
    @Test fun centeredLandscapeCropIsSquareOfShortSide() {
        // image 200x100 dans une fenêtre de 100 : échelle 1, centrée → on garde le carré du milieu
        val r = CoverCrop.srcSquare(ox = -50f, oy = 0f, scale = 1f, side = 100f, w = 200, h = 100)
        assertArrayEquals(intArrayOf(50, 0, 100), r)
    }

    @Test fun zoomShrinksTheCroppedArea() {
        val r = CoverCrop.srcSquare(ox = -100f, oy = -20f, scale = 2f, side = 100f, w = 200, h = 100)
        assertArrayEquals(intArrayOf(50, 10, 50), r)
    }

    @Test fun cropNeverLeavesTheImage() {
        val r = CoverCrop.srcSquare(ox = 30f, oy = -500f, scale = 1f, side = 100f, w = 200, h = 100)
        assertTrue(r[0] >= 0 && r[1] >= 0 && r[0] + r[2] <= 200 && r[1] + r[2] <= 100)
    }

    @Test fun offsetIsClampedSoTheImageAlwaysCoversTheWindow() {
        assertEquals(0f, CoverCrop.clampOffset(40f, 100f, 200f), 0.001f)
        assertEquals(-100f, CoverCrop.clampOffset(-300f, 100f, 200f), 0.001f)
        assertEquals(-30f, CoverCrop.clampOffset(-30f, 100f, 200f), 0.001f)
    }
}
