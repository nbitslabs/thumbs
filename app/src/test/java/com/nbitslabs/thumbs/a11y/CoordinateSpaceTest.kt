package com.nbitslabs.thumbs.a11y

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinateSpaceTest {
    @Test fun `phone screens fit vision model limits`() {
        for ((w, h) in listOf(1080 to 2400, 1440 to 3120, 1344 to 2992, 2400 to 1080, 2560 to 1600, 1080 to 1920)) {
            val s = CoordinateSpace.forScreen(w, h)
            assertTrue("$w x $h long edge", maxOf(s.width, s.height) <= CoordinateSpace.MAX_LONG_EDGE)
            assertTrue("$w x $h pixels", s.width.toLong() * s.height <= CoordinateSpace.MAX_PIXELS + 2000)
        }
    }

    @Test fun `small screens are not upscaled`() {
        val s = CoordinateSpace.forScreen(720, 1280)
        assertEquals(1.0, s.scale, 0.0)
        assertEquals(720, s.width)
    }

    @Test fun `round trips between space and screen`() {
        val s = CoordinateSpace.forScreen(1080, 2400)
        val x = 300.0
        val back = s.fromScreen(s.toScreenX(x).toInt())
        assertTrue(kotlin.math.abs(back - x) <= 1)
        assertEquals(1079f, s.toScreenX(99999.0))
        assertEquals(0f, s.toScreenY(-5.0))
    }
}
