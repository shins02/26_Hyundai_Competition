package com.gilstory.app.core

/** ① 길동무(평시) ② 깨우기(주의) ③ 쉼터 안내(경고) */
enum class Stage { COMPANION, WAKE, REST }

data class DriverSignals(
    /** 이번 주행의 연속 운전 시간(분) */
    val drivingMinutes: Int = 0,
    /** 차량 운전자 주의 경고(Telltale DRIVER_ATTENTION) 점등 여부 */
    val driverAttentionWarning: Boolean = false,
    /** 현재 시각 (0~23시) */
    val hour: Int = 12,

)

/**
 * 졸음 위험 점수로 단계를 정합니다.
 *
 * 의학적 진단이 아니라 "쉬라고 권할 타이밍"을 정하는 규칙입니다.
 * 가중치는 초안이며, 시연과 사용자 테스트로 조정합니다.
 */
class DrowsinessScorer(
    private val wakeThreshold: Int = 40,
    private val restThreshold: Int = 80,
) {

    fun score(s: DriverSignals): Int {
        var score = 0

        // 연속 운전 시간: 90분부터 오르기 시작, 120분이면 쉼터 안내 수준
        score += when {
            s.drivingMinutes >= 120 -> 80
            s.drivingMinutes >= 90 -> 40 + (s.drivingMinutes - 90) * 4 / 3
            s.drivingMinutes >= 60 -> 20
            else -> 0
        }

        // 졸음이 몰리기 쉬운 시간대 (새벽, 이른 오후)
        if (s.hour in 2..5) score += 25
        if (s.hour in 14..15) score += 10

        return score
    }

    fun stage(s: DriverSignals): Stage {
        // 차량 졸음 경고는 점수와 관계없이 바로 쉼터 안내
        if (s.driverAttentionWarning) return Stage.REST
        val score = score(s)
        return when {
            score >= restThreshold -> Stage.REST
            score >= wakeThreshold -> Stage.WAKE
            else -> Stage.COMPANION
        }
    }

}
