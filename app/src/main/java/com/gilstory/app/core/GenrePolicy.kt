package com.gilstory.app.core

data class GenreContext(
    /** 운전자가 정차 중에 고른 장르. 비어 있으면 모든 장르 */
    val preferred: Set<Genre> = Genre.entries.toSet(),
    /** 운전자가 주행 중에 직접 고른 장르 ("무서운 거" 등). 있으면 최우선 */
    val override: Genre? = null,
    val isNight: Boolean = false,
    /** 가족 모드: 공포 장르를 숨깁니다 */
    val familyMode: Boolean = false,
    /** 뒷좌석 동승자 착석 여부 (착석 정보를 못 받으면 false) */
    val rearOccupied: Boolean = false,
    /** 동승자가 있을 때 오싹한 이야기를 계속하기로 운전자가 동의했는지 */
    val spookyConfirmedWithPassengers: Boolean = false,
)

object GenrePolicy {

    fun allowed(ctx: GenreContext): Set<Genre> {
        val base = ctx.preferred.ifEmpty { Genre.entries.toSet() }.toMutableSet()
        if (ctx.familyMode) base.remove(Genre.SPOOKY)
        if (ctx.rearOccupied && !ctx.spookyConfirmedWithPassengers) base.remove(Genre.SPOOKY)
        return base.ifEmpty { setOf(Genre.LOCAL) }
    }

    /** 시간대 기본값: 낮에는 개그, 밤에는 오싹한 이야기. 허용되지 않으면 지역 이야기 */
    fun recommended(ctx: GenreContext): Genre {
        val allowed = allowed(ctx)
        ctx.override?.let { if (it in allowed) return it }
        val byTime = if (ctx.isNight) Genre.SPOOKY else Genre.COMEDY
        return when {
            byTime in allowed -> byTime
            Genre.LOCAL in allowed -> Genre.LOCAL
            else -> allowed.first()
        }
    }

    /** 대답이 둔해졌을 때 자극을 바꾸기 위한 다음 장르 */
    fun rotate(current: Genre, ctx: GenreContext): Genre {
        val order = listOf(Genre.COMEDY, Genre.THINK, Genre.LOCAL, Genre.SPOOKY)
        val allowed = allowed(ctx)
        val start = order.indexOf(current)
        for (i in 1..order.size) {
            val next = order[(start + i) % order.size]
            if (next in allowed) return next
        }
        return current
    }

    /** 동승자가 있는데 오싹한 이야기를 틀기 전에 물어봐야 하는지 */
    fun needsPassengerCheck(ctx: GenreContext, genre: Genre): Boolean =
        genre == Genre.SPOOKY && ctx.rearOccupied && !ctx.spookyConfirmedWithPassengers && !ctx.familyMode
}
