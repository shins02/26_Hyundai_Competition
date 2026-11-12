package com.gilstory.app.core

data class RestStopPoint(val name: String, val kind: String, val direction: String, val latitude: Double?, val longitude: Double?)

data class AheadRest(val description: String, val metres: Double)

/** 후보는 현재 경로의 휴게소 안내에서만 온다. 좌표·직선거리만으로 반대편 시설을 고르지 않는다. */
object RouteRests {
    fun next(points: List<RestStopPoint>, ahead: List<AheadRest>): RestStop? =
        ahead.asSequence().filter { it.metres.isFinite() && it.metres in 1000.0..50000.0 }
            .sortedBy { it.metres }.mapNotNull { guide ->
                points.firstOrNull { point ->
                    point.latitude != null && point.longitude != null && point.matches(guide.description)
                }?.let { RestStop(it.name, it.kind, guide.metres / 1000.0, it.latitude, it.longitude) }
            }.firstOrNull()

    private fun RestStopPoint.matches(description: String): Boolean {
        val base = Regex.escape(name.removeSuffix("휴게소").removeSuffix("자연"))
        val separator = """[\s()（）·-]*"""
        val facility = "${base}${separator}(?:자연)?${separator}휴게소"
        val directionLabel = "${Regex.escape(direction)}(?:방향|방면)?"
        val expression = if (direction == "양방향") facility else {
            if ("인천" in description) return false
            "(?:$facility$separator$directionLabel|${base}$separator$directionLabel${separator}휴게소)"
        }
        // e.g. 서여주휴게소 must not resolve to 여주휴게소 on another road.
        return Regex("(?<![가-힣A-Za-z0-9])$expression(?![가-힣A-Za-z0-9])").containsMatchIn(description)
    }
}
