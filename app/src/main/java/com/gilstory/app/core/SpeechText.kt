package com.gilstory.app.core

/**
 * 사람처럼 들리게 읽기 위한 문장 나누기.
 * 한 번에 길게 읽으면 기계처럼 들려서, 문장 단위로 나눠 읽고 문장 사이에 잠깐 쉽니다.
 */
object SpeechText {

    private val sentenceEnd = Regex("(?<=[.?!…])\\s+")
    private val commaBreak = Regex("(?<=,)\\s+")

    fun sentences(text: String): List<String> {
        val out = mutableListOf<String>()
        for (sentence in text.replace(Regex("\\s+"), " ").trim().split(sentenceEnd)) {
            if (sentence.length <= 70) {
                if (sentence.isNotBlank()) out += sentence
                continue
            }
            // 긴 문장은 쉼표에서 한 번 더 나눠 숨 쉴 틈을 줍니다
            var buf = ""
            for (piece in sentence.split(commaBreak)) {
                buf = if (buf.isNotEmpty() && "$buf $piece".length > 55) {
                    out += buf
                    piece
                } else {
                    "$buf $piece".trim()
                }
            }
            if (buf.isNotBlank()) out += buf
        }
        return out
    }

    /** 문장 뒤 쉬는 시간(ms). 질문 뒤에는 조금 더 길게 쉽니다. */
    fun pauseAfter(sentence: String): Long = when {
        sentence.endsWith("?") -> 520
        sentence.endsWith(".") || sentence.endsWith("!") || sentence.endsWith("…") -> 380
        else -> 170
    }
}
