package com.gilstory.app.platform.demo

import android.os.Handler
import com.gilstory.app.core.CamFacing
import com.gilstory.app.core.DriveGear
import com.gilstory.app.core.ParkingLocator
import com.gilstory.app.core.ParkingNotifier
import com.gilstory.app.core.SignCamera
import com.gilstory.app.core.SignFrame

/**
 * 시연용 주차: 통로 주행 → 1차 후진 → 앞으로 빼서 고쳐 대기 → 2차 후진 → P단.
 *
 * 한 번에 대지 못하고 고쳐 대는 경우를 보여 줍니다. 후진할 때는 뒤쪽 카메라, 앞으로 갈 때는 앞쪽 카메라가
 * 기둥 글자를 읽고, P단에서 많이·최근에 읽은 기둥(C-17)으로 주차 위치를 정합니다.
 * 빌트인캠 영상과 기어 값을 모두 정해진 값으로 넣습니다. 실제 기어·카메라 신호와 섞이지 않게 따로 돌립니다.
 */
class ParkingDemo(
    private val main: Handler,
    private val notifier: ParkingNotifier,
    private val onStep: (String) -> Unit,
    private val scene: Scene? = null,
    private val nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    /** 화면(가상 주차장 카메라)에 보여 줄 장면 */
    interface Scene {
        fun aisle()
        /** [final]이 false면 1차 후진(중간까지), true면 마지막 후진(멈춤턱까지) */
        fun reverse(durationMs: Long, final: Boolean)
        fun forward(durationMs: Long)
        fun read(text: String, facing: CamFacing)
    }

    private var camera: ScriptedCamera? = null
    private var locator: ParkingLocator? = null
    private val pending = mutableListOf<Runnable>()

    fun start() {
        stop()
        val scripted = ScriptedCamera()
        val run = ParkingLocator(scripted, notifier, nowMs)
        camera = scripted
        locator = run

        fun at(steps: Double, block: () -> Unit) = later((steps * STEP_MS).toLong(), block)
        fun see(text: String) {
            val facing = scripted.facing ?: return
            onStep("📷 [시연] ${if (facing == CamFacing.FRONT) "앞쪽" else "뒤쪽"} 카메라 글자: $text")
            scene?.read(text, facing)
            scripted.emit(SignFrame(text))
        }

        run.onGear(DriveGear.OTHER)
        onStep("🚗 [시연] D단 · 주차장 통로 주행 (앞쪽 카메라로 글자 읽기)")
        scene?.aisle()
        at(1.0) { see("B2 C-15") }
        at(2.0) {
            onStep("⏪ [시연] R단 · 1차 후진 (뒤쪽 카메라)")
            run.onGear(DriveGear.REVERSE)
            scene?.reverse(STEP_MS * 2, final = false)
        }
        at(3.0) { see("B2 C-16") }
        at(4.2) {
            onStep("🔁 [시연] D단 · 앞으로 빼서 고쳐 대기 (앞쪽 카메라)")
            run.onGear(DriveGear.OTHER)
            scene?.forward(STEP_MS * 2)
        }
        at(5.2) { see("B2 C-17") }
        at(6.4) {
            onStep("⏪ [시연] R단 · 2차 후진 (뒤쪽 카메라)")
            run.onGear(DriveGear.REVERSE)
            scene?.reverse(STEP_MS * 3, final = true)
        }
        at(7.6) { see("B2 C-17") }
        at(9.6) {
            onStep("🅿 [시연] P단 · 주차 완료")
            run.onGear(DriveGear.PARK)
        }
    }

    fun stop() {
        pending.forEach(main::removeCallbacks)
        pending.clear()
        locator?.release()
        locator = null
        camera = null
    }

    private fun later(delayMs: Long, block: () -> Unit) {
        val task = Runnable(block)
        pending += task
        main.postDelayed(task, delayMs)
    }

    private class ScriptedCamera : SignCamera {
        private var onFrame: ((SignFrame) -> Unit)? = null
        var facing: CamFacing? = null
            private set
        override val available = true
        override fun start(facing: CamFacing, onFrame: (SignFrame) -> Unit) {
            this.facing = facing
            this.onFrame = onFrame
        }
        override fun stop() {
            onFrame = null
            facing = null
        }
        override fun readOnce(onResult: (SignFrame?) -> Unit) = onResult(null)
        fun emit(frame: SignFrame) { onFrame?.invoke(frame) }
    }

    private companion object {
        const val STEP_MS = 1_600L
    }
}
