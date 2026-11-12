package com.gilstory.app.platform.pleos

import android.content.Context
import android.os.Handler
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.DrivingTimeListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import com.gilstory.app.core.DriveGear

/**
 * Pleos Vehicle SDK에서 졸음 판단과 장르 선택에 쓰는 신호를 받습니다. 모두 읽기 전용입니다.
 *
 * - Safety: 운전자 주의 경고 Telltale (DRIVER_ATTENTION)
 * - Odometer: 이번 주행의 운전 시간, 누적 주행 거리 (이어 듣기에서 결말을 10~15km 뒤에 들려줄 때)
 * - Gear: P단 여부 (정차 중에만 설정 화면 열기, 쉼터에서 다시 출발 감지), R단 (주차 위치 글자 인식 시작)
 * - Light: 전조등(로우빔) 상태 → 밤 판단
 * - Seat: 뒷좌석 착석 → 오싹한 이야기 전에 동승자 확인
 *
 * SDK 콜백은 IPC 스레드에서 올 수 있어 메인 스레드로 넘깁니다.
 * 차종에 따라 지원하지 않는 기능은 onFailed로 오며, 그 신호만 빼고 동작합니다.
 */
class PleosVehicleSignals(
    context: Context,
    private val main: Handler,
    private val sink: Sink,
) {

    interface Sink {
        fun onDriverAttention(on: Boolean)
        fun onDrivingMinutes(minutes: Int)
        fun onDistanceKm(km: Double)
        fun onPark(parked: Boolean?)
        fun onGear(gear: DriveGear)
        fun onHeadlamp(on: Boolean)
        fun onRearOccupied(occupied: Boolean)
        fun onSignalError(name: String, error: Exception)
    }

    private val vehicle = Vehicle(context)
    private val rearSeats = SeatArea(SeatValue.ROW_2_LEFT, SeatValue.ROW_2_CENTER, SeatValue.ROW_2_RIGHT)

    private val telltaleListener = object : TelltaleInfoListener {
        override fun onTelltaleInfoUpdated(p0: Telltales?) { p0?.let(::handleTelltales) }
        override fun onFailed(p0: Exception) = fail("경고등", p0)
    }

    private val drivingTimeListener = object : DrivingTimeListener {
        override fun onDrivingTimeUpdated(p0: Int?) {
            val value = p0 ?: return
            post { sink.onDrivingMinutes(toMinutes(value)) }
        }
        override fun onFailed(p0: Exception) = fail("주행 시간", p0)
    }

    private val distanceListener = object : DistanceDrivenListener {
        override fun onDistanceDrivenUpdated(p0: Float?) {
            val value = p0 ?: return
            post { sink.onDistanceKm(value.toDouble()) }
        }
        override fun onFailed(p0: Exception) = fail("주행 거리", p0)
    }

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(p0: VehicleGear?) = handleGear(p0)
        override fun onFailed(p0: Exception) = fail("기어", p0)
    }

    private val lightListener = object : LightStateListener {
        override fun onLightStateUpdated(p0: LightState?) {
            post { sink.onHeadlamp(p0 == LightState.ON) }
        }
        override fun onFailed(p0: Exception) = fail("전조등", p0)
    }

    private val seatListener = object : SeatOccupancyListener {
        override fun onSeatOccupancyUpdated(p0: Boolean?) {
            post { sink.onRearOccupied(p0 == true) }
        }
        override fun onFailed(p0: Exception) = fail("좌석", p0)
    }

    fun start() {
        runCatching { vehicle.initialize() }.onFailure { fail("Vehicle SDK 연결", it.asException()) }

        attempt("경고등") {
            vehicle.getSafety().getTelltalesInfo({ it?.let(::handleTelltales) }, { fail("경고등", it) })
            vehicle.getSafety().registerTelltales(telltaleListener)
        }
        attempt("주행 시간") {
            vehicle.getOdometer().getTripCurrentDrivingTime({ v -> if (v != null) post { sink.onDrivingMinutes(toMinutes(v)) } }, { fail("주행 시간", it) })
            vehicle.getOdometer().registerTripCurrentDrivingTime(drivingTimeListener)
        }
        // [확인 필요] 누적 주행 거리 단위를 km로 가정합니다. 에뮬레이터 Virtual Console 값으로 확인하세요.
        attempt("주행 거리") {
            vehicle.getOdometer().getCurrentDistanceDriven({ v -> if (v != null) post { sink.onDistanceKm(v.toDouble()) } }, { fail("주행 거리", it) })
            vehicle.getOdometer().registerDistanceDriven(distanceListener)
        }
        attempt("기어") {
            vehicle.getGear().getCurrentGearSelectionState({ g -> handleGear(g) }, { fail("기어", it) })
            vehicle.getGear().registerGearSelection(gearListener)
        }
        attempt("전조등") {
            vehicle.getLight().getCurrentLowBeamLightState({ s -> post { sink.onHeadlamp(s == LightState.ON) } }, { fail("전조등", it) })
            vehicle.getLight().registerLowBeamLightState(lightListener)
        }
        attempt("좌석") {
            vehicle.getSeat().getCurrentSeatOccupancy(rearSeats, { o -> post { sink.onRearOccupied(o == true) } }, { fail("좌석", it) })
            vehicle.getSeat().registerSeatOccupancy(rearSeats, seatListener)
        }
    }

    fun release() {
        runCatching { vehicle.getSafety().unregisterTelltales(telltaleListener) }
        runCatching { vehicle.getOdometer().unregisterTripCurrentDrivingTime(drivingTimeListener) }
        runCatching { vehicle.getOdometer().unregisterDistanceDriven(distanceListener) }
        runCatching { vehicle.getGear().unregisterGearSelection(gearListener) }
        runCatching { vehicle.getLight().unregisterLowBeamLightState(lightListener) }
        runCatching { vehicle.getSeat().unregisterSeatOccupancy(rearSeats, seatListener) }
        runCatching { vehicle.release() }
    }

    private fun handleGear(gear: VehicleGear?) = post {
        sink.onPark(gear?.let { it == VehicleGear.GEAR_PARK })
        when (gear) {
            VehicleGear.GEAR_PARK -> sink.onGear(DriveGear.PARK)
            VehicleGear.GEAR_REVERSE -> sink.onGear(DriveGear.REVERSE)
            VehicleGear.GEAR_DRIVE, VehicleGear.GEAR_NEUTRAL -> sink.onGear(DriveGear.OTHER)
            else -> Unit
        }
    }

    private fun handleTelltales(telltales: Telltales) {
        // 목록에 운전자 주의 경고가 있을 때만 상태를 바꿉니다 (없으면 이전 상태 유지)
        val attention = telltales.telltales.firstOrNull { it.type == TelltaleType.DRIVER_ATTENTION } ?: return
        val on = attention.state != TelltaleState.OFF
        post { sink.onDriverAttention(on) }
    }

    /** [확인 필요] 주행 시간 단위가 초라고 가정합니다. 실차·에뮬레이터 값으로 확인 후 조정하세요. */
    private fun toMinutes(value: Int): Int = if (DRIVING_TIME_IN_SECONDS) value / 60 else value

    private inline fun attempt(name: String, block: () -> Unit) {
        runCatching(block).onFailure { fail(name, it.asException()) }
    }

    private fun fail(name: String, error: Exception) = post { sink.onSignalError(name, error) }

    private fun post(block: () -> Unit) {
        main.post(block)
    }

    private fun Throwable.asException() = this as? Exception ?: RuntimeException(this)

    companion object {
        const val DRIVING_TIME_IN_SECONDS = true
    }
}
