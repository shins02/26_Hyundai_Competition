package com.gilstory.app.platform.fallback

import android.content.Context
import android.os.Handler
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.gilstory.app.core.AnswerListener
import com.gilstory.app.core.SignCamera
import com.gilstory.app.core.SignFrame
import com.gilstory.app.core.Speaker
import java.util.Locale

/**
 * 현재 Pleos SDK(Vehicle 2.3.2)에서 빌트인캠 영상을 받는 API를 찾지 못했습니다.
 * API가 제공되면 이 자리에 실제 카메라를 연결합니다.
 */
object NoSignCamera : SignCamera {
    override val available = false
    override fun start(onFrame: (SignFrame) -> Unit) = Unit
    override fun stop() = Unit
    override fun readOnce(onResult: (SignFrame?) -> Unit) = onResult(null)
}

/**
 * Pleos TTS를 쓸 수 없는 환경(일반 에뮬레이터 등)을 위한 안드로이드 기본 TTS.
 * 한국어 음성이 없으면 소리 없이 글자 수에 맞춰 기다렸다가 다음으로 넘어갑니다.
 */
class AndroidSpeaker(context: Context, private val main: Handler) : Speaker {

    private var ready = false
    private var onDone: (() -> Unit)? = null
    private var utterance = 0
    private val silentDone = Runnable { finish() }

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS &&
            tts.setLanguage(Locale.KOREAN) >= TextToSpeech.LANG_AVAILABLE
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finish()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish()
        })
    }

    override fun speak(text: String, onDone: () -> Unit) {
        this.onDone = onDone
        if (ready) {
            utterance += 1
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "gilstory-$utterance")
        } else {
            // 소리 없는 환경: 한 글자에 약 0.12초로 계산
            main.postDelayed(silentDone, (text.length * 120L).coerceIn(1_500L, 20_000L))
        }
    }

    override fun stop() {
        onDone = null
        main.removeCallbacks(silentDone)
        tts.stop()
    }

    fun release() {
        stop()
        tts.shutdown()
    }

    private fun finish() {
        main.post {
            val callback = onDone
            onDone = null
            callback?.invoke()
        }
    }
}

/** 음성 인식이 없으면 화면 버튼으로만 대답합니다. */
object ButtonOnlyListener : AnswerListener {
    override val available = false
    override fun listen(timeoutMs: Long, onResult: (String?) -> Unit) = Unit
    override fun cancel() = Unit
}

/** Pleos TTS가 준비되면 그쪽으로, 아니면 안드로이드 TTS로 읽습니다. 읽은 문장은 시연 로그에 남깁니다. */
class SwitchingSpeaker(
    private var active: Speaker,
    private val onSpoken: (String) -> Unit,
) : Speaker {

    fun switchTo(speaker: Speaker) {
        active.stop()
        active = speaker
    }

    override fun speak(text: String, onDone: () -> Unit) {
        onSpoken(text)
        active.speak(text, onDone)
    }

    override fun stop() = active.stop()
}

/** 시연 중에는 데모 대답 장치, 실제로는 Pleos 음성 인식을 씁니다. */
class SwitchingListener(private var active: AnswerListener) : AnswerListener {
    fun switchTo(listener: AnswerListener) {
        active.cancel()
        active = listener
    }

    override val available: Boolean get() = active.available
    override fun listen(timeoutMs: Long, onResult: (String?) -> Unit) = active.listen(timeoutMs, onResult)
    override fun cancel() = active.cancel()
}
