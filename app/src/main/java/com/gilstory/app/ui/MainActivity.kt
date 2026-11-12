package com.gilstory.app.ui

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.gilstory.app.R
import com.gilstory.app.core.Answer
import com.gilstory.app.core.AnswerInputPolicy
import com.gilstory.app.core.Clock
import com.gilstory.app.core.DriveGear
import com.gilstory.app.core.Genre
import com.gilstory.app.core.ParkingLocator
import com.gilstory.app.core.ParkingNotifier
import com.gilstory.app.core.ParkingSpot
import com.gilstory.app.core.RestStoryMode
import com.gilstory.app.core.SessionView
import com.gilstory.app.core.Stage
import com.gilstory.app.core.StoryPlanner
import com.gilstory.app.core.StorySession
import com.gilstory.app.platform.NaturalSpeaker
import com.gilstory.app.platform.StoryRepository
import com.gilstory.app.platform.demo.DemoDrive
import com.gilstory.app.platform.demo.ParkingDemo
import com.gilstory.app.platform.demo.SwitchingNavigator
import com.gilstory.app.platform.fallback.AndroidSpeaker
import com.gilstory.app.platform.fallback.ButtonOnlyListener
import com.gilstory.app.platform.fallback.NoSignCamera
import com.gilstory.app.platform.fallback.SwitchingListener
import com.gilstory.app.platform.fallback.SwitchingSpeaker
import com.gilstory.app.platform.pleos.PleosAnswerListener
import com.gilstory.app.platform.pleos.PleosNavigator
import com.gilstory.app.platform.pleos.PleosSpeaker
import com.gilstory.app.platform.pleos.PleosVehicleSignals
import java.util.Calendar

class MainActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())

    private lateinit var planner: StoryPlanner
    private lateinit var speaker: SwitchingSpeaker
    private lateinit var listener: SwitchingListener
    private lateinit var androidSpeaker: AndroidSpeaker
    private lateinit var pleosSpeaker: PleosSpeaker
    private lateinit var pleosListener: PleosAnswerListener
    private lateinit var vehicleSignals: PleosVehicleSignals
    private lateinit var pleosNavigator: PleosNavigator
    private lateinit var demo: DemoDrive
    private lateinit var navigator: SwitchingNavigator
    private lateinit var session: StorySession
    private lateinit var parkingLocator: ParkingLocator
    private lateinit var parkingDemo: ParkingDemo

    // 차량·시연 상태
    private var parked = false
    private val audioManager by lazy { getSystemService(android.media.AudioManager::class.java) }
    private var focusRequested = false
    // 요청 객체를 하나만 씁니다. 누를 때마다 새로 만들면 앞 요청이 "포커스 잃음"을 받아 이야기를 멈춥니다.
    private val focusRequest: android.media.AudioFocusRequest by lazy {
        android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAcceptsDelayedFocusGain(true).setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                if (focusRequested) when (change) {
                    android.media.AudioManager.AUDIOFOCUS_GAIN -> session.onAudioAllowed(true)
                    android.media.AudioManager.AUDIOFOCUS_LOSS -> { session.onAudioAllowed(false); session.stop() }
                    else -> session.onAudioAllowed(false)
                }
            }, main).build()
    }
    private fun startWithAudio() {
        if (session.view().running) return
        parkingSpot = null
        parkingStep = null
        val result = audioManager.requestAudioFocus(focusRequest)
        Log.i(TAG, "오디오 포커스 요청 결과: $result")
        if (result == android.media.AudioManager.AUDIOFOCUS_REQUEST_FAILED) {
            addLog("다른 차량 안내가 끝난 뒤 다시 시작해 주세요")
            return
        }
        session.onAudioAllowed(result == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        focusRequested = true
        session.start()
    }
    private var headlampOn = false
    private var demoNight = false
    private var rearOccupied = false
    private var attentionOn = false
    private var vehicleMinutes = 0
    // 차량이 주행 시간을 주지 않으면(에뮬레이터 등) 앱이 P단이 아닌 시간을 직접 셉니다
    private var ownDrivingClock = false
    private var ownDrivingMs = 0L
    private var lastTickMs = 0L
    private var demoMinutes = 0
    private var extraMinutes = 0
    // 주차 결과·시연 진행을 화면 가운데에 크게 보여 줍니다 (이야기가 다시 시작되거나 시간이 지나면 사라짐)
    private var parkingSpot: ParkingSpot? = null
    private var parkingStep: String? = null
    private var parkingShownAtMs = 0L
    private val env = linkedMapOf("차량" to "…", "내비" to "…", "음성 합성" to "…", "음성 인식" to "…", "빌트인캠" to "…")
    private val log = ArrayDeque<String>()

    private val clock = object : Clock {
        override fun nowMs() = SystemClock.elapsedRealtime()
        override fun hour() = if (demoNight) 23 else Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    }

    private val ticker = object : Runnable {
        override fun run() {
            countOwnDrivingTime()
            if (parkingSpot != null && SystemClock.elapsedRealtime() - parkingShownAtMs > PARKING_SHOW_MS) {
                parkingSpot = null
                render(session.view())
            }
            session.onTick()
            updateNight()
            main.postDelayed(this, TICK_MS)
        }
    }

    // ---- 뷰 ----
    private lateinit var stagePill: TextView
    private lateinit var regionText: TextView
    private lateinit var minutesText: TextView
    private lateinit var genreEmoji: TextView
    private lateinit var titleText: TextView
    private lateinit var statusText: TextView
    private lateinit var answerRow: LinearLayout
    private lateinit var option1: Button
    private lateinit var option2: Button
    private lateinit var startStop: Button
    private lateinit var settingsGroup: View
    private lateinit var drivingNotice: View
    private lateinit var envText: TextView
    private lateinit var logText: TextView
    private lateinit var demoDriveButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()

        planner = StoryPlanner(StoryRepository.loadStories(this))
        androidSpeaker = AndroidSpeaker(this, main)
        // 문장 단위로 나눠 사람처럼 쉬어 가며 읽습니다
        speaker = SwitchingSpeaker(NaturalSpeaker(androidSpeaker, main)) { addLog("🔊 $it") }
        listener = SwitchingListener(ButtonOnlyListener)

        demo = DemoDrive(main, demoSink)
        pleosNavigator = PleosNavigator(this, main, StoryRepository.loadRestStops(this), naviSink)
        navigator = SwitchingNavigator(demo, pleosNavigator)
        session = newSession(demoTiming = false)
        parkingLocator = ParkingLocator(NoSignCamera, parkingNotifier) { SystemClock.elapsedRealtime() }
        parkingDemo = ParkingDemo(main, demoParkingNotifier) { step ->
            addLog(step)
            parkingStep = step.replace("[시연] ", "")
            render(session.view())
        }
        setEnv("빌트인캠", if (NoSignCamera.available) "연결됨" else "영상 API 없음 (시연만)")

        setParked(false, known = false)
        connectPleos()
        wireButtons()
        render(session.view())
        main.post(ticker)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        session.restMetrics.let { m ->
            Log.i(TAG, "쉼터 지표: 권유 ${m.offered}, 수락 ${m.accepted}, 거절 ${m.declined}, 무응답 ${m.unanswered}, 도착 ${m.arrived}, 경고 중 권유 ${m.offeredOnWarning}·도착 ${m.arrivedOnWarning}")
        }
        demo.stop()
        session.stop()
        parkingDemo.stop()
        parkingLocator.release()
        vehicleSignals.release()
        pleosNavigator.release()
        pleosSpeaker.release()
        pleosListener.release()
        androidSpeaker.release()
        super.onDestroy()
    }

    // ---- Pleos 연동 ----

    private fun connectPleos() {
        pleosSpeaker = PleosSpeaker(this, main)
        pleosSpeaker.start { ok ->
            if (ok) speaker.switchTo(NaturalSpeaker(pleosSpeaker, main))
            setEnv("음성 합성", if (ok) "Pleos ✓" else "기본 TTS")
        }

        pleosListener = PleosAnswerListener(this, main)
        pleosListener.start { ok ->
            if (ok) listener.switchTo(pleosListener)
            setEnv("음성 인식", if (ok) "Pleos ✓ (주행 중 음성 대답)" else "없음 (주행 중 질문 생략)")
            render(session.view())
        }

        vehicleSignals = PleosVehicleSignals(this, main, vehicleSink)
        vehicleSignals.start()
        if (env["차량"] == "…") setEnv("차량", "Pleos ✓")

        pleosNavigator.start { ok -> setEnv("내비", if (ok) "Pleos ✓" else "데모만") }
    }

    private val vehicleSink = object : PleosVehicleSignals.Sink {
        override fun onDriverAttention(on: Boolean) {
            attentionOn = on
            session.onDriverAttention(on)
        }

        override fun onDistanceKm(km: Double) {
            if (!demo.isRunning) session.onDistanceKm(km)
        }

        override fun onDrivingMinutes(minutes: Int) {
            vehicleMinutes = minutes
            pushMinutes()
        }

        override fun onPark(parked: Boolean?) { if (!demo.isRunning) setParked(parked == true, known = parked != null) }

        override fun onGear(gear: DriveGear) {
            if (demo.isRunning) return
            if (gear == DriveGear.REVERSE) addLog("⏪ 후진 감지 (R단)")
            parkingLocator.onGear(gear)
        }

        override fun onHeadlamp(on: Boolean) {
            headlampOn = on
            updateNight()
        }

        override fun onRearOccupied(occupied: Boolean) {
            rearOccupied = occupied
            session.onRearOccupied(occupied)
        }

        override fun onSignalError(name: String, error: Exception) {
            if (name == "기어" && !demo.isRunning) setParked(false, known = false)
            if (name == "주행 시간" && !ownDrivingClock) {
                ownDrivingClock = true
                addLog("⏱ 차량 주행 시간이 없어 앱이 직접 셉니다")
            }
            setEnv("차량", "일부 신호 없음")
            Log.w(TAG, "$name 신호를 받을 수 없음", error)
            addLog("⚠ $name 신호를 받을 수 없음: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    // 휴대폰으로 보내는 경로(마이현대 연동 또는 동반 앱)가 정해지면 이 자리에서 전송합니다
    private val parkingNotifier = object : ParkingNotifier {
        override fun notify(spot: ParkingSpot) = showParking(spot, demo = false)
    }

    private val demoParkingNotifier = object : ParkingNotifier {
        override fun notify(spot: ParkingSpot) = showParking(spot, demo = true)
    }

    /** 주차 위치를 화면 가운데에 크게 띄우고 소리로도 알려 줍니다. */
    private fun showParking(spot: ParkingSpot, demo: Boolean) {
        Log.i(TAG, "주차 위치: ${spot.summary()}")
        addLog("📲 주차 위치 알림: ${spot.summary()}")
        // 시연에서는 듣던 이야기를 멈추고 주차 안내를 들려줍니다. 실제 주차 중 이야기는 끊지 않습니다.
        if (demo && session.view().running) session.stop()
        parkingStep = null
        parkingSpot = spot
        parkingShownAtMs = SystemClock.elapsedRealtime()
        render(session.view())
        if (session.view().running) return
        val line = if (spot.floor == null && spot.pillar == null) {
            "기둥 글자를 읽지 못했어요. 주차 사진으로 확인해 주세요."
        } else {
            // "C-17"을 "마이너스"로 읽지 않도록 소리 내는 문장은 따로 만듭니다
            val floor = spot.floor?.let { "지하 ${it.drop(1)}층" }
            val pillar = spot.pillar?.let { "${it.substringBefore('-')} ${it.substringAfter('-').trimStart('0')}번 기둥 옆" }
            "주차 위치를 기억해 둘게요. ${listOfNotNull(floor, pillar).joinToString(", ")}이에요."
        }
        val granted = audioManager.requestAudioFocus(focusRequest) != android.media.AudioManager.AUDIOFOCUS_REQUEST_FAILED
        if (!granted) return
        speaker.speak(line) {
            if (!session.view().running && !focusRequested) audioManager.abandonAudioFocusRequest(focusRequest)
        }
    }

    private val naviSink = object : PleosNavigator.Sink {
        override fun onRegion(region: String) {
            if (!demo.isRunning) session.onRegionChanged(region)
        }

        override fun onWaypointArrived() { if (!demo.isRunning) session.onWaypointArrived() }

        override fun onNaviError(message: String) = addLog("⚠ 내비: $message")
    }

    private val demoSink = object : DemoDrive.Sink {
        override fun onDemoRegion(region: String) {
            addLog("📍 $region 진입")
            session.onRegionChanged(region)
        }

        override fun onDemoMinutes(minutes: Int) {
            demoMinutes = minutes
            // 시연 주행은 평균 90km/h로 보고 거리를 만듭니다
            session.onDistanceKm(minutes * 1.5)
            pushMinutes()
        }

        override fun onDemoArrived() {
            addLog("🅿 쉼터 도착 (시연)")
            setParked(true)
            session.onWaypointArrived()
        }

        override fun onDemoFinished() {
            addLog("🏁 데모 주행 끝")
            demoDriveButton.text = "🚗 데모 주행 (영동고속도로)"
        }
    }

    // ---- 세션 ----

    private fun newSession(demoTiming: Boolean): StorySession {
        val timing = if (demoTiming) {
            // 시연 영상용: 이야기 사이 간격을 줄입니다
            StorySession.Timing(companionGapMs = 15_000, wakeGapMs = 8_000, restGapMs = 10_000)
        } else {
            StorySession.Timing()
        }
        return StorySession(planner, speaker, listener, navigator, clock, timing = timing, onUpdate = ::render).also {
            it.setPreferredGenres(selectedGenres())
            it.setFamilyMode(findViewById<Switch>(R.id.familyMode).isChecked)
            it.setRestStoryMode(restStoryMode())
            it.onParked(parked)
            it.onRearOccupied(rearOccupied)
            it.onNight(isNight())
            it.onDriverAttention(attentionOn)
            it.onDrivingMinutes(totalMinutes())
        }
    }

    private fun setParked(value: Boolean, known: Boolean = true) {
        parked = value
        settingsGroup.visibility = if (parked) View.VISIBLE else View.GONE
        drivingNotice.visibility = if (parked) View.GONE else View.VISIBLE
        // 쉼터에서 P단 → 주행 기어로 바뀌면 다시 출발
        session.onParked(parked, known)
        render(session.view())
    }

    private fun restStoryMode() =
        if (findViewById<Switch>(R.id.restKeepListening).isChecked) RestStoryMode.CONTINUE else RestStoryMode.PAUSE_UNTIL_REST

    private fun isNight() = headlampOn || demoNight || clock.hour() >= 19 || clock.hour() < 6

    private fun updateNight() = session.onNight(isNight())

    private fun countOwnDrivingTime() {
        val now = SystemClock.elapsedRealtime()
        val gap = if (lastTickMs == 0L) 0L else now - lastTickMs
        lastTickMs = now
        if (!ownDrivingClock || demo.isRunning || parked) return
        ownDrivingMs += gap
        val minutes = (ownDrivingMs / 60_000L).toInt()
        if (minutes != vehicleMinutes) {
            vehicleMinutes = minutes
            pushMinutes()
        }
    }

    private fun totalMinutes() = maxOf(vehicleMinutes, demoMinutes) + extraMinutes

    private fun pushMinutes() = session.onDrivingMinutes(totalMinutes())

    private fun selectedGenres(): Set<Genre> = buildSet {
        if (findViewById<CheckBox>(R.id.prefComedy).isChecked) add(Genre.COMEDY)
        if (findViewById<CheckBox>(R.id.prefSpooky).isChecked) add(Genre.SPOOKY)
        if (findViewById<CheckBox>(R.id.prefThink).isChecked) add(Genre.THINK)
        if (findViewById<CheckBox>(R.id.prefLocal).isChecked) add(Genre.LOCAL)
    }

    // ---- 화면 ----

    private fun bindViews() {
        stagePill = findViewById(R.id.stagePill)
        regionText = findViewById(R.id.regionText)
        minutesText = findViewById(R.id.minutesText)
        genreEmoji = findViewById(R.id.genreEmoji)
        titleText = findViewById(R.id.titleText)
        statusText = findViewById(R.id.statusText)
        answerRow = findViewById(R.id.answerRow)
        option1 = findViewById(R.id.option1)
        option2 = findViewById(R.id.option2)
        startStop = findViewById(R.id.startStop)
        settingsGroup = findViewById(R.id.settingsGroup)
        drivingNotice = findViewById(R.id.drivingNotice)
        envText = findViewById(R.id.envText)
        logText = findViewById(R.id.logText)
        demoDriveButton = findViewById(R.id.demoDrive)
    }

    private fun wireButtons() {
        startStop.setOnClickListener { if (session.view().running) session.stop() else startWithAudio() }
        findViewById<Button>(R.id.skip).setOnClickListener { session.submitAnswer(Answer.Skip) }
        option1.setOnClickListener { touchAnswer(Answer.Option(0)) }
        option2.setOnClickListener { touchAnswer(Answer.Option(1)) }

        mapOf(
            R.id.genreComedy to Genre.COMEDY,
            R.id.genreSpooky to Genre.SPOOKY,
            R.id.genreThink to Genre.THINK,
            R.id.genreLocal to Genre.LOCAL,
        ).forEach { (id, genre) ->
            findViewById<Button>(id).setOnClickListener {
                session.changeGenre(genre)
                if (!session.view().running) startWithAudio()
            }
        }

        listOf(R.id.prefComedy, R.id.prefSpooky, R.id.prefThink, R.id.prefLocal).forEach { id ->
            findViewById<CheckBox>(id).setOnCheckedChangeListener { _, _ -> session.setPreferredGenres(selectedGenres()) }
        }
        findViewById<Switch>(R.id.familyMode).setOnCheckedChangeListener { _, on -> session.setFamilyMode(on) }
        findViewById<Switch>(R.id.restKeepListening).setOnCheckedChangeListener { _, _ -> session.setRestStoryMode(restStoryMode()) }

        // ---- 시연 패널 ----
        demoDriveButton.setOnClickListener {
            if (demo.isRunning) {
                demo.stop()
                demoDriveButton.text = "🚗 데모 주행 (영동고속도로)"
                addLog("■ 데모 주행 중지")
                return@setOnClickListener
            }
            session.stop()
            demoMinutes = 0
            extraMinutes = 0
            session = newSession(demoTiming = true)
            setParked(false)
            demo.start()
            startWithAudio()
            demoDriveButton.text = "■ 데모 주행 멈추기"
            addLog("🚗 데모 주행 시작: 영동고속도로 서울 → 강릉")
        }
        findViewById<Button>(R.id.demoParking).setOnClickListener { parkingDemo.start() }
        findViewById<Button>(R.id.demoMinutes).setOnClickListener {
            extraMinutes += 30
            addLog("⏱ 운전 시간 +30분 (총 ${totalMinutes()}분)")
            pushMinutes()
        }
        findViewById<Button>(R.id.demoNight).setOnClickListener {
            demoNight = !demoNight
            addLog(if (demoNight) "🌙 밤으로 전환" else "☀ 낮으로 전환")
            updateNight()
        }
        findViewById<Button>(R.id.demoRear).setOnClickListener {
            rearOccupied = !rearOccupied
            addLog(if (rearOccupied) "👥 뒷좌석 탑승" else "👤 뒷좌석 비움")
            session.onRearOccupied(rearOccupied)
        }
        findViewById<Button>(R.id.demoAttention).setOnClickListener {
            attentionOn = !attentionOn
            addLog(if (attentionOn) "☕ 차량 졸음 경고 켜짐 (테스트 주입)" else "☕ 차량 졸음 경고 꺼짐")
            session.onDriverAttention(attentionOn)
        }
        findViewById<Button>(R.id.demoArrive).setOnClickListener {
            if (!demo.isRunning) return@setOnClickListener
            setParked(true)
            session.onWaypointArrived()
        }
        findViewById<Button>(R.id.demoResume).setOnClickListener {
            if (!demo.isRunning) return@setOnClickListener
            attentionOn = false
            session.onDriverAttention(false)
            setParked(false)
        }
    }

    private fun canTouchAnswer() = AnswerInputPolicy.touchAllowed(parked)

    /** 화면 버튼 대답은 정차 중에만 받습니다. 주행 중에는 말로 대답합니다. */
    private fun touchAnswer(answer: Answer) {
        if (!canTouchAnswer()) {
            addLog("✋ 주행 중에는 화면 대신 말로 대답해 주세요")
            return
        }
        session.submitAnswer(answer)
    }

    private fun render(v: SessionView) {
        if (!v.running && focusRequested) {
            focusRequested = false
            audioManager.abandonAudioFocusRequest(focusRequest)
        }
        val (label, color) = when {
            v.resting -> "🅿 쉬는 중" to R.color.stage_companion
            v.stage == Stage.COMPANION -> "🟢 길동무" to R.color.stage_companion
            v.stage == Stage.WAKE -> "🟠 깨우기" to R.color.stage_wake
            else -> "🔴 쉼터 안내" to R.color.stage_rest
        }
        stagePill.text = label
        stagePill.background.mutate().setTint(getColor(color))
        regionText.text = "📍 ${v.region ?: "위치 확인 중"}"
        minutesText.text = "운전 ${v.drivingMinutes}분"
        genreEmoji.text = v.genre.emoji
        titleText.text = v.storyTitle ?: v.restStopName?.let { "🅿 $it" } ?: v.genre.label
        statusText.text = v.status
        startStop.text = if (v.running) "■ 그만" else "▶ 시작"

        answerRow.visibility = if (v.awaitingAnswer) View.VISIBLE else View.GONE
        val touch = canTouchAnswer()
        if (v.options.size == 2) {
            // 주행 중: 선택지는 "이렇게 말하세요" 안내로만 보이고 눌리지 않습니다
            val prefix = if (touch) "" else "🎤 "
            option1.text = "${prefix}1번  ${v.options[0]}"
            option2.text = "${prefix}2번  ${v.options[1]}"
        }
        listOf(option1, option2).forEach {
            it.isEnabled = touch
            it.alpha = if (touch) 1f else 0.55f
        }
        if (v.awaitingAnswer && !touch) statusText.text = "🎤 말로 대답해 주세요 (\"1번\", \"2번\", \"네\", \"그만\")"

        // 주차 시연 진행·결과는 이야기보다 앞에 보여 줍니다
        val spot = parkingSpot
        val step = parkingStep
        if (spot != null) {
            genreEmoji.text = "🅿"
            titleText.text = spot.summary()
            statusText.text = if (spot.complete) "주차 위치를 기억했어요 · 휴대폰 알림 예정" else "주차 위치를 확인해 주세요"
        } else if (step != null) {
            genreEmoji.text = "⏪"
            titleText.text = "후진 주차 중"
            statusText.text = step
        }
    }

    private fun setEnv(key: String, value: String) {
        env[key] = value
        envText.text = env.entries.joinToString("\n") { "${it.key}: ${it.value}" }
    }

    private fun addLog(line: String) {
        log.addFirst(line)
        while (log.size > 8) log.removeLast()
        logText.text = log.joinToString("\n")
    }

    companion object {
        private const val TICK_MS = 2_000L
        private const val PARKING_SHOW_MS = 60_000L
        private const val TAG = "Gilstory"
    }
}
