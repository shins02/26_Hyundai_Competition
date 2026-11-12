package com.gilstory.app.core

import kotlin.math.roundToInt

/** 화면에 보여 줄 상태 (주행 중에는 한 줄 정보만 씁니다) */
data class SessionView(
    val running: Boolean,
    val stage: Stage,
    val genre: Genre,
    val region: String?,
    val drivingMinutes: Int,
    val status: String,
    val storyTitle: String?,
    val awaitingAnswer: Boolean,
    val options: List<String>,
    val resting: Boolean,
    val restStopName: String?,
)

/**
 * 휴식 안내가 시작될 때 듣던 이야기를 어떻게 할지. 운전자가 정차 중 설정에서 고릅니다.
 * 한 가지 방식만 강요하지 않도록 두 가지를 둡니다.
 */
enum class RestStoryMode {
    /** 이야기를 멈추고, 쉼터에 도착해 P단으로 정차하면 듣던 이야기의 결말을 들려줍니다 (기본) */
    PAUSE_UNTIL_REST,
    /** 이야기를 계속 들려주고, 듣던 이야기의 결말은 10~15km 더 달린 뒤 들려줍니다 */
    CONTINUE,
}

/**
 * S J Story의 흐름을 관리합니다.
 *
 * ① 길동무: 고른 장르의 이야기를 들려주고, 새 시·군에 들어서면 그 지역 이야기를 먼저 고릅니다.
 * 휴식 신호가 들어오면 현재 흐름을 취소하고, 동의한 경유지만 요청합니다.
 * 경로 반영·도착·P단을 각각 확인하고, 결말은 정차 중 선택해야 재생합니다.
 * '이어 듣기'(RestStoryMode.CONTINUE)를 고르면 휴식 권유 뒤에도 이야기를 이어 가고, 결말은 10~15km 뒤에 들려줍니다.
 *
 * 플랫폼(Pleos SDK, 데모)과 분리되어 있어 단위 테스트로 흐름을 검증할 수 있습니다.
 */
