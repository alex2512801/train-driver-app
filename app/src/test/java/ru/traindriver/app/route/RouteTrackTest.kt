package ru.traindriver.app.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs

class RouteTrackTest {

    // Простой "прямой" маршрут вдоль параллели, чтобы 1 градус долготы ~ считался вручную.
    // Калибровка (см. doc-комментарий RouteTrack) — кусочно-линейная по якорям, не единый
    // коэффициент растяжения на весь маршрут, как было раньше.
    private val track = RouteTrack(
        listOf(
            LatLon(55.0, 37.0),
            LatLon(55.0, 37.01),
            LatLon(55.0, 37.02)
        ),
        listOf(
            RouteTrack.RouteAnchor(55.0, 37.0, 0.0),
            RouteTrack.RouteAnchor(55.0, 37.02, 2000.0)
        )
    )

    @Test
    fun `point exactly on first anchor matches its real chainage`() {
        assertEquals(0.0, track.chainageMetersFor(55.0, 37.0), 5.0)
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
        assertTrue(b > a)
    }

    @Test
    fun `end chainage matches the last anchor`() {
        assertEquals(2000.0, track.endChainageM, 50.0)
    }

    @Test
    fun `end chainage is positive`() {
        assertTrue(abs(track.endChainageM) > 0.0)
    }

    @Test
    fun `anchors offset the whole route`() {
        val offsetTrack = RouteTrack(
            listOf(LatLon(55.0, 37.0), LatLon(55.0, 37.01)),
            listOf(
                RouteTrack.RouteAnchor(55.0, 37.0, 5_930_000.0),
                RouteTrack.RouteAnchor(55.0, 37.01, 5_931_000.0)
            )
        )
        assertTrue(offsetTrack.chainageMetersFor(55.0, 37.0) >= 5_930_000.0)
    }

    @Test
    fun `piecewise calibration matches an intermediate anchor exactly, not just the two ends`() {
        // Три якоря вместо двух — средний должен совпасть ТОЧНО (кусочно-линейная, а не единый
        // коэффициент растяжения на весь маршрут, у которого середина могла бы "гулять").
        val threeAnchorTrack = RouteTrack(
            listOf(
                LatLon(55.0, 37.0),
                LatLon(55.0, 37.01),
                LatLon(55.0, 37.02)
            ),
            listOf(
                RouteTrack.RouteAnchor(55.0, 37.0, 0.0),
                RouteTrack.RouteAnchor(55.0, 37.01, 500.0),
                RouteTrack.RouteAnchor(55.0, 37.02, 2000.0)
            )
        )
        assertEquals(500.0, threeAnchorTrack.chainageMetersFor(55.0, 37.01), 5.0)
    }

    @Test
    fun `requires at least two points`() {
        try {
            RouteTrack(
                listOf(LatLon(55.0, 37.0)),
                listOf(RouteTrack.RouteAnchor(55.0, 37.0, 0.0), RouteTrack.RouteAnchor(55.0, 37.01, 1000.0))
            )
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // ожидаемо
        }
    }

    @Test
    fun `requires at least two anchors`() {
        try {
            RouteTrack(
                listOf(LatLon(55.0, 37.0), LatLon(55.0, 37.01)),
                listOf(RouteTrack.RouteAnchor(55.0, 37.0, 0.0))
            )
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // ожидаемо
        }
    }
}
