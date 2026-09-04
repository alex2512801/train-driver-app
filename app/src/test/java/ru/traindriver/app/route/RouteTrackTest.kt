package ru.traindriver.app.route

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

class RouteTrackTest {

    // Простой "прямой" маршрут вдоль параллели, чтобы 1 градус долготы ~ считался вручную.
    private val track = RouteTrack(
        listOf(
            LatLon(55.0, 37.0),
            LatLon(55.0, 37.01),
            LatLon(55.0, 37.02)
        )
    )

    @Test
    fun `point exactly on first node has zero chainage`() {
        assertEquals(0.0, track.chainageMetersFor(55.0, 37.0), 1.0)
    }

    @Test
    fun `point off to the side projects onto the nearest segment`() {
        val onRoute = track.chainageMetersFor(55.0, 37.005)
        val offRoute = track.chainageMetersFor(55.001, 37.005)
        assertEquals(onRoute, offRoute, 5.0)
    }

    @Test
    fun `chainage grows monotonically along the route`() {
        val a = track.chainageMetersFor(55.0, 37.005)
        val b = track.chainageMetersFor(55.0, 37.015)
        assert(b > a)
    }

    @Test
    fun `total length is close to sum of straight segments`() {
        assert(abs(track.totalLengthM) > 0.0)
    }
}