class StorySession(
    private val planner: StoryPlanner,
    private val speaker: Speaker,
    private val listener: AnswerListener,
    private val navigator: RestNavigator,
    private val clock: Clock,
    private val scorer: DrowsinessScorer = DrowsinessScorer(),
    private val timing: Timing = Timing(),
    private val onUpdate: (SessionView) -> Unit = {},
) {

    data class Timing(
        val companionGapMs: Long = 120_000,
        val wakeGapMs: Long = 45_000,
        val restGapMs: Long = 60_000,
        val listenTimeoutMs: Long = 8_000,
        val warningLeadMs: Long = 1_500,
        /** 이어 듣기: 결말을 미루는 거리(km). 이야기마다 이 범위 안에서 정해집니다 */
        val endingMinKm: Int = 10,
        val endingMaxKm: Int = 15,
        /** 주행 거리 신호가 없을 때 거리를 시간으로 바꾸는 평균 속도(km/h) */
        val fallbackSpeedKmh: Int = 90,
    )

    private var running = false
    private var busy = false
    private var flowId = 0

    private var drivingMinutes = 0
    private var restBaselineMinutes = 0
    private var attentionWarning = false
    private var parked = false
    private var audioAllowed = true
    private var restHold = false
    private var offerAfter = 0L
    private var parkedPrompted = false
    private var activeStory: Story? = null
    private var audioInterruptedStory: Story? = null
    private var savedStory: Story? = null
    /** 미뤄 둔 결말이 선택형 이야기의 답이라면 그 선택지 번호 */
    private var savedChoice: Int? = null
    private var restMode = RestStoryMode.PAUSE_UNTIL_REST
    private var distanceKm: Double? = null
    private var endingAtKm: Double? = null
    private var endingAtMs: Long? = null
    private var endingNoticeKm: Int? = null
    /** 이어 듣기: 이번 휴식 안내에서 듣던 이야기를 이미 멈췄는지 */
    private var continueRestStarted = false
    private var episode = 0

    private var genreCtx = GenreContext()
    private var currentGenre: Genre? = null
    private var region: String? = null
    private var regionFresh = false
    private var nightPromptPending = false
    private val played = mutableSetOf<String>()

    private var lastFlowEndMs = 0L
    private var restOffered = false
    private var arrived = false
    private var restStop: RestStop? = null
    private var navigationPending = false
    private var waypointAdded = false
    private var resting = false
    private var restOnWarning = false


    /** 쉼터 권유 지표 (성공 지표 측정용) */
    var restMetrics = RestMetrics()
        private set

    private var status = "S J Story를 켜면 이야기를 시작해요"
    private var storyTitle: String? = null
    private var pending: PendingQuestion? = null

    private class PendingQuestion(
        val options: List<String>,
        val startedMs: Long,
        val attempt: Int,
        val onAnswer: (Answer?, Long) -> Unit,
        /** 질문을 읽는 중에도 버튼으로 바로 대답할 수 있습니다 */
        val speaking: Boolean = false,
    )

    // ---- 외부 입력 -------------------------------------------------------------

    fun start() {
        if (running) return
        running = true
        lastFlowEndMs = 0
        status = "이야기를 준비하고 있어요"
        publish()
        if (resting) maybeOfferEnding() else playNext()
    }

    fun stop() {
        running = false
        cancelFlow()
        status = "이야기를 멈췄어요"
        publish()
    }

    fun onAudioAllowed(allowed: Boolean) {
        if (audioAllowed == allowed) return
        audioAllowed = allowed
        if (!allowed) {
            activeStory?.let { audioInterruptedStory = it }
            cancelFlow()
            if (running) status = "차량 음성 안내를 기다리는 중"
        } else if (running) {
            if (resting) maybeOfferEnding() else playNext()
        }
        publish()
    }

    fun onParked(value: Boolean, known: Boolean = true) {
        val before = parked
        parked = value
        if (!value && (before || resting) && (restHold || resting)) {
            cancelFlow()
            if (known && (resting || restStop?.onRoute == true || !waypointAdded && !navigationPending)) {
                episode++
                resting = false; arrived = false; restHold = false; restOffered = false
                waypointAdded = false; restStop = null; parkedPrompted = false; continueRestStarted = false
                clearSavedEnding()
                restBaselineMinutes = drivingMinutes
                lastFlowEndMs = clock.nowMs()
                status = "다음 이야기를 기다리는 중"
                // 경고가 계속 켜져 있으면 주행 중 이야기를 다시 시작하지 않습니다.
                if (attentionWarning) { restHold = true; restOffered = true }
            }
        }
        if (!parked && !listener.available && pending != null) deliver(null, false)
        maybeOfferEnding()
        publish()
    }

    fun onRegionChanged(newRegion: String?) {
        if (newRegion == null || newRegion == region) return
        region = newRegion
        regionFresh = true
        publish()
        if (!busy) playNext()
    }

    fun onDrivingMinutes(minutes: Int) {
        // 차량 주행 시간이 다시 0부터 시작하면(재시동 등) 기준점도 초기화
        if (minutes < restBaselineMinutes) restBaselineMinutes = 0
        drivingMinutes = minutes
        if (running && currentStage() == Stage.REST) enterRest()
        publish()
    }

    /** 차량 누적 주행 거리(km). 이어 듣기에서 결말을 들려줄 때를 정합니다 */
    fun onDistanceKm(km: Double) {
        distanceKm = km
        if (endingDue() && !busy) playNext()
    }

    fun setRestStoryMode(mode: RestStoryMode) {
        restMode = mode
        publish()
    }

    fun onDriverAttention(on: Boolean) {
        val changed = on != attentionWarning
        attentionWarning = on
        if (on && changed && running) enterRest()
        publish()
    }

    private fun enterRest() {
        if (restMode == RestStoryMode.CONTINUE && !attentionWarning && !restHold) {
            // 장시간 주행으로 권하는 휴식에만 적용합니다. 차량 경고는 정차할 때까지 우선합니다.
            // 이어 듣기: 듣던 이야기는 결말을 10~15km 뒤로 미루고, 쉼터 안내를 한 번 한 뒤 이야기를 이어 갑니다
            if (restOffered) return
            if (savedStory == null) (activeStory ?: audioInterruptedStory)?.let { deferEnding(it, null) }
            audioInterruptedStory = null
            if (!continueRestStarted) {
                continueRestStarted = true
                cancelFlow()
                offerAfter = clock.nowMs() + if (attentionWarning) timing.warningLeadMs else 0L
                status = "휴식 안내"
            }
            if (running && audioAllowed && clock.nowMs() >= offerAfter) offerRest()
            publish()
            return
        }
        if (!restHold) {
            val interrupted = activeStory ?: audioInterruptedStory
            if (interrupted != null) {
                savedStory = interrupted
                savedChoice = null
            }
            endingAtKm = null
            endingAtMs = null
            endingNoticeKm = null
            audioInterruptedStory = null
            cancelFlow()
            restHold = true
            offerAfter = clock.nowMs() + if (attentionWarning) timing.warningLeadMs else 0L
            status = "이야기를 멈췄어요 · 휴식 안내"
        }
        if (running && audioAllowed && !restOffered && clock.nowMs() >= offerAfter) offerRest()
        publish()
    }

    fun onNight(night: Boolean) {
        if (night == genreCtx.isNight) return
        genreCtx = genreCtx.copy(isNight = night)
        if (night && currentGenre != Genre.SPOOKY) nightPromptPending = true
        publish()
    }

    fun onRearOccupied(occupied: Boolean) {
        genreCtx = genreCtx.copy(rearOccupied = occupied, spookyConfirmedWithPassengers = false)
        publish()
    }

    fun setFamilyMode(on: Boolean) {
        genreCtx = genreCtx.copy(familyMode = on)
        if (on && currentGenre == Genre.SPOOKY) currentGenre = null
        publish()
    }

    fun setPreferredGenres(genres: Set<Genre>) {
        genreCtx = genreCtx.copy(preferred = genres)
        currentGenre?.let { if (it !in GenrePolicy.allowed(genreCtx)) currentGenre = null }
        publish()
    }

    /** 일정 간격(예: 5초)으로 불러 주세요. 쉬는 틈이 끝나면 다음 이야기를 시작합니다. */
    fun onTick() {
        if (!running || !audioAllowed || busy || resting) return
        val restPending = currentStage() == Stage.REST && (restMode == RestStoryMode.PAUSE_UNTIL_REST || !restOffered)
        if (restHold || restPending || endingDue() || clock.nowMs() - lastFlowEndMs >= gapFor(currentStage())) playNext()
    }

    fun onWaypointArrived() {
        if (!waypointAdded || arrived) return
        arrived = true
        status = "쉼터 도착 · 주차 후 이용할 수 있어요"
        maybeOfferEnding()
        publish()
    }

    private fun maybeOfferEnding() {
        if (!arrived || !parked) return
        if (!resting) restMetrics += RestMetrics(arrived = 1, arrivedOnWarning = if (restOnWarning) 1 else 0)
        resting = true
        if (!running || !audioAllowed || parkedPrompted) return
        parkedPrompted = true
        cancelFlow()
        val story = savedStory ?: return finishFlow()
        val chosen = savedChoice
        val id = beginFlow("쉼터에서 쉬는 중")
        ask(id, "주차했어요. 듣던 이야기의 결말을 들을까요?", listOf("결말 듣기", "조용히 쉬기")) { answer, _ ->
            if (answer == Answer.Stop) return@ask stop()
            clearSavedEnding()
            if (answer != Answer.Yes && answer != Answer.Option(0)) return@ask finishFlow()
            if (!parked || !resting) return@ask finishFlow()
            tellEnding(id, story, chosen)
        }
    }

    /** 미뤄 둔 결말을 들려줍니다. 선택형 이야기에서 아직 고르지 않았다면 먼저 고르게 합니다 */
    private fun tellEnding(id: Int, story: Story, chosen: Int?) {
        val part = story.interaction
        if (chosen != null && part != null) {
            speaker.speak(part.responses[chosen]) { guard(id) { finishFlow() } }
        } else if (part?.type == InteractionType.CHOICE) {
            ask(id, part.question + " 1번, " + part.options[0] + ". 2번, " + part.options[1], part.options) { choice, _ ->
                if (choice == Answer.Stop) stop()
                else if (choice is Answer.Option) speaker.speak(part.responses[choice.index]) { guard(id) { finishFlow() } }
                else finishFlow()
            }
        } else {
            val ending = part?.answer?.let { part.responses.getOrNull(it) } ?: story.ending
            speaker.speak(ending) { guard(id) { finishFlow() } }
        }
    }

    // ---- 이어 듣기: 결말을 10~15km 뒤로 미루기 ----------------------------------------

    private fun deferEnding(story: Story, chosen: Int?) {
        savedStory = story
        savedChoice = chosen
        // 이야기마다 10~15km 사이에서 정해, 결말이 매번 같은 거리에서 나오지 않게 합니다
        val span = (timing.endingMaxKm - timing.endingMinKm).coerceAtLeast(0) + 1
        val km = timing.endingMinKm + Math.floorMod(story.id.hashCode(), span)
        endingNoticeKm = km
        val here = distanceKm
        if (here != null) {
            endingAtKm = here + km
            endingAtMs = null
        } else {
            endingAtKm = null
            endingAtMs = clock.nowMs() + km * 3_600_000L / timing.fallbackSpeedKmh
        }
    }

    private fun endingDue(): Boolean {
        if (restMode != RestStoryMode.CONTINUE || savedStory == null || resting) return false
        val atKm = endingAtKm
        val here = distanceKm
        if (atKm != null && here != null) return here >= atKm
        return endingAtMs?.let { clock.nowMs() >= it } ?: false
    }

    private fun clearSavedEnding() {
        savedStory = null; savedChoice = null
        endingAtKm = null; endingAtMs = null; endingNoticeKm = null
    }

    /** 결말 예고(한 번) 또는 때가 된 결말. 들려줄 것이 있으면 true */
    private fun playDeferredEnding(): Boolean {
        val story = savedStory ?: return false
        endingNoticeKm?.let { km ->
            endingNoticeKm = null
            val id = beginFlow("결말 예고")
            speaker.speak("「${story.title}」 결말은 ${km}킬로미터쯤 더 달린 뒤에 들려드릴게요.") { guard(id) { finishFlow() } }
            return true
        }
        if (!endingDue()) return false
        val chosen = savedChoice
        clearSavedEnding()
        val id = beginFlow("${story.genre.emoji} ${story.title} · 결말")
        storyTitle = story.title
        publish()
        speaker.speak("기다리셨죠. 아까 「${story.title}」 결말 들려드릴게요.") { guard(id) { tellEnding(id, story, chosen) } }
        return true
    }

    /** 화면 버튼 또는 음성으로 들어온 대답·명령 */
    fun submitAnswer(answer: Answer) {
        if (pending != null) {
            deliver(answer, heard = true)
            return
        }
        when (answer) {
            Answer.Stop -> stop()
            Answer.Skip -> skip()
            is Answer.ChangeGenre -> changeGenre(answer.genre)
            else -> Unit
        }
    }

    fun changeGenre(genre: Genre) {
        if (restHold) return
        currentGenre = genre
        genreCtx = genreCtx.copy(override = genre)
        nightPromptPending = false
        skip()
    }

    fun skip() {
        if (restHold) return
        cancelFlow()
        lastFlowEndMs = 0
        publish()
        if (running) playNext()
    }

    fun view(): SessionView = SessionView(
        running = running,
        stage = currentStage(),
        genre = currentGenre ?: GenrePolicy.recommended(genreCtx),
        region = region,
        drivingMinutes = effectiveMinutes(),
        status = status,
        storyTitle = storyTitle,
        awaitingAnswer = pending != null,
        options = pending?.options ?: emptyList(),
        resting = resting,
        restStopName = restStop?.name,
    )

    // ---- 흐름 -----------------------------------------------------------------

    private fun playNext() {
        if (!running || !audioAllowed || resting) return
        val stage = currentStage()
        val restPending = stage == Stage.REST && (restMode == RestStoryMode.PAUSE_UNTIL_REST || !restOffered)
        if (restHold || restPending) { enterRest(); return }
        if (busy) return
        if (restMode == RestStoryMode.CONTINUE && playDeferredEnding()) return
        if (nightPromptPending) {
            nightPromptPending = false
            // 동승자 여부는 오싹한 이야기를 틀기 직전에 따로 묻습니다
            if (Genre.SPOOKY in GenrePolicy.allowed(genreCtx.copy(rearOccupied = false)) && genreCtx.override == null) {
                offerNightSwitch()
                return
            }
        }

        val genre = chooseGenre()
        if (GenrePolicy.needsPassengerCheck(genreCtx, genre)) {
            askPassengers()
            return
        }

        // 깨우기·쉼터 단계에서는 대답형 이야기를 먼저 고릅니다
        val needInteraction = stage != Stage.COMPANION
        val story = findStory(genre, needInteraction)
            ?: run {
                // 들려줄 이야기를 다 들었으면 처음부터 다시
                played.clear()
                findStory(genre, needInteraction)
            }
            ?: return

        currentGenre = story.genre
        played += story.id
        tell(story)
    }

    /** 고른 장르에 없으면 허용된 다른 장르로 넘어가며 찾습니다 */
    private fun findStory(genre: Genre, needInteraction: Boolean): Story? {
        val order = mutableListOf(genre)
        var g = genre
        repeat(Genre.entries.size) {
            g = GenrePolicy.rotate(g, genreCtx)
            if (g !in order) order += g
        }
        for (requireInteraction in listOf(needInteraction, false).distinct()) {
            for (candidate in order) {
                planner.next(candidate, region, played, requireInteraction)?.let { return it }
            }
        }
        return null
    }

    private fun chooseGenre(): Genre {
        var genre = currentGenre ?: GenrePolicy.recommended(genreCtx)
        // 동승자 확인이 필요한 오싹한 이야기는 그대로 두고, playNext에서 먼저 물어봅니다
        if (genre !in GenrePolicy.allowed(genreCtx) && !GenrePolicy.needsPassengerCheck(genreCtx, genre)) {
            genre = GenrePolicy.recommended(genreCtx)
        }

        // 새 지역에 들어섰는데 지금 장르에 그 지역 이야기가 없으면, 지역 이야기가 있는 장르로
        if (regionFresh && genreCtx.override == null && !planner.hasRegional(genre, region, played)) {
            val allowed = GenrePolicy.allowed(genreCtx)
            // 지역 이야기 장르를 먼저, 그다음 시간대에 맞는 순서로
            val order = listOf(Genre.LOCAL, GenrePolicy.recommended(genreCtx), Genre.COMEDY, Genre.THINK, Genre.SPOOKY)
            order.firstOrNull { it in allowed && planner.hasRegional(it, region, played) }?.let { genre = it }
        }
        regionFresh = false
        return genre
    }

    private fun tell(story: Story) {
        val id = beginFlow("${story.genre.emoji} ${story.title}")
        audioInterruptedStory = null
        activeStory = story
        storyTitle = story.title
        publish()
        // 첫 문장에 이미 지역 이름이 나오면 "지금 지나는 ○○ 이야기예요"를 붙이지 않습니다
        val short = story.region?.replace(Regex("(시|군|구)$"), "")
        val intro = buildString {
            if (story.region != null && story.region == region && !story.text.take(60).contains(short!!)) {
                append("지금 지나는 ${story.region} 이야기예요. ")
            }
            if (story.fiction) append("지어낸 이야기예요. ")
        }
        speaker.speak(intro + story.text) {
            guard(id) {
                val interaction = story.interaction
                if (interaction == null) finishFlow() else interact(id, interaction)
            }
        }
    }

    private fun interact(id: Int, interaction: Interaction) {
        val prompt = "${interaction.question} 1번, ${interaction.options[0]}. 2번, ${interaction.options[1]}."
        ask(id, prompt, interaction.options) { answer, _ ->
            when (answer) {
                is Answer.Option -> {
                    if (restMode == RestStoryMode.CONTINUE && restOffered && savedStory == null &&
                        interaction.type == InteractionType.CHOICE && currentStage() == Stage.REST
                    ) {
                        activeStory?.let { deferEnding(it, answer.index) }
                        endingNoticeKm = null
                        val km = savedStory?.let { endingKmLabel() } ?: timing.endingMinKm
                        speaker.speak("좋아요. 결말은 ${km}킬로미터쯤 더 달린 뒤에 들려드릴게요.") { guard(id) { finishFlow() } }
                        return@ask
                    }
                    val prefix = if (interaction.type != InteractionType.QUIZ) "" else if (answer.index == interaction.answer) "정답이에요! " else "아쉬워요. "
                    speaker.speak(prefix + interaction.responses[answer.index]) { guard(id) { finishFlow() } }
                }
                is Answer.ChangeGenre -> changeGenre(answer.genre)
                Answer.Stop -> stop()
                Answer.Skip -> skip()
                else -> finishFlow()
            }
        }
    }

    private fun offerRest() {
        restOffered = true
        val id = beginFlow("쉼터 안내")
        val stop = navigator.nextRestStop()
        restStop = stop
        publish()
        if (stop == null) {
            speaker.speak("지금 경로에서 확인된 쉼터가 없어요. 안전한 곳에서 쉬어 가세요.") { guard(id) { finishFlow() } }
            return
        }
        val km = (stop.distanceKm * 10).roundToInt() / 10.0
        restOnWarning = attentionWarning
        restMetrics += RestMetrics(offered = 1, offeredOnWarning = if (restOnWarning) 1 else 0)
        val action = if (stop.onRoute) "가는 길에 있으니 거기서 쉬어 갈까요?" else "경유지로 추가할까요?"
        val prompt = "진행 방향의 ${stop.name}까지 약 ${km}킬로미터예요. $action"
        ask(id, prompt, listOf("네", "아니요")) { answer, _ ->
            if (answer == Answer.Stop) return@ask stop()
            if (answer != Answer.Yes && answer != Answer.Option(0)) {
                restMetrics += if (answer == null) RestMetrics(unanswered = 1) else RestMetrics(declined = 1)
                finishFlow(); return@ask
            }
            restMetrics += RestMetrics(accepted = 1)
            if (stop.onRoute) {
                navigator.followExisting(stop)
                waypointAdded = true
                speaker.speak("현재 경로를 그대로 안내해요.") { guard(id) { finishFlow() } }
                return@ask
            }
            navigationPending = true
            status = "내비게이션 반영을 기다리는 중"
            publish()
            val requestedEpisode = episode
            navigator.addWaypoint(stop) { ok ->
                if (episode != requestedEpisode || !navigationPending) return@addWaypoint
                navigationPending = false
                waypointAdded = ok
                val result = if (ok) "내비게이션에 ${stop.name} 경유지가 반영됐어요." else "경유지 등록을 확인하지 못했어요."
                if (running && audioAllowed && flowId == id) {
                    status = result; publish()
                    speaker.speak(result) { guard(id) { finishFlow() } }
                } else publish()
            }
        }
    }

    private fun offerNightSwitch() {
        val id = beginFlow("밤 모드 제안")
        ask(id, "밤이 됐네요. 오싹한 이야기로 바꿀까요? 네, 또는 아니요로 대답해 주세요.", listOf("네", "아니요")) { answer, _ ->
            if (answer == Answer.Stop) return@ask stop()
            if (answer == Answer.Yes || answer == Answer.Option(0)) {
                currentGenre = Genre.SPOOKY
                genreCtx = genreCtx.copy(override = Genre.SPOOKY)
            }
            finishFlow()
            playNext()
        }
    }

    private fun askPassengers() {
        val id = beginFlow("동승자 확인")
        ask(id, "뒷좌석에 함께 타신 분이 있네요. 오싹한 이야기를 계속할까요? 네, 또는 아니요로 대답해 주세요.", listOf("네", "아니요")) { answer, _ ->
            if (answer == Answer.Stop) return@ask stop()
            if (answer == Answer.Yes || answer == Answer.Option(0)) {
                genreCtx = genreCtx.copy(spookyConfirmedWithPassengers = true)
            } else {
                currentGenre = Genre.LOCAL
                genreCtx = genreCtx.copy(override = null)
            }
            finishFlow()
            playNext()
        }
    }

    // ---- 질문과 대답 -------------------------------------------------------------

    private fun ask(id: Int, prompt: String, options: List<String>, onAnswer: (Answer?, Long) -> Unit) {
        if (!parked && !listener.available) {
            // 주행 중 터치 대체 금지. 휴식 안내는 정보만 읽고 질문은 생략합니다.
            if (restHold && !resting) speaker.speak("안전한 곳에서 쉬어 가세요. 음성 확인 없이 경로를 변경하지 않아요.") { guard(id) { onAnswer(null, 0) } }
            else onAnswer(null, 0)
            return
        }
        val asking = PendingQuestion(options, clock.nowMs(), attempt = 1, onAnswer, speaking = true)
        pending = asking
        publish()
        speaker.speak(prompt) {
            guard(id) {
                if (pending !== asking) return@guard // 읽는 도중 이미 대답함
                pending = null
                listenOnce(id, options, attempt = 1, onAnswer)
            }
        }
    }

    private fun listenOnce(id: Int, options: List<String>, attempt: Int, onAnswer: (Answer?, Long) -> Unit) {
        pending = PendingQuestion(options, clock.nowMs(), attempt, onAnswer)
        status = "대답을 기다리는 중"
        publish()
        if (!listener.available) { if (!parked) deliver(null, false); return }
        listener.listen(timing.listenTimeoutMs) { text ->
            guard(id) {
                if (pending == null) return@guard
                if (text == null) deliver(null, heard = false) else deliver(AnswerParser.parse(text, options), heard = true)
            }
        }
    }

    private fun deliver(answer: Answer?, heard: Boolean) {
        val p = pending ?: return
        val id = flowId
        if (p.speaking) speaker.stop()
        val understood = answer != null && answer != Answer.Unknown
        if (!understood) {
            if (p.attempt == 1 && heard && !restHold && listener.available) {
                pending = null
                listener.cancel()
                val again = if (heard) "잘 못 알아들었어요." else "대답이 안 들렸어요."
                speaker.speak("$again 1번, 2번 중에 골라 주세요.") {
                    guard(id) { listenOnce(id, p.options, attempt = 2, p.onAnswer) }
                }
                return
            }
            pending = null
            listener.cancel()
            publish()
            p.onAnswer(null, clock.nowMs() - p.startedMs)
            return
        }
        pending = null
        listener.cancel()
        publish()
        p.onAnswer(answer, clock.nowMs() - p.startedMs)
    }

    // ---- 내부 도우미 -------------------------------------------------------------

    private fun currentStage(): Stage = scorer.stage(
        DriverSignals(
            drivingMinutes = effectiveMinutes(),
            driverAttentionWarning = attentionWarning,
            hour = clock.hour(),

        ),
    )

    private fun endingKmLabel(): Int {
        val atKm = endingAtKm
        val here = distanceKm
        if (atKm != null && here != null) return (atKm - here).roundToInt()
        val atMs = endingAtMs ?: return timing.endingMinKm
        return ((atMs - clock.nowMs()) * timing.fallbackSpeedKmh / 3_600_000L).toInt()
    }

    private fun effectiveMinutes() = (drivingMinutes - restBaselineMinutes).coerceAtLeast(0)

    private fun gapFor(stage: Stage): Long = when {
        waypointAdded -> timing.restGapMs
        stage == Stage.COMPANION -> timing.companionGapMs
        else -> timing.wakeGapMs
    }

    private fun beginFlow(label: String): Int {
        busy = true
        flowId += 1
        status = label
        return flowId
    }

    private fun finishFlow() {
        busy = false
        pending = null
        storyTitle = null
        activeStory = null
        lastFlowEndMs = clock.nowMs()
        if (running) status = when {
            resting -> "쉼터에서 쉬는 중"
            waypointAdded -> "내비게이션 안내 중"
            restHold -> "휴식을 위해 이야기 재생을 멈췄어요"
            else -> "다음 이야기를 기다리는 중"
        }
        publish()
    }

    private fun cancelFlow() {
        flowId += 1
        busy = false
        pending = null
        storyTitle = null
        activeStory = null
        speaker.stop()
        listener.cancel()
    }

    /** 멈추거나 건너뛴 뒤에 늦게 도착한 콜백은 무시합니다 */
    private inline fun guard(id: Int, block: () -> Unit) {
        if (id == flowId) block()
    }

    private fun publish() = onUpdate(view())
}
