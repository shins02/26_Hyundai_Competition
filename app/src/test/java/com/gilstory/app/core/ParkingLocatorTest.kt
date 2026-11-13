package com.gilstory.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParkingLocatorTest {

    private class FakeCamera(override val available: Boolean = true) : SignCamera {
        var onFrame: ((SignFrame) -> Unit)? = null
        var onceCallback: ((SignFrame?) -> Unit)? = null
        var startCount = 0
        var facing: CamFacing? = null

        override fun start(facing: CamFacing, onFrame: (SignFrame) -> Unit) {
            startCount += 1
            this.facing = facing
            this.onFrame = onFrame
        }

        override fun stop() {
            // 이미 나간 프레임이 stop 뒤에 도착하는 경우를 시험하려고 콜백을 지우지 않습니다
        }

        override fun readOnce(onResult: (SignFrame?) -> Unit) {
            onceCallback = onResult
        }

        fun see(text: String, photo: String? = null) = onFrame!!(SignFrame(text, photo))
    }

    private class FakeNotifier : ParkingNotifier {
        val spots = mutableListOf<ParkingSpot>()
        override fun notify(spot: ParkingSpot) {
            spots += spot
        }
    }

    private var now = 0L
    private val camera = FakeCamera()
    private val notifier = FakeNotifier()
    private val locator = ParkingLocator(camera, notifier) { now }

    @Test
    fun `후진 중 가장 많이 읽은 기둥을 P단에서 알린다`() {
        locator.onGear(DriveGear.OTHER)
        locator.onGear(DriveGear.REVERSE)
        camera.see("B2 C-16", "a")
        camera.see("B2 C-17", "b")
        camera.see("B2 C-17", "c")
        assertTrue(notifier.spots.isEmpty())

        locator.onGear(DriveGear.PARK)

        assertEquals(listOf(ParkingSpot("B2", "C-17", "c")), notifier.spots)
    }

    @Test
    fun `앞뒤로 움직여도 알림은 한 번이고 값은 이어진다`() {
        locator.onGear(DriveGear.REVERSE)
        camera.see("B2 C-17")
        locator.onGear(DriveGear.OTHER)
        now += 5_000
        locator.onGear(DriveGear.REVERSE)
        camera.see("C-17")
        locator.onGear(DriveGear.PARK)
        locator.onGear(DriveGear.PARK)

        assertEquals(1, notifier.spots.size)
        assertEquals(ParkingSpot("B2", "C-17"), notifier.spots.single())
    }

    @Test
    fun `앱을 켰을 때 이미 P단이면 알리지 않는다`() {
        locator.onGear(DriveGear.PARK)

        assertTrue(notifier.spots.isEmpty())
        assertNull(camera.onceCallback)
    }

    @Test
    fun `후진은 뒤쪽, 앞으로 갈 때는 앞쪽 카메라로 읽는다`() {
        locator.onGear(DriveGear.OTHER)
        assertEquals(CamFacing.FRONT, camera.facing)
        locator.onGear(DriveGear.REVERSE)
        assertEquals(CamFacing.REAR, camera.facing)
        locator.onGear(DriveGear.OTHER)
        assertEquals(CamFacing.FRONT, camera.facing)
    }

    @Test
    fun `후진을 여러 번 고쳐 대도 마지막 자리 기둥을 고른다`() {
        locator.onGear(DriveGear.OTHER)
        locator.onGear(DriveGear.REVERSE)
        camera.see("B2 C-16", "a")
        camera.see("B2 C-16", "b")
        now += 6_000
        locator.onGear(DriveGear.OTHER) // 앞으로 빼서 고쳐 대기
        camera.see("B2 C-17", "c")
        now += 6_000
        locator.onGear(DriveGear.REVERSE)
        camera.see("B2 C-17", "d")
        now += 3_000
        locator.onGear(DriveGear.PARK)

        assertEquals(listOf(ParkingSpot("B2", "C-17", "d")), notifier.spots)
        assertNull(camera.onceCallback)
    }

    @Test
    fun `앞으로 주차하면 주차 직전 앞쪽 카메라 글자로 정한다`() {
        locator.onGear(DriveGear.OTHER)
        camera.see("B2 C-15") // 통로를 지나며 본 기둥
        now += 8_000
        camera.see("B2 C-17", "f")
        camera.see("C-17")
        now += 2_000
        locator.onGear(DriveGear.PARK)

        assertEquals(ParkingSpot("B2", "C-17", "f"), notifier.spots.single())
        assertNull(camera.onceCallback)
    }

    @Test
    fun `오래전에 앞쪽 카메라로 본 글자는 쓰지 않는다`() {
        locator.onGear(DriveGear.OTHER)
        camera.see("B9 H-99")
        now += ParkingLocator.FRONT_WINDOW_MS + 1
        locator.onGear(DriveGear.REVERSE)
        camera.see("B2 C-17")
        locator.onGear(DriveGear.PARK)

        assertEquals(ParkingSpot("B2", "C-17"), notifier.spots.single())
    }

    @Test
    fun `앞으로 주차하면 P단에서 한 번 읽는다`() {
        locator.onGear(DriveGear.OTHER)
        locator.onGear(DriveGear.PARK)
        assertTrue(notifier.spots.isEmpty())

        camera.onceCallback!!(SignFrame("지하 3층 D-5", "p"))

        assertEquals(ParkingSpot("B3", "D-05", "p"), notifier.spots.single())
    }

    @Test
    fun `출차 후진 때 읽은 값을 다음 주차에 쓰지 않는다`() {
        locator.onGear(DriveGear.PARK)
        locator.onGear(DriveGear.REVERSE)
        camera.see("B2 C-17")
        locator.onGear(DriveGear.OTHER)
        now += ParkingLocator.STALE_AFTER_MS + 1
        locator.onGear(DriveGear.PARK)

        camera.onceCallback!!(SignFrame("B1 A-03"))

        assertEquals(ParkingSpot("B1", "A-03"), notifier.spots.single())
    }

    @Test
    fun `다시 후진 주차하면 출차 때 값이 섞이지 않는다`() {
        locator.onGear(DriveGear.PARK)
        locator.onGear(DriveGear.REVERSE)
        camera.see("B2 C-17")
        camera.see("B2 C-17")
        locator.onGear(DriveGear.OTHER)
        now += ParkingLocator.STALE_AFTER_MS + 1
        locator.onGear(DriveGear.REVERSE)
        camera.see("B1 A-03")
        locator.onGear(DriveGear.PARK)

        assertEquals(ParkingSpot("B1", "A-03"), notifier.spots.single())
    }

    @Test
    fun `P단에서 읽는 사이 출발하면 알리지 않는다`() {
        locator.onGear(DriveGear.OTHER)
        locator.onGear(DriveGear.PARK)
        val pending = camera.onceCallback!!
        locator.onGear(DriveGear.OTHER)

        pending(SignFrame("B1 A-03"))

        assertTrue(notifier.spots.isEmpty())
    }

    @Test
    fun `글자를 못 읽으면 사진 확인 알림을 보낸다`() {
        locator.onGear(DriveGear.REVERSE)
        camera.see("EXIT", "p")
        locator.onGear(DriveGear.PARK)

        val spot = notifier.spots.single()
        assertFalse(spot.complete)
        assertEquals("p", spot.photoUri)
        assertEquals("글자를 읽지 못했어요. 주차 사진을 확인해 주세요", spot.summary())
    }

    @Test
    fun `카메라를 쓸 수 없으면 켜지도 알리지도 않는다`() {
        val off = FakeCamera(available = false)
        val noCamera = ParkingLocator(off, notifier) { now }
        noCamera.onGear(DriveGear.REVERSE)
        noCamera.onGear(DriveGear.PARK)

        assertEquals(0, off.startCount)
        assertTrue(notifier.spots.isEmpty())
    }

    @Test
    fun `표지 글자를 해석한다`() {
        assertEquals(SignReading("B2", "C-17"), ParkingSignParser.parse("B2 C-17"))
        assertEquals(SignReading("B2", "C-17"), ParkingSignParser.parse("B2C17"))
        assertEquals(SignReading("B3", "D-05"), ParkingSignParser.parse("지하 3층\nD 5"))
        assertEquals(SignReading("B1", null), ParkingSignParser.parse("b1"))
        assertEquals(SignReading(null, "B-12"), ParkingSignParser.parse("B-12"))
        assertEquals(SignReading(null, "C-17"), ParkingSignParser.parse("C-I7"))
        assertEquals(SignReading(null, null), ParkingSignParser.parse("EXIT 12가 3456"))
        assertEquals(SignReading(null, null), ParkingSignParser.parse("A-OIL"))
    }

    @Test
    fun `위치 문구를 만든다`() {
        assertEquals("지하 2층 · C-17 기둥 옆", ParkingSpot("B2", "C-17").summary())
        assertEquals("지하 2층 · 기둥 번호 확인 필요", ParkingSpot("B2", null).summary())
        assertEquals("C-17 기둥 옆 · 층 확인 필요", ParkingSpot(null, "C-17").summary())
    }
}
