package com.gilstory.app.core

/** 정차가 확인될 때만 화면 대답을 허용합니다. 마이크 오류는 예외가 아닙니다. */
object AnswerInputPolicy {
    fun touchAllowed(parked: Boolean) = parked
}
