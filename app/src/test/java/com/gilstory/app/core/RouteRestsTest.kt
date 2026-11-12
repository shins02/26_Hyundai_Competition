package com.gilstory.app.core
import org.junit.Assert.*
import org.junit.Test
class RouteRestsTest {
    @Test fun `방향이 일치하거나 양방향인 시설만 좌표 후보가 된다`() {
        val stops = listOf(RestStopPoint("용인휴게소","휴게소","강릉",37.245583,127.2419801), RestStopPoint("덕평자연휴게소","휴게소","양방향",37.241456,127.390189))
        fun next(name:String) = RouteRests.next(stops,listOf(AheadRest(name,3200.0)))
        assertNull(next("용인휴게소")); assertNull(next("용인휴게소 인천방향"))
        assertNull(next("동용인휴게소(강릉방향)"))
        assertNull(next("용인휴게소 통과 후 강릉 방면"))
        assertEquals(3.2,next("용인휴게소(강릉방향)")!!.distanceKm,0.001)
        assertEquals("용인휴게소",next("용인(강릉방향)휴게소")!!.name)
        assertEquals("덕평자연휴게소",next("덕평휴게소")!!.name)
    }
}
