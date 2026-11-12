package com.gilstory.app.core

enum class DriveGear { PARK, REVERSE, OTHER }

/** 층·기둥 중 읽지 못한 값은 null입니다. 둘 다 없으면 사진만 보냅니다. */
data class ParkingSpot(
    val floor: String?,
    val pillar: String?,
    val photoUri: String? = null,
) {
    val complete get() = floor != null && pillar != null

    fun summary(): String {
        val floorText = floor?.let { "지하 ${it.drop(1)}층" }
        return when {
            floorText != null && pillar != null -> "$floorText · $pillar 기둥 옆"
            floorText != null -> "$floorText · 기둥 번호 확인 필요"
            pillar != null -> "$pillar 기둥 옆 · 층 확인 필요"
            else -> "글자를 읽지 못했어요. 주차 사진을 확인해 주세요"
        }
    }
}

/**
 * 후진(R단)할 때 빌트인캠이 읽은 기둥 글자로 주차 위치를 정해, 주차(P단)를 마치면 한 번 알립니다.
 *
 * - 후진 중에 읽은 값 중 가장 많이 나온 값을 고릅니다 (같으면 최근 값). 지나친 옆 기둥이나 오독을 거르기 위해서입니다.
 * - 앞뒤로 여러 번 움직여도 P단이 될 때 한 번만 알립니다.
 * - 후진 없이 앞으로 세웠으면 P단에서 한 번만 읽습니다.
 * - 출차하며 후진한 뒤 멀리 달렸다면 그때 읽은 값은 버립니다. 다음 주차 위치로 잘못 보내지 않기 위해서입니다.
 * - 앱을 켰을 때 이미 P단이면 알리지 않습니다.
 */
class ParkingLocator(
    private val camera: SignCamera,
    private val notifier: ParkingNotifier,
    private val nowMs: () -> Long,
) {
    private var gear: DriveGear? = null
    private var movedSinceParked = false
    private var reversed = false
    private var sampling = false
    private var leftReverseAtMs: Long? = null
    private var readToken = 0
    private val readings = ArrayDeque<SignFrame>()

    fun onGear(next: DriveGear) {
        if (next == gear) return
        val before = gear
        gear = next
        if (!camera.available) return
        when (next) {
            DriveGear.REVERSE -> {
                dropIfStale()
                movedSinceParked = true
                reversed = true
                startSampling()
            }
            DriveGear.OTHER -> {
                stopSampling()
                if (before == DriveGear.PARK) clearTrip()
                if (before == DriveGear.REVERSE) leftReverseAtMs = nowMs()
                movedSinceParked = true
            }
            DriveGear.PARK -> {
                stopSampling()
                dropIfStale()
                if (movedSinceParked) finishParking()
            }
        }
    }

    fun release() {
        stopSampling()
        readToken += 1
        clearTrip()
    }

    private fun startSampling() {
        if (sampling) return
        sampling = true
        camera.start(::onFrame)
    }

    private fun stopSampling() {
        if (!sampling) return
        sampling = false
        camera.stop()
    }

    private fun onFrame(frame: SignFrame) {
        if (sampling) remember(frame)
    }

    private fun remember(frame: SignFrame) {
        readings.addLast(frame)
        while (readings.size > MAX_READINGS) readings.removeFirst()
    }

    private fun dropIfStale() {
        val left = leftReverseAtMs ?: return
        if (nowMs() - left > STALE_AFTER_MS) clearTrip()
    }

    private fun clearTrip() {
        readings.clear()
        reversed = false
        leftReverseAtMs = null
    }

    private fun finishParking() {
        movedSinceParked = false
        if (reversed) {
            send()
            return
        }
        val token = ++readToken
        camera.readOnce { frame ->
            if (token == readToken && gear == DriveGear.PARK) {
                frame?.let(::remember)
                send()
            }
        }
    }

    private fun send() {
        val parsed = readings.map { it to ParkingSignParser.parse(it.text) }
        val floor = mostCommon(parsed.mapNotNull { it.second.floor })
        val pillar = mostCommon(parsed.mapNotNull { it.second.pillar })
        val photo = parsed.lastOrNull { pillar != null && it.second.pillar == pillar }?.first?.photoUri
            ?: readings.lastOrNull()?.photoUri
        clearTrip()
        notifier.notify(ParkingSpot(floor, pillar, photo))
    }

    private fun mostCommon(values: List<String>): String? {
        val counts = values.groupingBy { it }.eachCount()
        return values.lastOrNull { counts[it] == counts.values.maxOrNull() }
    }

    companion object {
        const val MAX_READINGS = 40
        const val STALE_AFTER_MS = 90_000L
    }
}

data class SignReading(val floor: String?, val pillar: String?)

/** 문자 인식 결과에서 층(B2, 지하 2층)과 기둥 번호(C-17, C17)를 찾습니다. */
object ParkingSignParser {
    private val koreanFloor = Regex("지하\\s*([1-9])\\s*층?")
    private val latinFloor = Regex("(?<![A-Z0-9])B\\s?([1-9])(?![0-9])(?:층|F)?")
    private val pillar = Regex("(?<![A-Z0-9])([A-H])\\s*[-–—]?\\s*([0-9ILO]{1,3})(?![0-9A-Z])")

    fun parse(text: String): SignReading {
        val upper = text.uppercase()
        val floorMatch = koreanFloor.find(upper) ?: latinFloor.find(upper)
        val rest = floorMatch?.let { upper.removeRange(it.range) } ?: upper
        return SignReading(floorMatch?.let { "B${it.groupValues[1]}" }, findPillar(rest))
    }

    private fun findPillar(text: String): String? {
        for (match in pillar.findAll(text)) {
            val raw = match.groupValues[2]
            if (raw.none(Char::isDigit)) continue
            val number = raw.replace('I', '1').replace('L', '1').replace('O', '0').toInt()
            if (number > 0) return "${match.groupValues[1]}-${number.toString().padStart(2, '0')}"
        }
        return null
    }
}
