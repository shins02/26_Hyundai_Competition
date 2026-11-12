package com.gilstory.app.platform.demo

import android.os.Handler
import com.gilstory.app.core.DriveGear
import com.gilstory.app.core.ParkingLocator
import com.gilstory.app.core.ParkingNotifier
import com.gilstory.app.core.SignCamera
import com.gilstory.app.core.SignFrame

/**
 * 시연용 후진 주차: 통로 주행 → R단 → 기둥 글자 인식 → P단.
 *
 * 빌트인캠 영상과 기어 값을 모두 정해진 값으로 넣습니다. 실제 기어·카메라 신호와 섞이지 않게 따로 돌립니다.
 */
class ParkingDemo(
    private val main: Handler,
    private val notifier: ParkingNotifier,
    private val onStep: (String) -> Unit,
) {
    private val frames = listOf("B2 C-16", "B2 C-17", "B2 C-17")
    private var camera: ScriptedCamera? = null
    private var locator: ParkingLocator? = null
    private val pending = mutableListOf<Runnable>()

    fun start() {
        stop()
        val scripted = ScriptedCamera()
        val run = ParkingLocator(scripted, notifier) { 0L }
        camera = scripted
        locator = run

        run.onGear(DriveGear.OTHER)
        onStep("🚗 [시연] D단 · 주차장 통로 주행 (글자 인식 꺼짐)")
        later(STEP_MS) {
            onStep("⏪ [시연] R단 감지 · 빌트인캠 글자 인식 시작")
            run.onGear(DriveGear.REVERSE)
        }
        frames.forEachIndexed { i, text ->
            later(STEP_MS * (i + 2)) {
                onStep("📷 [시연] 기둥 글자: $text")
                scripted.emit(SignFrame(text))
            }
        }
        later(STEP_MS * (frames.size + 2)) {
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
        override val available = true
        override fun start(onFrame: (SignFrame) -> Unit) { this.onFrame = onFrame }
        override fun stop() { onFrame = null }
        override fun readOnce(onResult: (SignFrame?) -> Unit) = onResult(null)
        fun emit(frame: SignFrame) { onFrame?.invoke(frame) }
    }

    private companion object {
        const val STEP_MS = 1_200L
    }
}
