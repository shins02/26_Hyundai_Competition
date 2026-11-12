package com.gilstory.app.platform

import android.os.Handler
import com.gilstory.app.core.Speaker
import com.gilstory.app.core.SpeechText

/**
 * 어떤 TTS 엔진이든 감싸서, 문장 단위로 나눠 읽고 문장 사이에 사람처럼 쉽니다.
 * 긴 문단을 한 번에 넘기면 억양이 단조로워지고, 일부 엔진은 중간에 끊기기도 합니다.
 */
class NaturalSpeaker(
    private val engine: Speaker,
    private val main: Handler,
) : Speaker {

    private var token = 0

    override fun speak(text: String, onDone: () -> Unit) {
        val my = ++token
        val queue = ArrayDeque(SpeechText.sentences(text))
        if (queue.isEmpty()) {
            onDone()
            return
        }
        fun next() {
            if (my != token) return
            val sentence = queue.removeFirstOrNull() ?: return onDone()
            engine.speak(sentence) {
                if (my != token) return@speak
                if (queue.isEmpty()) onDone() else main.postDelayed({ next() }, SpeechText.pauseAfter(sentence))
            }
        }
        next()
    }

    override fun stop() {
        token++
        engine.stop()
    }
}
