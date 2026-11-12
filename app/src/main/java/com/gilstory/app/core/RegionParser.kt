package com.gilstory.app.core

/**
 * NaviHelper 현재 위치 주소에서 시·군·구 이름을 뽑습니다.
 * 예: "강원특별자치도 횡성군 둔내면 ..." → "횡성군", "경기도 용인시 처인구 ..." → "용인시"
 */
object RegionParser {

    private val province = Regex("(도|특별자치도|특별시|광역시|특별자치시)$")
    private val district = Regex("(시|군|구)$")

    fun regionOf(address: String?): String? {
        val tokens = address?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: return null
        if (tokens.isEmpty()) return null
        // 특별시·광역시는 첫 토큰이 지역 단위 (예: "서울특별시 강남구" → "서울특별시")
        val first = tokens[0]
        if (first.endsWith("특별시") || first.endsWith("광역시") || first.endsWith("특별자치시")) return first
        return tokens.drop(if (province.containsMatchIn(first)) 1 else 0)
            .firstOrNull { district.containsMatchIn(it) }
    }
}
