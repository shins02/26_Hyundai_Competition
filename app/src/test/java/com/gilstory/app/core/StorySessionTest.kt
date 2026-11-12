package com.gilstory.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StorySessionTest {

    private class FakeSpeaker : Speaker {
        val spoken = mutableListOf<String>()
        override fun speak(text: String, onDone: () -> Unit) {
            spoken += text
            onDone()
        }
        override fun stop() = Unit
    }

    /** 미리 정한 대답을 순서대로 돌려줍니다. null은 "대답 없음" */
    private class ScriptedListener(vararg answers: String?) : AnswerListener {
        private val queue = ArrayDeque(answers.toList())
        override val available = true
        override fun listen(timeoutMs: Long, onResult: (String?) -> Unit) {
            onResult(if (queue.isEmpty()) null else queue.removeFirst())
        }
        override fun cancel() = Unit
    }

    private open class FakeNavigator(private val stop: RestStop?) : RestNavigator {
        var added: RestStop? = null
        override fun nextRestStop() = stop
        override fun addWaypoint(stop: RestStop, onResult: (Boolean) -> Unit) {
            added = stop
            onResult(true)
        }
    }

    private class FakeClock(var now: Long = 1_000_000, var h: Int = 11) : Clock {
        override fun nowMs() = now
        override fun hour() = h
    }

    private val stories = listOf(
        Story("c1", Genre.COMEDY, "횡성군", "한우 넌센스", "한우 이야기.",
            Interaction(InteractionType.QUIZ, "소가 웃으면?", listOf("우하하", "음메"), listOf("정답 해설", "오답 해설"), answer = 0)),
        Story("c2", Genre.COMEDY, null, "공용 개그", "공용 개그."),
        Story("s1", Genre.SPOOKY, "평창군", "고개의 불빛", "오싹한 이야기.",
            Interaction(InteractionType.CHOICE, "차를 세울까요?", listOf("세운다", "그냥 간다"), listOf("결말 A", "결말 B")), fiction = true),
        Story("s2", Genre.SPOOKY, null, "공용 괴담", "공용 괴담.", fiction = true),
        Story("l1", Genre.LOCAL, "평창군", "평창 이야기", "고원 소개."),
        Story("t1", Genre.THINK, null, "생각 하나", "생각할 거리.",
            Interaction(InteractionType.BALANCE, "고른다면?", listOf("A", "B"), listOf("A 멘트", "B 멘트"))),
    )

    private val rest = RestStop("평창휴게소", "휴게소", 4.2)

    private fun session(listener: AnswerListener, nav: FakeNavigator = FakeNavigator(rest), clock: FakeClock = FakeClock(), speaker: FakeSpeaker = FakeSpeaker()) =
        StorySession(StoryPlanner(stories), speaker, listener, nav, clock, timing = StorySession.Timing(warningLeadMs = 0))

    @Test
    fun `새 지역에 들어서면 그 지역 이야기를 먼저 들려준다`() {
        val speaker = FakeSpeaker()
        val s = session(ScriptedListener("1번"), speaker = speaker)
        s.onRegionChanged("횡성군")
        s.start()
        assertTrue(speaker.spoken.first().startsWith("지금 지나는 횡성군 이야기예요."))
        assertTrue(speaker.spoken.any { it.startsWith("정답이에요!") })
    }

    @Test
    fun `지금 장르에 지역 이야기가 없으면 지역 이야기가 있는 장르로 바꾼다`() {
        val speaker = FakeSpeaker()
        val s = session(ScriptedListener(), speaker = speaker)
        s.start() // 낮이라 개그부터
        s.onRegionChanged("평창군")
        s.onTick()
        val last = speaker.spoken.last { it.contains("평창") }
        assertTrue(last.contains("지금 지나는 평창군 이야기예요."))
    }

    @Test fun `무응답만으로 휴식을 권하거나 경로를 변경하지 않는다`() {
        val nav = FakeNavigator(rest); val clock = FakeClock()
        val s = session(ScriptedListener(), nav, clock)
        s.onDrivingMinutes(95); s.start(); repeat(8) { clock.now += 60_000; s.onTick() }
        assertEquals(Stage.WAKE, s.view().stage); assertEquals(0, s.restMetrics.offered); assertNull(nav.added)
    }

    @Test fun `경고는 질문을 취소하고 오디오 우선권 회복 후에만 휴식을 제안한다`() {
        val spoken = mutableListOf<String>(); val done = mutableListOf<() -> Unit>()
        var stopped = false
        val speaker = object : Speaker {
            override fun speak(text: String, onDone: () -> Unit) { spoken += text; done += onDone }
            override fun stop() { stopped = true }
        }
        val nav = FakeNavigator(rest); val clock = FakeClock()
        val s = StorySession(StoryPlanner(stories), speaker, ScriptedListener("네"), nav, clock)
        s.onRegionChanged("횡성군"); s.start(); done.first()()
        assertTrue(s.view().awaitingAnswer); val late = done.last()
        s.onDriverAttention(true); s.onAudioAllowed(false); late(); clock.now += 3_000; s.onTick()
        assertTrue(stopped); assertNull(nav.added); assertTrue(!s.view().awaitingAnswer)
        s.onAudioAllowed(true); assertTrue(spoken.last().contains("경유지로 추가할까요"))
        done.last()(); assertEquals(rest, nav.added); s.onDriverAttention(true)
        assertEquals(1,s.restMetrics.offered)
    }

    @Test fun `운전 중 마이크가 없으면 질문과 화면 대체 입력을 건너뛴다`() {
        val speaker = FakeSpeaker(); val nav = FakeNavigator(rest)
        val s = session(object : AnswerListener {
            override val available = false
            override fun listen(timeoutMs: Long, onResult: (String?) -> Unit) = error("No mic")
            override fun cancel() {}
        }, nav, speaker = speaker)
        s.onRegionChanged("횡성군"); s.start(); s.onDriverAttention(true)
        assertTrue(!s.view().awaitingAnswer); assertNull(nav.added)
        assertTrue(speaker.spoken.none { "소가 웃으면" in it })
    }

    @Test fun `중단한 이야기의 결말은 등록 확인과 도착 및 P단 후 선택해야 재생된다`() {
        var ack: ((Boolean) -> Unit)? = null
        val nav = object : FakeNavigator(rest) { override fun addWaypoint(stop: RestStop, onResult: (Boolean) -> Unit) { ack = onResult } }
        val spoken = mutableListOf<String>()
        val speaker = object : Speaker {
            override fun speak(text: String, onDone: () -> Unit) {
                spoken += text; if (!text.contains("한우 이야기.")) onDone()
            }
            override fun stop() {}
        }
        val clock = FakeClock()
        val s = StorySession(StoryPlanner(stories), speaker, ScriptedListener("네","네"), nav, clock)
        s.onRegionChanged("횡성군"); s.start(); s.onDriverAttention(true); clock.now += 2_000; s.onTick()
        assertNotNull(ack); assertTrue(spoken.none { "반영됐어요" in it })
        s.onWaypointArrived(); assertTrue(!s.view().resting)
        ack!!(true); assertTrue(spoken.any { "반영됐어요" in it })
        s.onWaypointArrived(); assertTrue(!s.view().resting); assertTrue("정답 해설" !in spoken)
        s.onParked(true); assertTrue(s.view().resting); assertEquals(1, spoken.count { it == "정답 해설" })
        s.onParked(true); s.onWaypointArrived(); s.onParked(false)
        assertEquals(1, spoken.count { it == "정답 해설" }); assertEquals(1,s.restMetrics.arrived)
    }

    @Test
    fun `동승자가 있으면 오싹한 이야기 전에 묻고, 아니요면 다른 장르로 바꾼다`() {
        val speaker = FakeSpeaker()
        val s = session(ScriptedListener("네", "아니요"), speaker = speaker, clock = FakeClock(h = 22))
        s.onRearOccupied(true)
        s.onNight(true)
        s.start()
        // 밤 모드 제안 "네" → 동승자 확인 "아니요" → 지역 이야기
        assertTrue(speaker.spoken.any { it.startsWith("뒷좌석에 함께 타신 분이 있네요") })
        assertTrue(s.view().genre != Genre.SPOOKY)
        assertTrue(speaker.spoken.none { it.contains("오싹한 이야기.") || it.contains("공용 괴담.") })
    }

    @Test
    fun `질문을 읽는 도중 버튼으로 바로 대답할 수 있다`() {
        val speaker = object : Speaker {
            val spoken = mutableListOf<String>()
            var pendingDone: (() -> Unit)? = null
            override fun speak(text: String, onDone: () -> Unit) {
                spoken += text
                // 질문은 읽는 중인 상태로 남겨 두고, 나머지는 바로 끝냅니다
                if (text.contains("소가 웃으면")) pendingDone = onDone else onDone()
            }
            override fun stop() { pendingDone = null }
        }
        val s = StorySession(StoryPlanner(stories), speaker, ScriptedListener(), FakeNavigator(rest), FakeClock())
        s.onRegionChanged("횡성군")
        s.start()
        assertTrue(s.view().awaitingAnswer)
        s.submitAnswer(Answer.Option(0))
        assertTrue(speaker.spoken.last().startsWith("정답이에요!"))
    }

    @Test
    fun `그만이라고 하면 멈춘다`() {
        val speaker = FakeSpeaker()
        val s = session(ScriptedListener("그만"), speaker = speaker)
        s.onRegionChanged("횡성군")
        s.start()
        assertEquals(false, s.view().running)
        assertNull(s.view().storyTitle)
    }

    @Test
    fun `쉼터 정보가 없어도 쉬라고 권한다`() {
        val speaker = FakeSpeaker()
        val s = session(ScriptedListener(), FakeNavigator(null), speaker = speaker)
        s.start()
        s.onDriverAttention(true)
        assertNotNull(speaker.spoken.firstOrNull { it.contains("안전한 곳에서 쉬어 가세요") })
    }

    @Test fun `경로 위 시설은 경유지를 추가하지 않고 P단만으로 도착을 기록하지 않는다`() {
        val nav = FakeNavigator(rest.copy(onRoute = true)); val s = session(ScriptedListener("네"), nav)
        s.onDriverAttention(true); s.start(); s.onParked(true)
        assertNull(nav.added); assertTrue(!s.view().resting)
        s.onWaypointArrived(); assertTrue(s.view().resting)
    }

    @Test fun `거절과 무응답에는 재질문이나 자동 경로 변경이 없다`() {
        for (value in listOf("아니요", null, "네비 바꾸지 마")) {
            val nav = FakeNavigator(rest); val clock = FakeClock()
            val s = session(ScriptedListener(value, "네"), nav, clock)
            s.onDriverAttention(true); s.start(); clock.now += 600_000; s.onTick()
            s.onRegionChanged("평창군"); s.changeGenre(Genre.SPOOKY); s.skip(); s.onTick()
            assertNull(nav.added); assertEquals(1,s.restMetrics.offered); assertEquals(0,s.restMetrics.accepted)
        }
    }

    @Test fun `내비 등록 실패를 성공이나 도착으로 안내하지 않는다`() {
        val speaker = FakeSpeaker()
        val nav = object : FakeNavigator(rest) { override fun addWaypoint(stop: RestStop, onResult: (Boolean) -> Unit) { onResult(false) } }
        val s = session(ScriptedListener("네"), nav, speaker = speaker)
        s.onDriverAttention(true); s.start(); s.onWaypointArrived(); s.onParked(true)
        assertTrue(speaker.spoken.any { "확인하지 못했어요" in it }); assertTrue(!s.view().resting)
    }

    /** "한우 이야기."를 읽는 중에는 끝나지 않는 스피커 (이야기 도중 경고가 오는 장면) */
    private class HangingSpeaker : Speaker {
        val spoken = mutableListOf<String>()
        override fun speak(text: String, onDone: () -> Unit) {
            spoken += text
            if (!text.contains("한우 이야기.")) onDone()
        }
        override fun stop() {}
    }

    private fun continueSession(listener: AnswerListener, speaker: Speaker, clock: FakeClock, nav: FakeNavigator = FakeNavigator(rest)) =
        StorySession(StoryPlanner(stories), speaker, listener, nav, clock, timing = StorySession.Timing(warningLeadMs = 0)).also {
            it.setRestStoryMode(RestStoryMode.CONTINUE)
        }

    @Test fun `이어 듣기는 휴식 안내 뒤에도 이야기를 이어 가고 결말은 10~15km 뒤에 들려준다`() {
        val speaker = HangingSpeaker(); val clock = FakeClock()
        val s = continueSession(ScriptedListener("아니요"), speaker, clock)
        s.onDistanceKm(100.0)
        s.onRegionChanged("횡성군"); s.start()
        s.onDrivingMinutes(120) // 「한우 넌센스」 도중 장시간 운전 → 쉼터 안내 → "아니요"
        assertTrue(speaker.spoken.any { "경유지로 추가할까요" in it })

        clock.now += 60_000; s.onTick()
        val notice = speaker.spoken.last()
        assertTrue(notice.startsWith("「한우 넌센스」 결말은 "))
        val km = Regex("결말은 (\\d+)킬로미터").find(notice)!!.groupValues[1].toInt()
        assertTrue(km in 10..15)

        clock.now += 60_000; s.onTick() // 결말을 기다리는 동안 다른 이야기를 이어 감
        val told = speaker.spoken.size
        s.onDistanceKm(100.0 + km - 1)
        assertTrue("정답 해설" !in speaker.spoken)
        assertTrue(told > speaker.spoken.indexOf(notice) + 1)

        s.onDistanceKm(100.0 + km)
        assertTrue(speaker.spoken.any { it.startsWith("기다리셨죠. 아까 「한우 넌센스」 결말") })
        assertEquals("정답 해설", speaker.spoken.last())
        s.onDistanceKm(200.0); assertEquals(1, speaker.spoken.count { it == "정답 해설" })
    }

    @Test fun `주행 거리 신호가 없으면 평균 속도로 바꾼 시간 뒤에 결말을 들려준다`() {
        val speaker = HangingSpeaker(); val clock = FakeClock()
        val s = continueSession(ScriptedListener("아니요"), speaker, clock)
        s.onRegionChanged("횡성군"); s.start(); s.onDrivingMinutes(120)
        clock.now += 60_000; s.onTick() // 예고
        clock.now += 5 * 60_000; s.onTick()
        assertTrue("정답 해설" !in speaker.spoken) // 90km/h로 10km는 약 6.7분
        clock.now += 6 * 60_000; s.onTick()
        assertTrue("정답 해설" in speaker.spoken)
    }

    @Test fun `이어 듣기에서도 쉼터에 먼저 도착해 정차하면 그때 결말을 들려주고, 다시 출발하면 이야기를 이어 간다`() {
        val speaker = HangingSpeaker(); val clock = FakeClock(); val nav = FakeNavigator(rest)
        val s = continueSession(ScriptedListener("네", "네"), speaker, clock, nav)
        s.onDistanceKm(100.0)
        s.onRegionChanged("횡성군"); s.start(); s.onDriverAttention(true)
        assertEquals(rest, nav.added)
        s.onWaypointArrived(); s.onParked(true)
        assertTrue(s.view().resting)
        assertEquals("정답 해설", speaker.spoken.last())

        s.onDriverAttention(false); s.onParked(false)
        assertTrue(!s.view().resting)
        s.onDistanceKm(130.0); clock.now += 10 * 60_000; s.onTick()
        assertEquals(1, speaker.spoken.count { it == "정답 해설" })
        assertTrue(speaker.spoken.none { it.startsWith("기다리셨죠") })
    }

    @Test fun `이어 듣기를 켰어도 실제 차량 경고 뒤에는 재생과 거리 기반 결말이 멈춘다`() {
        val speaker = HangingSpeaker()
        val clock = FakeClock()
        val session = continueSession(ScriptedListener("아니요"), speaker, clock)
        session.onDistanceKm(100.0)
        session.onRegionChanged("횡성군")
        session.start()
        session.onDriverAttention(true)
        val heardAfterWarning = speaker.spoken.toList()
        session.onDistanceKm(140.0)
        session.onDriverAttention(false)
        clock.now += 900_000
        session.onTick()
        session.skip()
        assertEquals(heardAfterWarning, speaker.spoken)
        assertEquals(1, session.restMetrics.offered)
    }

    @Test fun `기본 쉬어 가기에서는 휴식 안내 뒤 주행 중에 이야기를 다시 틀지 않는다`() {
        val speaker = HangingSpeaker(); val clock = FakeClock()
        val s = StorySession(StoryPlanner(stories), speaker, ScriptedListener("아니요"), FakeNavigator(rest), clock, timing = StorySession.Timing(warningLeadMs = 0))
        s.onDistanceKm(100.0)
        s.onRegionChanged("횡성군"); s.start(); s.onDriverAttention(true)
        val after = speaker.spoken.size
        clock.now += 30 * 60_000; s.onTick(); s.onDistanceKm(150.0)
        assertEquals(after, speaker.spoken.size)
    }
}
