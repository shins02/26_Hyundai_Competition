package com.gilstory.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerInputPolicyTest {
    @Test
    fun `주행 중이고 음성 인식이 되면 화면 대답을 막는다`() {
        assertFalse(AnswerInputPolicy.touchAllowed(parked = false))
    }

    @Test
    fun `정차 중이면 화면으로도 대답할 수 있다`() {
        assertTrue(AnswerInputPolicy.touchAllowed(parked = true))
    }

    @Test
    fun `음성 인식과 관계없이 주행 중 터치를 막는다`() {
        assertFalse(AnswerInputPolicy.touchAllowed(parked = false))
    }
}
