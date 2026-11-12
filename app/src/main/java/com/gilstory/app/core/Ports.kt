package com.gilstory.app.core

/** 이야기를 소리 내어 읽습니다. stop() 뒤에는 onDone을 부르지 않아야 합니다. */
interface Speaker {
    fun speak(text: String, onDone: () -> Unit)
    fun stop()
}

/** 운전자의 짧은 대답을 한 번 듣습니다. 시간 안에 못 들으면 null을 돌려줍니다. */
interface AnswerListener {
    val available: Boolean
    fun listen(timeoutMs: Long, onResult: (String?) -> Unit)
    fun cancel()
}

data class RestStop(
    val name: String,
    /** "졸음쉼터" 또는 "휴게소" */
    val kind: String,
    val distanceKm: Double,
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** 이미 안내 중인 경로 위에 있는 쉼터(내비 길 안내 정보에서 찾음). 경유지를 추가하지 않고 그대로 들르면 됩니다 */
    val onRoute: Boolean = false,
)

interface RestNavigator {
    /** 진행 방향 앞쪽의 가장 가까운 쉼터. 모르면 null */
    fun nextRestStop(): RestStop?
    fun followExisting(stop: RestStop) {}
    fun addWaypoint(stop: RestStop, onResult: (Boolean) -> Unit)
}

/** 빌트인캠 한 장면에서 읽은 글자와, 알림에 붙일 표지 주변 사진 */
data class SignFrame(val text: String, val photoUri: String? = null)

/** 빌트인캠에서 기둥 글자를 읽습니다. 영상은 차 안에서만 처리합니다. */
interface SignCamera {
    val available: Boolean
    fun start(onFrame: (SignFrame) -> Unit)
    fun stop()
    fun readOnce(onResult: (SignFrame?) -> Unit)
}

interface ParkingNotifier {
    fun notify(spot: ParkingSpot)
}

interface Clock {
    fun nowMs(): Long
    fun hour(): Int
}
