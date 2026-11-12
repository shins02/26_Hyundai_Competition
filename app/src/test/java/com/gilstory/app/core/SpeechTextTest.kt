package com.gilstory.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTextTest {

    @Test
    fun `문장 단위로 나눈다`() {
        val parts = SpeechText.sentences("지금 지나는 용인에는 토성이 있어요. 처인성이에요! 누가 지켰을까요?")
        assertEquals(listOf("지금 지나는 용인에는 토성이 있어요.", "처인성이에요!", "누가 지켰을까요?"), parts)
    }

    @Test
    fun `긴 문장은 쉼표에서 한 번 더 나눈다`() {
        val long = "1232년, 세계에서 가장 강하던 몽골 제국의 군대가 고려로 쳐들어왔고, 몇 해 뒤 유럽까지 휩쓸게 되는 그 군대를 이끌던 장수의 이름은 살리타였어요."
        val parts = SpeechText.sentences(long)
        assertTrue(parts.size >= 2)
        assertTrue(parts.all { it.length <= 70 })
        assertEquals(long, parts.joinToString(" "))
    }

    @Test
    fun `질문 뒤에는 더 길게 쉰다`() {
        assertTrue(SpeechText.pauseAfter("누구였을까요?") > SpeechText.pauseAfter("처인성이에요."))
    }
}
