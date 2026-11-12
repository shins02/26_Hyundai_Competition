package com.gilstory.app.core

enum class Genre(val label: String, val emoji: String) {
    COMEDY("개그", "😂"),
    SPOOKY("오싹한 이야기", "👻"),
    THINK("생각하는 이야기", "🤔"),
    LOCAL("지역 이야기", "🗺️"),
}

enum class InteractionType {
    /** 정답이 있는 퀴즈 (넌센스, 지역 퀴즈) */
    QUIZ,

    /** 고른 쪽에 따라 결말이 갈리는 이야기 (오싹한 이야기) */
    CHOICE,

    /** 정답 없이 둘 중 하나를 고르는 밸런스 게임 (생각하는 이야기) */
    BALANCE,
}

data class Interaction(
    val type: InteractionType,
    val question: String,
    val options: List<String>,
    /** 선택지별 후속 멘트. QUIZ는 정답/오답 해설, CHOICE는 결말 */
    val responses: List<String>,
    /** QUIZ의 정답 인덱스 (0부터) */
    val answer: Int? = null,
) {
    init {
        require(options.size == 2) { "선택지는 2개만 씁니다 (운전 중 한 번에 고를 수 있게)" }
        require(responses.size == options.size) { "선택지마다 후속 멘트가 필요합니다" }
    }
}

data class Story(
    val id: String,
    val genre: Genre,
    /** 시·군·구 이름 (예: "횡성군"). null이면 어느 지역에서나 쓰는 공용 이야기 */
    val region: String?,
    val title: String,
    val text: String,
    val interaction: Interaction? = null,
    /** 사실이 아닌 창작 이야기 여부. 오싹한 이야기는 항상 창작임을 밝힙니다 */
    val fiction: Boolean = false,
    val ending: String = text,
)
