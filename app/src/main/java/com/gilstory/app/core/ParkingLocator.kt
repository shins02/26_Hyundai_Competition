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
 * 주차하며 빌트인캠이 읽은 기둥 글자로 주차 위치를 정해, 주차(P단)를 마치면 한 번 알립니다.
 *
 * - 후진(R단)에는 뒤쪽 카메라, 앞으로 갈 때(D단)는 앞쪽 카메라로 읽습니다.
 *   후진을 2~3번 고쳐 대거나, 앞으로 빼서 다시 대거나, 앞으로 세워도 위치를 정할 수 있습니다.
 * - P단이 되면 주차 직전 읽은 값 가운데 많이, 그리고 최근에 읽은 값을 고릅니다.
 *   (오래된 값일수록 덜 셉니다.) 지나친 옆 기둥이나 오독을 거르기 위해서입니다.
 * - 앞쪽 카메라 값은 P단 직전 [FRONT_WINDOW_MS] 안의 것만 씁니다. 통로를 지나며 본 먼 기둥은 버립니다.
 * - 뒤쪽 카메라 값은 [STALE_AFTER_MS] 안의 것만 씁니다. 출차하며 후진할 때 읽은 값을
 *   다음 주차 위치로 잘못 보내지 않기 위해서입니다.
 * - 쓸 값이 하나도 없으면 P단에서 앞쪽 카메라로 한 번 더 읽습니다.
 * - 앞뒤로 여러 번 움직여도 P단이 될 때 한 번만 알립니다. 앱을 켰을 때 이미 P단이면 알리지 않습니다.
 */
class ParkingLocator(
    private val camera: SignCamera,
    private val notifier: ParkingNotifier,
    private val nowMs: () -> Long,
) {
    private class Reading(val frame: SignFrame, val facing: CamFacing, val atMs: Long)

    private var gear: DriveGear? = null
    private var movedSinceParked = false
    private var sampling: CamFacing? = null
    private var readToken = 0
    private val readings = ArrayDeque<Reading>()

    fun onGear(next: DriveGear) {
        if (next == gear) return
        val before = gear
        gear = next
        if (!camera.available) return
        when (next) {
            DriveGear.REVERSE -> {
                movedSinceParked = true
                startSampling(CamFacing.REAR)
            }
            DriveGear.OTHER -> {
                if (before == DriveGear.PARK) readings.clear()
                movedSinceParked = true
                startSampling(CamFacing.FRONT)
            }
            DriveGear.PARK -> {
                stopSampling()
                if (movedSinceParked) finishParking()
            }
        }
    }

    fun release() {
        stopSampling()
        readToken += 1
        readings.clear()
    }

    private fun startSampling(facing: CamFacing) {
        if (sampling == facing) return
        stopSampling()
        sampling = facing
        camera.start(facing, ::onFrame)
    }

    private fun stopSampling() {
        if (sampling == null) return
        sampling = null
        camera.stop()
    }

    private fun onFrame(frame: SignFrame) {
        val facing = sampling ?: return
        remember(Reading(frame, facing, nowMs()))
    }

    private fun remember(reading: Reading) {
        readings.addLast(reading)
        while (readings.size > MAX_READINGS) readings.removeFirst()
    }

    private fun finishParking() {
        movedSinceParked = false
        val now = nowMs()
        val recent = readings.filter {
            now - it.atMs <= if (it.facing == CamFacing.FRONT) FRONT_WINDOW_MS else STALE_AFTER_MS
        }
        if (recent.isNotEmpty()) {
            send(recent, now)
            return
        }
        val token = ++readToken
        camera.readOnce { frame ->
            if (token == readToken && gear == DriveGear.PARK) send(listOfNotNull(frame?.let { Reading(it, CamFacing.FRONT, nowMs()) }), nowMs())
        }
    }

    private fun send(used: List<Reading>, now: Long) {
        val parsed = used.map { it to ParkingSignParser.parse(it.frame.text) }
        val floor = best(parsed.mapNotNull { (r, s) -> s.floor?.let { it to r.atMs } }, now)
        val pillar = best(parsed.mapNotNull { (r, s) -> s.pillar?.let { it to r.atMs } }, now)
        // 알림 사진: 고른 기둥이 찍힌 마지막 사진, 없으면 마지막 사진
        val photo = parsed.lastOrNull { pillar != null && it.second.pillar == pillar && it.first.frame.photoUri != null }?.first?.frame?.photoUri
            ?: used.lastOrNull { it.frame.photoUri != null }?.frame?.photoUri
        readings.clear()
        notifier.notify(ParkingSpot(floor, pillar, photo))
    }

    /** 읽은 횟수에 최근일수록 큰 무게를 줘서 고릅니다. 같으면 마지막에 읽은 값 */
    private fun best(values: List<Pair<String, Long>>, now: Long): String? {
        if (values.isEmpty()) return null
        val score = HashMap<String, Double>()
        for ((value, at) in values) {
            score[value] = (score[value] ?: 0.0) + Math.pow(0.5, (now - at).toDouble() / HALF_LIFE_MS)
        }
        val top = score.values.max()
        return values.last { score[it.first]!! >= top - 1e-9 }.first
    }

    companion object {
        const val MAX_READINGS = 60
        /** 뒤쪽 카메라 값을 쓰는 기간 (출차 때 읽은 값 거르기) */
        const val STALE_AFTER_MS = 90_000L
        /** 앞쪽 카메라 값을 쓰는 기간: P단 직전 */
        const val FRONT_WINDOW_MS = 20_000L
        /** 이만큼 지난 값은 절반만 셉니다 */
        const val HALF_LIFE_MS = 10_000.0
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
