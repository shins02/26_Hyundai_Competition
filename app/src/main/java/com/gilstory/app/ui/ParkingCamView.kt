package com.gilstory.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.gilstory.app.core.CamFacing
import kotlin.math.max
import kotlin.math.min

/**
 * 주차 시연 때 지도 자리에 띄우는 가상 주차장 카메라 화면.
 *
 * Pleos 공개 SDK에는 후방 카메라·빌트인캠 영상을 앱이 받는 기능이 없어서, 지하 주차장을 직접 그려 보여 줍니다.
 * 화면 위쪽에 "가상 주차장 · 시연 화면"이라고 늘 적어 실제 영상과 헷갈리지 않게 합니다.
 *
 * 순서: 통로 주행(D, 앞쪽) → 1차 후진(R, 뒤쪽 + 가이드라인) → 앞으로 빼서 고쳐 대기(D, 앞쪽)
 * → 2차 후진(R) → 주차 완료(P). 어느 쪽 카메라든 기둥 글자를 읽으면 네모로 표시합니다.
 * 장면은 미터 단위 3차원 좌표를 원근 투영해서 그립니다 (X: 오른쪽, Y: 높이, z: 카메라에서 앞으로).
 */
class ParkingCamView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    enum class Phase { AISLE, REVERSE, FORWARD, PARKED }

    private var phase = Phase.AISLE
    private var phaseStartMs = 0L
    private var reverseMs = 6_000L
    private var fromWall = START_WALL
    private var toWall = MID_WALL
    /** 마지막으로 그린 뒷벽까지 거리 (다음 장면을 이어 붙이려고 기억합니다) */
    private var wallNow = START_WALL
    /** 빌트인캠이 읽은 글자 (기둥 표지) */
    private var readText: String? = null
    private var doneText: String = ""

    private val dp = resources.displayMetrics.density

    // ---- 장면 값 (미터) ----
    private val camH = 1.0
    private val floorName = "B2"
    private val leftPillar = "C-16"
    private val rightPillar = "C-17"

    /** D단: 주차장 통로를 앞으로 달리는 화면 */
    fun startAisle() {
        phase = Phase.AISLE
        wallNow = START_WALL
        readText = null
        phaseStartMs = SystemClock.uptimeMillis()
        invalidate()
    }

    /** R단: 뒤쪽 카메라로 바꾸고 [durationMs] 동안 주차 칸 안으로 물러납니다. [final]이면 멈춤턱 앞까지 */
    fun startReverse(durationMs: Long, final: Boolean) {
        phase = Phase.REVERSE
        reverseMs = durationMs.coerceAtLeast(1_000L)
        fromWall = if (final) wallNow + PULL_FORWARD else START_WALL
        toWall = if (final) END_WALL else MID_WALL
        readText = null
        phaseStartMs = SystemClock.uptimeMillis()
        invalidate()
    }

    /** D단으로 고쳐 대기: 앞쪽 카메라로 바꾸고 조금 앞으로 나갑니다 */
    fun startForward(durationMs: Long) {
        phase = Phase.FORWARD
        reverseMs = durationMs.coerceAtLeast(1_000L)
        readText = null
        phaseStartMs = SystemClock.uptimeMillis()
        invalidate()
    }

    /** 빌트인캠 한 장면에서 읽은 글자 (예: "B2 C-17") */
    fun showRead(text: String, facing: CamFacing) {
        readText = text
        invalidate()
    }

    /** P단: 주차 완료 */
    fun finish(summary: String) {
        phase = Phase.PARKED
        doneText = summary
        invalidate()
    }


    // ---- 그리기 도구 ----
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

    private var w = 0f
    private var h = 0f
    private var cx = 0f
    private var horizon = 0f
    private var focal = 0f

    private fun sx(x: Double, z: Double) = (cx + focal * x / z).toFloat()
    private fun sy(y: Double, z: Double) = (horizon + focal * (camH - y) / z).toFloat()

    /** 바닥(Y=0) 위 사각형: x1..x2, z1..z2 */
    private fun floorQuad(canvas: Canvas, x1: Double, x2: Double, z1: Double, z2: Double, color: Int) {
        val a = max(z1, NEAR); val b = max(z2, NEAR)
        if (b <= a) return
        val p = Path().apply {
            moveTo(sx(x1, a), sy(0.0, a)); lineTo(sx(x2, a), sy(0.0, a))
            lineTo(sx(x2, b), sy(0.0, b)); lineTo(sx(x1, b), sy(0.0, b)); close()
        }
        fill.color = color
        canvas.drawPath(p, fill)
    }

    /** 카메라를 바라보는 세운 면: x1..x2, y1..y2, 깊이 z */
    private fun face(x1: Double, x2: Double, y1: Double, y2: Double, z: Double) =
        RectF(sx(x1, z), sy(y2, z), sx(x2, z), sy(y1, z))

    /** 기둥: 앞면과 통로 쪽 옆면, 그리고 층·기둥 번호 표지. 표지 글자 칸을 돌려줍니다 */
    private fun pillar(canvas: Canvas, x1: Double, x2: Double, zFront: Double, depth: Double, label: String, sideOnLeft: Boolean): RectF? {
        if (zFront < NEAR) return null
        val zBack = zFront + depth
        // 옆면 (가운데 쪽)
        val sideX = if (sideOnLeft) x1 else x2
        val side = Path().apply {
            moveTo(sx(sideX, zFront), sy(0.0, zFront)); lineTo(sx(sideX, zBack), sy(0.0, zBack))
            lineTo(sx(sideX, zBack), sy(WALL_H, zBack)); lineTo(sx(sideX, zFront), sy(WALL_H, zFront)); close()
        }
        fill.color = 0xFF8C8F93.toInt()
        canvas.drawPath(side, fill)
        val front = face(x1, x2, 0.0, WALL_H, zFront)
        fill.color = 0xFFB4B7BB.toInt()
        canvas.drawRect(front, fill)
        // 아래쪽 노랑·검정 띠
        val band = face(x1, x2, 0.0, 0.35, zFront)
        fill.color = 0xFFF2C230.toInt()
        canvas.drawRect(band, fill)
        fill.color = 0xFF222222.toInt()
        val stripes = 4
        for (i in 0 until stripes step 2) {
            val bw = band.width() / stripes
            canvas.drawRect(band.left + i * bw, band.top, band.left + (i + 1) * bw, band.bottom, fill)
        }
        // 표지: 위쪽 초록 띠에 층, 아래 흰 판에 기둥 번호
        val sign = face(x1 + 0.06, x2 - 0.06, 1.15, 1.95, zFront)
        val floorBand = RectF(sign.left, sign.top, sign.right, sign.top + sign.height() * 0.38f)
        fill.color = 0xFF1B7F4B.toInt()
        canvas.drawRect(floorBand, fill)
        fill.color = 0xFFF7F7F2.toInt()
        canvas.drawRect(sign.left, floorBand.bottom, sign.right, sign.bottom, fill)
        text.typeface = bold
        text.textAlign = Paint.Align.CENTER
        text.color = 0xFFFFFFFF.toInt()
        text.textSize = floorBand.height() * 0.78f
        canvas.drawText(floorName, floorBand.centerX(), floorBand.bottom - floorBand.height() * 0.18f, text)
        text.color = 0xFF1A1A1A.toInt()
        val numH = sign.bottom - floorBand.bottom
        text.textSize = min(numH * 0.7f, sign.width() * 0.34f)
        canvas.drawText(label, sign.centerX(), sign.bottom - numH * 0.25f, text)
        return sign
    }

    private fun ceilingLight(canvas: Canvas, x: Double, z: Double) {
        if (z < NEAR) return
        val r = face(x - 0.6, x + 0.6, WALL_H - 0.04, WALL_H, z)
        r.bottom = max(r.bottom, r.top + 3 * dp)
        fill.color = 0xFFEFF6FF.toInt()
        canvas.drawRect(r, fill)
    }

    private fun progressNow(): Double {
        val t = ((SystemClock.uptimeMillis() - phaseStartMs).toDouble() / reverseMs).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t) // 천천히 출발해 천천히 멈춥니다
    }

    override fun onDraw(canvas: Canvas) {
        w = width.toFloat(); h = height.toFloat()
        if (w <= 0 || h <= 0) return
        cx = w / 2
        horizon = h * 0.36f
        focal = h * 0.62f // 후방 카메라처럼 넓게 봅니다

        val clip = Path().apply { addRoundRect(RectF(0f, 0f, w, h), 22 * dp, 22 * dp, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(clip)

        // 천장·바닥
        fill.shader = LinearGradient(0f, 0f, 0f, horizon, 0xFF2B2F36.toInt(), 0xFF3C424A.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, horizon, fill)
        fill.shader = LinearGradient(0f, horizon, 0f, h, 0xFF4E545C.toInt(), 0xFF6B7178.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, horizon, w, h, fill)
        fill.shader = null

        when (phase) {
            Phase.AISLE -> drawAisle(canvas)
            Phase.REVERSE, Phase.PARKED -> drawBay(canvas)
            Phase.FORWARD -> drawFront(canvas)
        }

        // 카메라 화면 느낌: 가장자리를 살짝 어둡게
        fill.shader = LinearGradient(0f, h * 0.75f, 0f, h, 0x00000000, 0x66000000, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, h * 0.75f, w, h, fill)
        fill.shader = null

        drawHud(canvas)
        canvas.restore()

        val moving = phase == Phase.AISLE ||
            ((phase == Phase.REVERSE || phase == Phase.FORWARD) && SystemClock.uptimeMillis() - phaseStartMs < reverseMs)
        if (moving && isShown) postInvalidateOnAnimation()
    }

    /** D단: 통로 앞쪽. 기둥이 양옆으로 지나갑니다 */
    private fun drawAisle(canvas: Canvas) {
        val travel = (SystemClock.uptimeMillis() - phaseStartMs) / 1000.0 * AISLE_SPEED
        // 통로 가운데 노란 점선
        val dash = 3.0
        var z = dash - (travel % dash) - dash
        while (z < 40) {
            floorQuad(canvas, -0.06, 0.06, z, z + 1.4, 0xFFE9C341.toInt())
            z += dash
        }
        // 양옆 주차 칸 앞 흰 선
        floorQuad(canvas, -3.05, -2.95, 0.0, 40.0, 0xCCF2F2F2.toInt())
        floorQuad(canvas, 2.95, 3.05, 0.0, 40.0, 0xCCF2F2F2.toInt())
        val gap = 7.5
        val first = travel % gap
        val labels = listOf("C-12", "C-13", "C-14", "C-15")
        val passed = (travel / gap).toInt()
        // 먼 것부터 그립니다
        for (i in 5 downTo 0) {
            val pz = (i + 1) * gap - first
            ceilingLight(canvas, 0.0, pz - gap / 2)
            val label = labels[(passed + i) % labels.size]
            pillar(canvas, -3.9, -3.3, pz, 0.6, label, sideOnLeft = false)
            pillar(canvas, 3.3, 3.9, pz, 0.6, label, sideOnLeft = true)
        }
        drawReadBox(canvas, null)
    }

    /** R단·P단: 주차 칸 뒤쪽. 뒷벽에 다가갑니다 */
    private fun drawBay(canvas: Canvas) {
        val wall = if (phase == Phase.PARKED) wallNow else fromWall - (fromWall - toWall) * progressNow()
        wallNow = wall
        // 뒷벽
        val back = face(-8.0, 8.0, 0.0, WALL_H, wall)
        fill.color = 0xFF9EA2A7.toInt()
        canvas.drawRect(back, fill)
        fill.color = 0xFF7D8288.toInt()
        canvas.drawRect(face(-8.0, 8.0, 0.0, 0.25, wall), fill)
        // 바닥 주차 칸 선 (우리 칸 가운데, 양옆 칸)
        for (x in listOf(-3.75, -1.25, 1.25, 3.75)) floorQuad(canvas, x - 0.06, x + 0.06, 0.0, wall, 0xFFF2F2F2.toInt())
        // 바퀴 멈춤턱
        val stopZ = wall - 0.55
        if (stopZ > NEAR) {
            fill.color = 0xFFF2C230.toInt()
            canvas.drawRect(face(-0.85, -0.25, 0.0, 0.14, stopZ), fill)
            canvas.drawRect(face(0.25, 0.85, 0.0, 0.14, stopZ), fill)
        }
        ceilingLight(canvas, 0.0, wall - 1.5)
        ceilingLight(canvas, 0.0, wall - 5.0)
        // 기둥: 왼쪽 옆 칸 너머 C-16, 우리 칸 오른쪽 C-17
        val leftSign = pillar(canvas, -4.35, -3.75, wall - 0.6, 0.6, leftPillar, sideOnLeft = false)
        val rightSign = pillar(canvas, 1.45, 2.05, wall - 0.6, 0.6, rightPillar, sideOnLeft = true)

        // 후진 가이드라인 (차에 붙어 있으니 화면에 고정)
        if (phase == Phase.REVERSE) {
            line.strokeWidth = 5 * dp
            val bands = listOf(0.5 to 1.0 to 0xFFE53935.toInt(), 1.0 to 2.0 to 0xFFFDD835.toInt(), 2.0 to 3.2 to 0xFF43A047.toInt())
            for ((range, color) in bands) {
                line.color = color
                val (a, b) = range
                for (x in listOf(-0.95, 0.95)) canvas.drawLine(sx(x, a), sy(0.0, a), sx(x, b), sy(0.0, b), line)
                canvas.drawLine(sx(-0.95, b), sy(0.0, b), sx(-0.7, b), sy(0.0, b), line)
                canvas.drawLine(sx(0.95, b), sy(0.0, b), sx(0.7, b), sy(0.0, b), line)
            }
        }

        // 글자 인식 네모
        val read = readText
        if (read != null && phase == Phase.REVERSE) {
            drawReadBox(canvas, when {
                read.endsWith(leftPillar) -> leftSign
                read.endsWith(rightPillar) -> rightSign
                else -> null
            })
        }

        if (phase == Phase.PARKED) {
            val r = RectF(w / 2 - min(w * 0.42f, 360 * dp), h * 0.56f, w / 2 + min(w * 0.42f, 360 * dp), h * 0.56f + 120 * dp)
            fill.color = 0xE6146C43.toInt()
            canvas.drawRoundRect(r, 18 * dp, 18 * dp, fill)
            text.typeface = bold
            text.textAlign = Paint.Align.CENTER
            text.color = 0xFFFFFFFF.toInt()
            text.textSize = 30 * dp
            canvas.drawText("🅿 주차 완료", r.centerX(), r.top + 48 * dp, text)
            text.textSize = 26 * dp
            canvas.drawText(doneText, r.centerX(), r.top + 94 * dp, text)
        }
    }

    /** D단으로 고쳐 댈 때: 주차 칸에서 통로 쪽을 보는 앞쪽 카메라. 옆 기둥(C-17) 표지가 오른쪽에 보입니다 */
    private fun drawFront(canvas: Canvas) {
        val p = progressNow()
        val pillarZ = 3.4 - PULL_FORWARD * p // 앞으로 나가며 기둥이 가까워집니다
        val aisleStart = pillarZ + 0.6
        val opposite = aisleStart + 6.0
        // 건너편 벽과 주차 칸
        fill.color = 0xFF9EA2A7.toInt()
        canvas.drawRect(face(-12.0, 12.0, 0.0, WALL_H, opposite + 5.0), fill)
        for (x in listOf(-6.25, -3.75, -1.25, 1.25, 3.75, 6.25)) floorQuad(canvas, x - 0.06, x + 0.06, opposite, opposite + 5.0, 0xFFF2F2F2.toInt())
        ceilingLight(canvas, 0.0, opposite + 2.5)
        pillar(canvas, -4.35, -3.75, opposite, 0.6, "C-31", sideOnLeft = false)
        pillar(canvas, 1.45, 2.05, opposite, 0.6, "C-32", sideOnLeft = true)
        // 통로: 가로 노란 점선
        var x = -9.0
        while (x < 9.0) {
            floorQuad(canvas, x, x + 1.4, aisleStart + 2.95, aisleStart + 3.05, 0xFFE9C341.toInt())
            x += 3.0
        }
        ceilingLight(canvas, 0.0, aisleStart + 3.0)
        // 우리 주차 칸 옆 선과 옆 기둥
        for (lx in listOf(-1.25, 1.25)) floorQuad(canvas, lx - 0.06, lx + 0.06, 0.0, aisleStart, 0xFFF2F2F2.toInt())
        frontSign = pillar(canvas, 1.45, 2.05, pillarZ, 0.6, rightPillar, sideOnLeft = true)
        drawReadBox(canvas, frontSign.takeIf { readText?.endsWith(rightPillar) == true })
    }

    private var frontSign: RectF? = null

    /** 읽은 글자가 있는 표지에 네모와 꼬리표를 그립니다 */
    private fun drawReadBox(canvas: Canvas, box: RectF?) {
        val read = readText ?: return
        if (box == null) {
            label(canvas, "인식: $read", w / 2, h - 24 * dp, 0xFF00E5FF.toInt(), 0xFF00363D.toInt())
            return
        }
        val pad = 6 * dp
        val r = RectF(box.left - pad, box.top - pad, box.right + pad, box.bottom + pad)
        line.color = 0xFF00E5FF.toInt()
        line.strokeWidth = 4 * dp
        canvas.drawRoundRect(r, 6 * dp, 6 * dp, line)
        label(canvas, "인식: $read", r.centerX(), r.top - 10 * dp, 0xFF00E5FF.toInt(), 0xFF00363D.toInt())
    }

    /** 글자 바탕이 있는 작은 꼬리표 (아래 가운데 기준) */
    private fun label(canvas: Canvas, s: String, x: Float, bottom: Float, bg: Int, fg: Int) {
        text.typeface = bold
        text.textSize = 20 * dp
        text.textAlign = Paint.Align.CENTER
        val tw = text.measureText(s) + 24 * dp
        val r = RectF(x - tw / 2, bottom - 34 * dp, x + tw / 2, bottom)
        val left = r.left.coerceAtLeast(12 * dp)
        r.offset(left - r.left, 0f)
        r.offset(min(0f, w - 12 * dp - r.right), 0f)
        fill.color = bg
        canvas.drawRoundRect(r, 10 * dp, 10 * dp, fill)
        text.color = fg
        canvas.drawText(s, r.centerX(), r.bottom - 10 * dp, text)
    }

    /** 위쪽: 어느 카메라인지와 시연 화면 표시, 오른쪽에 기어 */
    private fun drawHud(canvas: Canvas) {
        val title = when (phase) {
            Phase.AISLE -> "📷 앞쪽 카메라 · 주차장 통로"
            Phase.FORWARD -> "📷 앞쪽 카메라 · 고쳐 대는 중"
            else -> "📷 후방 카메라 · 주차 칸"
        }
        text.typeface = bold
        text.textAlign = Paint.Align.LEFT
        text.textSize = 24 * dp
        val tw = text.measureText(title)
        val r = RectF(20 * dp, 20 * dp, 20 * dp + tw + 32 * dp, 68 * dp)
        fill.color = 0xCC111418.toInt()
        canvas.drawRoundRect(r, 16 * dp, 16 * dp, fill)
        text.color = 0xFFFFFFFF.toInt()
        canvas.drawText(title, r.left + 16 * dp, r.bottom - 15 * dp, text)

        text.typeface = Typeface.DEFAULT
        text.textSize = 18 * dp
        text.color = 0xFFB8C0CC.toInt()
        val note = "가상 주차장 · 시연 화면 (실제 카메라 영상 아님)"
        val nw = text.measureText(note)
        val nr = RectF(20 * dp, 76 * dp, 20 * dp + nw + 24 * dp, 108 * dp)
        fill.color = 0x99111418.toInt()
        canvas.drawRoundRect(nr, 12 * dp, 12 * dp, fill)
        canvas.drawText(note, nr.left + 12 * dp, nr.bottom - 9 * dp, text)

        val gear = when (phase) { Phase.AISLE, Phase.FORWARD -> "D"; Phase.REVERSE -> "R"; Phase.PARKED -> "P" }
        val gr = RectF(w - 96 * dp, 20 * dp, w - 20 * dp, 96 * dp)
        fill.color = when (phase) { Phase.REVERSE -> 0xFFE53935.toInt(); Phase.PARKED -> 0xFF1E88E5.toInt(); else -> 0xCC111418.toInt() }
        canvas.drawRoundRect(gr, 16 * dp, 16 * dp, fill)
        text.typeface = bold
        text.textAlign = Paint.Align.CENTER
        text.textSize = 48 * dp
        text.color = 0xFFFFFFFF.toInt()
        canvas.drawText(gear, gr.centerX(), gr.bottom - 20 * dp, text)
    }

    private companion object {
        const val NEAR = 0.35
        const val WALL_H = 2.6
        const val START_WALL = 11.0
        const val END_WALL = 3.4
        const val MID_WALL = 6.8     // 1차 후진에서 멈추는 곳
        const val PULL_FORWARD = 0.9 // 고쳐 대며 앞으로 나가는 거리
        const val AISLE_SPEED = 3.5 // m/s, 통로 서행
    }
}
