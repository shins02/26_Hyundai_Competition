package com.gilstory.app.core

sealed interface Answer {
    data class Option(val index: Int) : Answer
    data object Yes : Answer
    data object No : Answer
    data object Skip : Answer
    data object Stop : Answer
    data class ChangeGenre(val genre: Genre) : Answer
    data object Unknown : Answer
}

/**
 * 짧은 한국어 음성 인식 결과를 대답으로 바꿉니다.
 * 운전 중 대화이므로 "1번", "2번", "응", "아니", "다른 거", "그만", "웃긴 거" 정도만 알아듣습니다.
 */
object AnswerParser {

    private val stopWords = listOf("그만", "멈춰", "꺼 줘", "꺼줘", "조용")
    private val skipWords = listOf("다른 거", "다른거", "다음", "넘겨", "패스")
    private val yesWords = listOf("응", "어", "네", "예", "그래", "좋아", "추가", "콜", "오케이")
    private val noWords = listOf("아니", "싫어", "괜찮아", "됐어", "안 해", "안해", "노")
    private val firstWords = listOf("1번", "일번", "첫 번째", "첫번째", "하나", "1")
    // "이번"은 "이번엔 1번"처럼 쓰여서 넣지 않습니다
    private val secondWords = listOf("2번", "두 번째", "두번째", "둘", "2")

    private val genreWords = mapOf(
        Genre.COMEDY to listOf("웃긴", "개그", "재밌는", "재미있는"),
        Genre.SPOOKY to listOf("무서운", "오싹", "공포", "괴담"),
        Genre.THINK to listOf("생각", "철학", "진지한"),
        Genre.LOCAL to listOf("지역", "동네", "여기 얘기", "여기 이야기"),
    )

    fun parse(raw: String?, options: List<String> = emptyList()): Answer {
        val text = raw?.trim()?.replace(Regex("[.?!,]"), "") ?: return Answer.Unknown
        if (text.isEmpty()) return Answer.Unknown

        if (options == listOf("네", "아니요")) {
            val exact = text.replace(" ", "")
            return when (exact) {
                "네", "예", "응", "그래", "좋아", "추가해줘", "1번", "일번", "1" -> Answer.Yes
                "아니요", "아니", "싫어", "괜찮아", "2번", "이번", "2" -> Answer.No
                "그만", "멈춰" -> Answer.Stop
                else -> Answer.Unknown
            }
        }
        if (stopWords.any { text.contains(it) }) return Answer.Stop
        genreWords.forEach { (genre, words) -> if (words.any { text.contains(it) }) return Answer.ChangeGenre(genre) }
        if (skipWords.any { text.contains(it) }) return Answer.Skip

        // 선택지 내용을 그대로 말한 경우 ("도자기")
        options.forEachIndexed { i, option ->
            if (option.isNotBlank() && text.contains(option.trim())) return Answer.Option(i)
        }
        if (matchesNumber(text, secondWords)) return Answer.Option(1)
        if (matchesNumber(text, firstWords)) return Answer.Option(0)

        if (noWords.any { text.startsWith(it) || text == it }) return Answer.No
        if (yesWords.any { text.startsWith(it) || text == it }) return Answer.Yes
        return Answer.Unknown
    }

    // "2번"에 "1"이 들어가 오인식되지 않도록, 숫자 한 글자는 단독 발화일 때만 인정합니다
    private fun matchesNumber(text: String, words: List<String>): Boolean =
        words.any { w -> if (w.length == 1) text == w else text.contains(w) }
}
