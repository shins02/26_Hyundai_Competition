package com.gilstory.app.platform.pleos

import android.content.Context
import android.os.Handler
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import com.gilstory.app.core.RegionParser
import com.gilstory.app.core.RestNavigator
import com.gilstory.app.core.RestStop
import com.gilstory.app.core.RestStopPoint
import com.gilstory.app.core.RouteRests
import com.gilstory.app.core.AheadRest
import android.os.SystemClock
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import kotlin.math.abs

/**
 * Pleos NaviHelper로 현재 위치의 시·군을 알아내고, 쉼터를 경유지로 추가합니다.
 *
 * 현재 위치 주소(CurrentLocationInfo.address)에 시·군·구가 들어 있어 별도 지오코딩이 필요 없습니다.
 * 쉼터 좌표는 assets/rest_stops.json에서 읽으며, 좌표가 없는 쉼터는 고르지 않습니다.
 * 좌표로 고를 쉼터가 없으면, 길 안내 중일 때 내비 길 안내 정보(TBT)에 나오는 경로 위 휴게소·졸음쉼터를 대신 씁니다.
 */
class PleosNavigator(
    context: Context,
    private val main: Handler,
    private val restStops: List<RestStopPoint>,
    private val sink: Sink,
) : RestNavigator {

    interface Sink {
        fun onRegion(region: String)
        fun onWaypointArrived()
        fun onNaviError(message: String)
    }

    private val navi = NaviHelper(context)
    private var ready = false
    private var routeId: String? = null
    private var ahead = emptyList<AheadRest>()
    private var guidanceAt = 0L
    private var pending: RestStop? = null
    private var confirmed: RestStop? = null
    private var completion: ((Boolean) -> Unit)? = null
    private val timeout = Runnable { resolve(false) }

    private fun resolve(ok: Boolean) {
        main.removeCallbacks(timeout)
        if (ok) confirmed = pending
        pending = null
        val done = completion
        completion = null
        done?.invoke(ok)
    }
    private fun clearGuidance() { ahead = emptyList(); guidanceAt = 0L }

    private val listener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            main.post {
                if (currentLocationInfo.routeId != routeId) clearGuidance()
                routeId = currentLocationInfo.routeId
                RegionParser.regionOf(currentLocationInfo.address)?.let(sink::onRegion)
            }
        }

        override fun onTBTInfo(tBTInfo: List<TBTInfo>) {
            main.post {
                ahead = tBTInfo.filter { it.type == TurnType.TURN_ENTER_SERVICEAREA || it.type == TurnType.TURN_RESTAREA }
                    .map { AheadRest(it.description, it.distance) }
                guidanceAt = SystemClock.elapsedRealtime()
            }
        }
        override fun onRouteStarted(routeStartInfo: ai.pleos.playground.navi.data.RouteStartInfo) { main.post { clearGuidance() } }
        override fun onReRouteEnded() { main.post { clearGuidance() } }
        override fun onRouteCancelled() { main.post { clearGuidance(); confirmed = null; resolve(false) } }
        override fun onRouteEnded() { main.post { clearGuidance(); resolve(false) } }

        override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) {
            main.post { if (pending != null || confirmed != null) runCatching { navi.getWaypointInfo() } }
        }
        override fun onWayPointInfo(waypointInfo: List<WaypointInfo>) {
            main.post {
                val target = pending ?: return@post
                if (waypointInfo.any { abs(it.latitude - target.latitude!!) < 0.0003 && abs(it.longitude - target.longitude!!) < 0.0003 }) {
                    resolve(true)
                }
            }
        }

        override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) {
            main.post {
                val target = confirmed ?: return@post
                if (waypointArrivedInfo.name.replace(" ", "") == target.name.replace(" ", "")) {
                    confirmed = null
                    sink.onWaypointArrived()
                }
            }
        }

        override fun onError(naviErrorCode: ai.pleos.playground.navi.constants.NaviErrorCode) {
            main.post { sink.onNaviError(naviErrorCode.toString()); if (pending != null) resolve(false) }
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!ready) return
            runCatching { navi.getCurrentLocationInfo() }
            runCatching { navi.getTBTInfo() }
            if (pending != null) runCatching { navi.getWaypointInfo() }
            main.postDelayed(this, POLL_MS)
        }
    }

    fun start(onReady: (Boolean) -> Unit) {
        runCatching {
            navi.initialize(
                {
                    main.post {
                        ready = true
                        navi.addListener(listener)
                        main.post(poll)
                        onReady(true)
                    }
                },
                { error -> main.post { sink.onNaviError(error.message ?: "NaviHelper 초기화 실패"); onReady(false) } },
            )
        }.onFailure {
            sink.onNaviError(it.message ?: "NaviHelper 초기화 실패")
            onReady(false)
        }
    }

    fun release() {
        ready = false
        clearGuidance(); resolve(false); confirmed = null
        main.removeCallbacks(poll)
        runCatching { navi.removeListener(listener) }
        runCatching { navi.release() }
    }

    override fun nextRestStop(): RestStop? {
        if (!ready || SystemClock.elapsedRealtime() - guidanceAt > 15_000) return null
        return RouteRests.next(restStops, ahead)
            ?: ahead.filter { it.metres in 1000.0..50000.0 && it.description.isNotBlank() }.minByOrNull { it.metres }?.let {
                // 내비가 직접 안내하는 곳이지만 POI/방향이 확정되지 않은 경우, 좌표 경로를 요청하지 않습니다.
                RestStop(it.description, "쉼터", it.metres / 1000.0, onRoute = true)
            }
    }

    override fun followExisting(stop: RestStop) {
        if (stop.onRoute && nextRestStop()?.name == stop.name) {
            confirmed = stop
        }
    }

    override fun addWaypoint(stop: RestStop, onResult: (Boolean) -> Unit) {
        if (stop.latitude == null || stop.longitude == null || pending != null || nextRestStop()?.let { it.name == stop.name && it.latitude == stop.latitude && it.longitude == stop.longitude } != true) {
            onResult(false); return
        }
        pending = stop
        completion = onResult
        main.postDelayed(timeout, 15_000)
        runCatching {
            navi.addWaypoint(RequestWaypointInfo(stop.latitude!!, stop.longitude!!, stop.name, WaypointIndex.FIRST, "", "", "", ""))
            navi.getWaypointInfo()
        }.onFailure { resolve(false) }
    }

    companion object { private const val POLL_MS = 5_000L }
}
