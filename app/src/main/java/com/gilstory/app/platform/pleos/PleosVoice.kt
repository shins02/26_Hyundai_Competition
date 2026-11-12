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

/**
 * Pleos SpeechToText로 "1번", "네", "그만" 같은 짧은 대답을 한 번 듣습니다.
 *
 * - 듣는 시간은 마이크가 실제로 열린 때부터 셉니다. 말이 들어오는 동안(중간 결과)에는 조금씩 늘립니다.
 * - 시간이 다 되면 그때까지 들린 중간 결과를 대답으로 씁니다.
 * - 들은 말과 마이크 소리 크기를 기록해, 인식이 안 될 때 원인을 볼 수 있게 합니다.
 */
class PleosAnswerListener(
    context: Context,
    private val main: Handler,
    private val onHeard: (String) -> Unit = {},
    /** 마이크가 열려 말해도 될 때 */
    private val onListening: () -> Unit = {},
) : AnswerListener {

    private val stt = SpeechToText(context, SttMode.ON_DEVICE)
    private var onResult: ((String?) -> Unit)? = null
    private var ready = false
    private var timeoutMs = 8_000L
    private var partial: String? = null
    private var maxRms = 0.0

    override val available: Boolean get() = ready

    private val timeout = Runnable {
        runCatching { stt.stop() }
        deliver(partial)
    }

    private fun restartTimer(ms: Long) {
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, ms)
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
                                    main.post {
                                        if (onResult == null) return@post
                                        if (p1) deliver(p0) else if (p0.isNotBlank()) {
                                            partial = p0
                                            restartTimer(EXTEND_MS)
                                        }
                                    }
                                }
                                override fun onUpdatedEpdData(p0: Long, p1: Long) = Unit
                                override fun onStartedRecognition() = Unit
                                override fun onEndedRecognition() = Unit
                                override fun onError(p0: SttError) { Log.w(TAG, "Pleos STT 오류: $p0"); deliver(partial) }
                                // 마이크가 열린 때(ReadyToStartRecognition)부터 듣는 시간을 셉니다.
                                // 에뮬레이터에서는 요청 뒤 열리기까지 6초 넘게 걸리기도 합니다.
                                override fun onReady() {
                                    main.post {
                                        if (onResult == null) return@post
                                        restartTimer(timeoutMs)
                                        onListening()
                                    }
                                }
                                override fun onUpdatedRms(p0: Double) { if (p0 > maxRms) maxRms = p0 }
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
        this.timeoutMs = timeoutMs
        partial = null
        maxRms = 0.0
        // 마이크가 열리기까지 기다립니다 (열리면 onReady에서 다시 셈)
        restartTimer(timeoutMs + OPEN_GRACE_MS)
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
            Log.i(TAG, "STT 결과: ${text ?: "(못 들음)"} · 최대 소리 크기 ${"%.1f".format(maxRms)}")
            onHeard(if (text.isNullOrBlank()) "🎤 대답을 못 들었어요 (마이크 소리 ${"%.0f".format(maxRms)})" else "🎤 들은 말: $text")
            callback(text)
        }
    }

    private companion object {
        const val OPEN_GRACE_MS = 15_000L
        const val EXTEND_MS = 2_500L
    }
}

private const val TAG = "Gilstory"
