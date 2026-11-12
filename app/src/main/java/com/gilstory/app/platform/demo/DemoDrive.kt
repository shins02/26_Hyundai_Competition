package com.gilstory.app.platform.demo

import android.os.Handler
import com.gilstory.app.core.RestNavigator
import com.gilstory.app.core.RestStop

/**
 * 시연 영상용 모의 주행: 영동고속도로 서울 → 강릉 방향.
 *
 * 실제 2시간 넘는 주행을 몇 분으로 줄여서, 시·군이 바뀌고 운전 시간이 쌓이는 장면을 만듭니다.
 * 쉼터 이름은 영동고속도로 강릉 방향 휴게소이고, 거리는 시연용 값입니다(실측 아님).
 */
class DemoDrive(
    private val main: Handler,
    private val sink: Sink,
) : RestNavigator {

    interface Sink {
        fun onDemoRegion(region: String)
        fun onDemoMinutes(minutes: Int)
        fun onDemoArrived()
        fun onDemoFinished()
    }

    private data class Leg(val region: String, val minutes: Int, val nextRest: String, val restKm: Double)

    private val legs = listOf(
        Leg("용인시", 10, "용인휴게소", 6.0),
        Leg("이천시", 30, "덕평자연휴게소", 5.0),
        Leg("여주시", 45, "여주휴게소", 4.0),
        Leg("원주시", 65, "문막휴게소", 3.5),
        Leg("횡성군", 85, "횡성휴게소", 4.5),
        Leg("평창군", 100, "평창휴게소", 4.0),
        Leg("평창군", 115, "평창휴게소", 2.0),
        Leg("강릉시", 125, "강릉휴게소", 3.0),
    )

    private var index = -1
    private var running = false
    private var legMs = DEFAULT_LEG_MS

    val isRunning get() = running

    /** 지도용: 시연 구간 순서대로의 시·군 이름과, 한 구간 길이 */
    val regions: List<String> get() = legs.map { it.region }
    val legDurationMs: Long get() = legMs

    private val step = object : Runnable {
        override fun run() {
            if (!running) return
            index += 1
            if (index >= legs.size) {
                running = false
                sink.onDemoFinished()
                return
            }
            val leg = legs[index]
            sink.onDemoMinutes(leg.minutes)
            sink.onDemoRegion(leg.region)
            main.postDelayed(this, legMs)
        }
    }

    private val arrive = Runnable { sink.onDemoArrived() }

    fun start(legDurationMs: Long = DEFAULT_LEG_MS) {
        stop()
        legMs = legDurationMs
        index = -1
        running = true
        main.post(step)
    }

    fun stop() {
        running = false
        main.removeCallbacks(step)
        main.removeCallbacks(arrive)
    }

    override fun nextRestStop(): RestStop? {
        val leg = legs.getOrNull(index.coerceAtLeast(0)) ?: return null
        return RestStop(leg.nextRest, "휴게소", leg.restKm)
    }

    override fun addWaypoint(stop: RestStop, onResult: (Boolean) -> Unit) {
        onResult(true)
        // 시연: 경유지 추가 후 잠시 뒤 쉼터에 도착
        main.removeCallbacks(arrive)
        main.postDelayed(arrive, ARRIVE_MS)
    }

    companion object {
        const val DEFAULT_LEG_MS = 40_000L
        const val ARRIVE_MS = 45_000L
    }
}

/** 데모 중에는 데모 쉼터, 그 밖에는 Pleos 내비를 씁니다. */
class SwitchingNavigator(private val demo: DemoDrive, private val real: RestNavigator?) : RestNavigator {
    override fun nextRestStop(): RestStop? =
        if (demo.isRunning || real == null) demo.nextRestStop() else real.nextRestStop()

    override fun followExisting(stop: RestStop) { if (!demo.isRunning) real?.followExisting(stop) }

    override fun addWaypoint(stop: RestStop, onResult: (Boolean) -> Unit) =
        if (demo.isRunning || real == null) demo.addWaypoint(stop, onResult) else real.addWaypoint(stop, onResult)
}
