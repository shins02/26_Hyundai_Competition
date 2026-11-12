package com.gilstory.app.core

/**
 * 장르 × 지역 × 이미 들은 이야기를 보고 다음 이야기를 고릅니다.
 * 같은 장르 안에서 "지금 지나는 지역" 이야기를 먼저, 없으면 공용 이야기를 고릅니다.
 */
class StoryPlanner(private val stories: List<Story>) {

    fun next(genre: Genre, region: String?, played: Set<String>, requireInteraction: Boolean = false): Story? {
        val candidates = stories.filter { it.genre == genre && it.id !in played }
            .filter { !requireInteraction || it.interaction != null }
        return candidates.firstOrNull { region != null && it.region == region }
            ?: candidates.firstOrNull { it.region == null }
    }

    /** 지역 이야기가 있는 장르를 우선해서 고르고 싶을 때 */
    fun hasRegional(genre: Genre, region: String?, played: Set<String>): Boolean =
        region != null && stories.any { it.genre == genre && it.region == region && it.id !in played }

    fun byId(id: String): Story? = stories.firstOrNull { it.id == id }
}
