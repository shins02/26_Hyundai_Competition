package com.gilstory.app.platform.pleos

import android.content.Context
import android.os.Handler
import android.util.Log
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import com.gilstory.app.core.AnswerListener
import com.gilstory.app.core.Speaker
import java.util.Locale
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInit
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInit

/**
 * Pleos TextToSpeech. 터널·산간처럼 통신이 끊기는 곳에서도 멈추지 않도록 ON_DEVICE 모드를 씁니다.
 * (HYBRID/SERVER 모드는 Pleos Playground My Project의 앱 등록 정보가 필요합니다)
 */
class PleosSpeaker(context: Context, private val main: Handler) : Speaker {

    private val tts = TextToSpeech(context, TtsMode.ON_DEVICE)
    private var onDone: (() -> Unit)? = null

    fun start(onReady: (Boolean) -> Unit) {
        runCatching {
            // setLocale·리스너 등록은 initialize 성공 뒤에 해야 합니다 (그 전에는 SDK 내부 객체가 없음)
            tts.initialize(object : TtsInit {
                override fun onSuccess() {
                    main.post {
                        val ok = runCatching {
                            tts.setLocale(Locale.KOREA)
                            tts.addEventListener(object : EventListener {
                                override fun onStart() = Unit
                                override fun onReady() = Unit
                                override fun onDone() = finish()
                                // 오류가 나도 흐름이 멈추지 않도록 끝난 것으로 처리합니다
                                override fun onError(p0: TtsError) { Log.w(TAG, "Pleos TTS 오류: $p0"); finish() }
                                override fun onStop() = Unit
                                override fun onUpdatedRms(p0: Double) = Unit
                            })
                        }.onFailure { Log.w(TAG, "Pleos TTS 설정 실패", it) }.isSuccess
                        onReady(ok)
                    }
                }
                override fun onFailure(p0: TtsError) { Log.w(TAG, "Pleos TTS 준비 실패: $p0"); main.post { onReady(false) } }
            })
        }.onFailure { Log.w(TAG, "Pleos TTS 준비 실패", it); onReady(false) }
    }

    override fun speak(text: String, onDone: () -> Unit) {
        this.onDone = onDone
        runCatching { tts.speak(text, true) }.onFailure { finish() }
    }

    override fun stop() {
        onDone = null
        runCatching { tts.stop() }
    }

    fun release() {
        onDone = null
        runCatching { tts.release() }
    }

    private fun finish() {
        main.post {
            val callback = onDone
            onDone = null
            callback?.invoke()
        }
    }
}

/** Pleos SpeechToText로 "1번", "네", "그만" 같은 짧은 대답을 한 번 듣습니다. */
class PleosAnswerListener(context: Context, private val main: Handler) : AnswerListener {

    private val stt = SpeechToText(context, SttMode.ON_DEVICE)
    private var onResult: ((String?) -> Unit)? = null
    private var ready = false

    override val available: Boolean get() = ready

    private val timeout = Runnable {
        runCatching { stt.stop() }
        deliver(null)
    }

    fun start(onReady: (Boolean) -> Unit) {
        runCatching {
            stt.initialize(object : SttInit {
                override fun onSuccess() {
                    main.post {
                        val ok = runCatching {
                            stt.setLocale(Locale.KOREA)
                            stt.addListener(object : ResultListener {
                                override fun onUpdated(p0: String, p1: Boolean) {
                                    if (p1) deliver(p0)
                                }
                                override fun onUpdatedEpdData(p0: Long, p1: Long) = Unit
                                override fun onStartedRecognition() = Unit
                                override fun onEndedRecognition() = Unit
                                override fun onError(p0: SttError) { Log.w(TAG, "Pleos STT 오류: $p0"); deliver(null) }
                                override fun onReady() = Unit
                                override fun onUpdatedRms(p0: Double) = Unit
                            })
                        }.onFailure { Log.w(TAG, "Pleos STT 설정 실패", it) }.isSuccess
                        ready = ok
                        onReady(ok)
                    }
                }
                override fun onFailure(p0: SttError) { Log.w(TAG, "Pleos STT 준비 실패: $p0"); main.post { ready = false; onReady(false) } }
            })
        }.onFailure { Log.w(TAG, "Pleos STT 준비 실패", it); onReady(false) }
    }

    override fun listen(timeoutMs: Long, onResult: (String?) -> Unit) {
        this.onResult = onResult
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, timeoutMs)
        runCatching { stt.request() }.onFailure { deliver(null) }
    }

    override fun cancel() {
        onResult = null
        main.removeCallbacks(timeout)
        runCatching { stt.stop() }
    }

    fun release() {
        cancel()
        runCatching { stt.release() }
    }

    private fun deliver(text: String?) {
        main.post {
            val callback = onResult ?: return@post
            onResult = null
            main.removeCallbacks(timeout)
            callback(text)
        }
    }
}

private const val TAG = "Gilstory"
