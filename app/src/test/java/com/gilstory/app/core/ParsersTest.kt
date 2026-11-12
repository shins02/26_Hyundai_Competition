package com.gilstory.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsersTest {

    @Test
    fun `번호와 선택지 내용을 알아듣는다`() {
        val options = listOf("도자기", "녹차")
        assertEquals(Answer.Option(0), AnswerParser.parse("1번", options))
        assertEquals(Answer.Option(1), AnswerParser.parse("2번이요", options))
        assertEquals(Answer.Option(0), AnswerParser.parse("도자기", options))
        assertEquals(Answer.Option(1), AnswerParser.parse("음 녹차?", options))
        assertEquals(Answer.Option(0), AnswerParser.parse("이번엔 1번", options))
    }

    @Test
    fun `명령어를 알아듣는다`() {
        assertEquals(Answer.Stop, AnswerParser.parse("그만해"))
        assertEquals(Answer.Skip, AnswerParser.parse("다른 거"))
        assertEquals(Answer.ChangeGenre(Genre.SPOOKY), AnswerParser.parse("무서운 거 해 줘"))
        assertEquals(Answer.ChangeGenre(Genre.COMEDY), AnswerParser.parse("웃긴 거"))
        assertEquals(Answer.Yes, AnswerParser.parse("응"))
        assertEquals(Answer.No, AnswerParser.parse("아니요"))
        assertEquals(Answer.Unknown, AnswerParser.parse(""))
        assertEquals(Answer.Unknown, AnswerParser.parse(null))
    }

    @Test
    fun `주소에서 시군구를 뽑는다`() {
        assertEquals("횡성군", RegionParser.regionOf("강원특별자치도 횡성군 둔내면 영동고속도로"))
        assertEquals("용인시", RegionParser.regionOf("경기도 용인시 처인구 양지면"))
        assertEquals("서울특별시", RegionParser.regionOf("서울특별시 강남구 역삼동"))
        assertEquals(null, RegionParser.regionOf(""))
        assertEquals(null, RegionParser.regionOf(null))
    }

    @Test
    fun `졸음 단계를 판단한다`() {
        val scorer = DrowsinessScorer()
        assertEquals(Stage.COMPANION, scorer.stage(DriverSignals(drivingMinutes = 30, hour = 11)))
        assertEquals(Stage.WAKE, scorer.stage(DriverSignals(drivingMinutes = 95, hour = 11)))
        assertEquals(Stage.REST, scorer.stage(DriverSignals(drivingMinutes = 125, hour = 11)))
        assertEquals(Stage.REST, scorer.stage(DriverSignals(drivingMinutes = 10, driverAttentionWarning = true)))
        // 새벽에는 더 일찍 깨우기 단계로
        assertEquals(Stage.WAKE, scorer.stage(DriverSignals(drivingMinutes = 60, hour = 3)))
    }

    @Test
    fun `경로 변경은 독립된 동의만 해석한다`() {
        val o = listOf("네","아니요")
        assertEquals(Answer.Yes, AnswerParser.parse("네",o)); assertEquals(Answer.No, AnswerParser.parse("아니요",o))
        assertEquals(Answer.Unknown, AnswerParser.parse("네비 바꾸지 마",o)); assertEquals(Answer.Unknown, AnswerParser.parse("1번은 아니야",o))
    }

    @Test
    fun `장르 정책은 가족 모드와 동승자를 지킨다`() {
        val night = GenreContext(isNight = true)
        assertEquals(Genre.SPOOKY, GenrePolicy.recommended(night))
        assertEquals(Genre.COMEDY, GenrePolicy.recommended(GenreContext(isNight = false)))
        assertEquals(Genre.LOCAL, GenrePolicy.recommended(night.copy(familyMode = true)))
        assertFalse(Genre.SPOOKY in GenrePolicy.allowed(night.copy(rearOccupied = true)))
        assertTrue(Genre.SPOOKY in GenrePolicy.allowed(night.copy(rearOccupied = true, spookyConfirmedWithPassengers = true)))
        assertTrue(GenrePolicy.needsPassengerCheck(night.copy(rearOccupied = true), Genre.SPOOKY))
        assertEquals(Genre.THINK, GenrePolicy.rotate(Genre.COMEDY, GenreContext()))
        assertEquals(Genre.COMEDY, GenrePolicy.rotate(Genre.LOCAL, GenreContext(familyMode = true)))
    }
}
