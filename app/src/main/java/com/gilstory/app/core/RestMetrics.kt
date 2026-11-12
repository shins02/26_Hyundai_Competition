package com.gilstory.app.core

/**
 * 쉼터 권유가 실제 휴식으로 이어졌는지 세는 지표입니다.
 *
 * 기획서의 성공 지표(쉼터 권유 수락률, 쉼터 도착률, 졸음 경고 뒤 휴식률)를 재는 데 씁니다.
 * 횟수만 세고 위치나 시간 기록은 남기지 않습니다.
 */
data class RestMetrics(
    /** 구체적인 쉼터를 골라 권한 횟수 */
    val offered: Int = 0,
    /** 그중 차량 졸음 경고가 켜진 상태에서 권한 횟수 */
    val offeredOnWarning: Int = 0,
    val accepted: Int = 0,
    val declined: Int = 0,
    val unanswered: Int = 0,
    /** 받아들인 쉼터에 도착한 횟수 */
    val arrived: Int = 0,
    /** 졸음 경고 중에 받아들인 쉼터에 도착한 횟수 */
    val arrivedOnWarning: Int = 0,
) {
    val acceptRate: Double get() = ratio(accepted, offered)
    val arrivalRate: Double get() = ratio(arrived, accepted)
    val warningRestRate: Double get() = ratio(arrivedOnWarning, offeredOnWarning)

    operator fun plus(o: RestMetrics) = RestMetrics(
        offered + o.offered, offeredOnWarning + o.offeredOnWarning, accepted + o.accepted,
        declined + o.declined, unanswered + o.unanswered, arrived + o.arrived, arrivedOnWarning + o.arrivedOnWarning,
    )

    private fun ratio(a: Int, b: Int) = if (b == 0) 0.0 else a.toDouble() / b
}
