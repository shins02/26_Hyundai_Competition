package com.gilstory.app.ui

import android.content.Context
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.gilstory.app.platform.BaseMap
import com.gilstory.app.platform.GeoPoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 이야기 위에 크게 띄우는 경로 지도.
 *
 * Pleos 공개 SDK에는 내비 지도를 앱 안에 넣는 기능이 없어서, 공개 자료(Natural Earth 시·도 경계·도로)로 직접 그립니다.
 * - 주행 화면(NAV): 차를 아래쪽 가운데에 두고 진행 방향을 위로 돌려 비스듬히 기울여 보여 줍니다.
 *   위쪽에는 다음 안내(쉼터·회전), 아래쪽에는 남은 거리와 도착 예정 시각을 띄웁니다.
 * - 전체 경로(OVERVIEW): 경로 전체가 한눈에 들어오게 맞춥니다. 지도를 누르면 두 화면을 바꿉니다.
 * - 시연 주행: 영동고속도로 경로(대략 위치)를 따라 차가 움직입니다.
 * - 실제 주행: NaviHelper가 주는 현재 위치로 지나온 길을 쌓고, 경유지·목적지까지는 직선 점선으로 잇습니다
 *   (경로 모양 데이터는 SDK가 주지 않습니다).
 */
class RouteMapView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    enum class RestState { NONE, OFFERED, ADDED }
    enum class Mode { NAV, OVERVIEW }

    data class Pin(val name: String, val point: GeoPoint)

    /** 위쪽 안내 띠: 아이콘, 이름, 거리 글자 */
    data class Banner(val icon: String, val title: String, val distance: String)

    var baseMap: BaseMap? = null
        set(value) { field = value; invalidate() }
    var roads: List<Pair<Boolean, List<GeoPoint>>> = emptyList() // (고속도로 여부, 선)
        set(value) { field = value; invalidate() }

    var mode = Mode.OVERVIEW
        set(value) { field = value; invalidate() }

    private var plannedPath: List<GeoPoint> = emptyList()
    private var passedOnPath = 0.0 // plannedPath 위에서 지나온 위치 (점 번호 + 비율)
    private val trail = ArrayList<GeoPoint>()
    private var car: GeoPoint? = null
    private var heading: Double? = null
    private var smoothHeading: Double? = null
    private var destination: GeoPoint? = null
    private var waypoints: List<GeoPoint> = emptyList()
    private var pins: List<Pin> = emptyList()
    private var highlight: String? = null
    private var restState = RestState.NONE

    var title: String = ""
        set(value) { field = value; invalidate() }
    var caption: String = ""
        set(value) { field = value; invalidate() }
    var banner: Banner? = null
        set(value) { field = value; invalidate() }
    /** 아래 띠: 왼쪽 큰 글자(남은 거리), 오른쪽(도착 예정) */
    var bottomLeft: String = ""
        set(value) { field = value; invalidate() }
    var bottomRight: String = ""
        set(value) { field = value; invalidate() }
    var roadName: String = ""
        set(value) { field = value; invalidate() }

    private val dp = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity

    // ---- 색 ----
    private val water = paint(0xFF101822.toInt())
    private val otherLand = paint(0xFF1A212B.toInt())
    private val land = paint(0xFF222B37.toInt())
    private val border = stroke(0xFF3A4556.toInt(), 1f)
    private val majorCasing = stroke(0xFF1A202A.toInt(), 11f)
    private val majorRoad = stroke(0xFF59667A.toInt(), 7f)
    private val minorRoad = stroke(0xFF3C4757.toInt(), 4f)
    private val grid = stroke(0xFF2A3442.toInt(), 1.5f)
    private val routeCasing = stroke(0xFF173E91.toInt(), 15f)
    private val remaining = stroke(0xFF4C8DFF.toInt(), 10f)
    private val passed = stroke(0xFF7A8496.toInt(), 10f)
    private val overview = stroke(0xFF4C8DFF.toInt(), 5f).apply { pathEffect = DashPathEffect(floatArrayOf(14 * dp, 10 * dp), 0f) }
    private val toRest = stroke(0xFF2E9E6B.toInt(), 6f).apply { pathEffect = DashPathEffect(floatArrayOf(12 * dp, 8 * dp), 0f) }
    private val pinFill = paint(0xFF262D39.toInt())
    private val pinStroke = stroke(0xFF9AA3B2.toInt(), 2f)
    private val white = stroke(0xFFFFFFFF.toInt(), 3f)
    private val carFill = paint(0xFF4C8DFF.toInt())
    private val carHalo = paint(0x334C8DFF)
    private val destFill = paint(0xFFD9443B.toInt())
    private val panel = paint(0xE6151B24.toInt())
    private val bannerFill = paint(0xFF1F6F4E.toInt())
    private val chipFill = paint(0xCC262D39.toInt())

    private val pinText = text(0xFFF2F4F7.toInt(), 12f, bold = true, center = true)
    private val label = text(0xFFF2F4F7.toInt(), 16f, bold = true, center = true)
    private val provinceText = text(0xFF5C6778.toInt(), 18f, bold = true, center = true)
    private val titleText = text(0xFFF2F4F7.toInt(), 20f, bold = true)
    private val captionText = text(0xFF9AA3B2.toInt(), 17f)
    private val bannerIcon = text(0xFFFFFFFF.toInt(), 40f, bold = true, center = true)
    private val bannerTitle = text(0xFFFFFFFF.toInt(), 24f, bold = true)
    private val bannerDistance = text(0xFFFFFFFF.toInt(), 34f, bold = true)
    private val bottomBig = text(0xFFF2F4F7.toInt(), 26f, bold = true)
    private val bottomSmall = text(0xFF9AA3B2.toInt(), 20f)
    private val roadText = text(0xFFF2F4F7.toInt(), 18f, bold = true, center = true)
    private val chipText = text(0xFFF2F4F7.toInt(), 16f, center = true)

    // ---- 바깥에서 넣는 값 ----

    /** 시연 주행: 미리 정한 경로를 그립니다. */
    fun showPlannedRoute(path: List<GeoPoint>) {
        plannedPath = path
        passedOnPath = 0.0
        trail.clear()
        destination = path.lastOrNull()
        waypoints = emptyList()
        smoothHeading = null
        invalidate()
    }

    /** 시연 주행: 경로 위 위치(점 번호 + 비율)로 차를 옮깁니다. */
    fun moveAlongPlanned(position: Double) {
        if (plannedPath.size < 2) return
        passedOnPath = position.coerceIn(0.0, (plannedPath.size - 1).toDouble())
        val i = passedOnPath.toInt().coerceAtMost(plannedPath.size - 2)
        val f = passedOnPath - i
        val a = plannedPath[i]
        val b = plannedPath[i + 1]
        car = GeoPoint(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
        // 꺾이는 곳에서 화면이 휙 돌지 않도록, 조금 앞을 보고 방향을 정합니다
        heading = bearing(car!!, pointAhead(passedOnPath, 1.2))
        invalidate()
    }

    /** 시연 주행: 남은 거리(km). */
    fun remainingPlannedKm(): Double {
        if (plannedPath.size < 2) return 0.0
        val i = passedOnPath.toInt().coerceAtMost(plannedPath.size - 2)
        var km = car?.let { km(it, plannedPath[i + 1]) } ?: 0.0
        for (k in i + 1 until plannedPath.size - 1) km += km(plannedPath[k], plannedPath[k + 1])
        return km
    }

    /** 시연 주행: 지금 차 위치 (경로 위 점 번호 + 비율) */
    val plannedPosition get() = passedOnPath

    /** 시연 주행: 경로 앞쪽(2km 안쪽에 붙은)에서 가장 가까운 쉼터와, 경로를 따라 남은 거리(km). */
    fun nextPinAhead(): Pair<Pin, Double>? {
        if (plannedPath.size < 2) return null
        return pins.mapNotNull { pin ->
            val (pos, offKm) = projectOnPath(pin.point)
            if (offKm > 2.0 || pos <= passedOnPath + 0.02) null else pin to alongKm(passedOnPath, pos)
        }.minByOrNull { it.second }
    }

    /** 시연 주행: 쉼터가 경로 위 어디쯤인지 (점 번호 + 비율) */
    fun pathPositionOf(name: String): Double? = pins.firstOrNull { it.name == name }?.let { projectOnPath(it.point).first }

    private fun projectOnPath(p: GeoPoint): Pair<Double, Double> {
        var best = 0.0
        var bestKm = Double.MAX_VALUE
        for (i in 0 until plannedPath.size - 1) {
            val a = plannedPath[i]
            val b = plannedPath[i + 1]
            val k = cos(Math.toRadians(a.lat))
            val ax = (p.lon - a.lon) * k
            val ay = p.lat - a.lat
            val bx = (b.lon - a.lon) * k
            val by = b.lat - a.lat
            val len = bx * bx + by * by
            val t = if (len == 0.0) 0.0 else ((ax * bx + ay * by) / len).coerceIn(0.0, 1.0)
            val d = hypot(ax - t * bx, ay - t * by) * 111.0
            if (d < bestKm) { bestKm = d; best = i + t }
        }
        return best to bestKm
    }

    private fun alongKm(from: Double, to: Double): Double {
        fun at(pos: Double): GeoPoint {
            val i = pos.toInt().coerceAtMost(plannedPath.size - 2)
            val f = pos - i
            val a = plannedPath[i]
            val b = plannedPath[i + 1]
            return GeoPoint(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
        }
        var km = 0.0
        var cur = from
        while (cur < to) {
            val next = minOf(kotlin.math.floor(cur) + 1, to)
            km += km(at(cur), at(next))
            cur = next
        }
        return km
    }

    /** 실제 주행: 내비의 현재 위치. 지나온 길로 쌓습니다. */
    fun updatePosition(point: GeoPoint, headingDeg: Double?) {
        if (plannedPath.isNotEmpty()) { plannedPath = emptyList(); destination = null; smoothHeading = null }
        val last = trail.lastOrNull()
        if (last == null || last.lat != point.lat || last.lon != point.lon) {
            trail.add(point)
            if (trail.size > MAX_TRAIL) trail.removeAt(0)
        }
        car = point
        heading = headingDeg
        invalidate()
    }

    fun updateRoute(dest: GeoPoint?, via: List<GeoPoint>) {
        if (plannedPath.isNotEmpty()) return
        destination = dest
        waypoints = via
        invalidate()
    }

    val hasRoute get() = plannedPath.isNotEmpty() || destination != null

    fun setRestPins(list: List<Pin>) { pins = list; invalidate() }

    fun setRestHighlight(name: String?, state: RestState) {
        highlight = name
        restState = if (name == null) RestState.NONE else state
        invalidate()
    }

    // ---- 그리기 ----

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val clip = Path().apply { addRoundRect(RectF(0f, 0f, w, h), 22 * dp, 22 * dp, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawRect(0f, 0f, w, h, water)

        val nav = mode == Mode.NAV && car != null
        val proj = if (nav) navProjection(w, h) else overviewProjection(w, h)

        // 지도 층 (주행 화면에서는 기울임)
        canvas.save()
        proj.tilt?.let { canvas.concat(it) }
        drawWorld(canvas, proj, w, h, nav)
        canvas.restore()

        // 세워서 그리는 표시 (쉼터, 목적지, 차)
        drawMarkers(canvas, proj)

        if (nav) drawNavOverlay(canvas, w, h) else drawOverviewOverlay(canvas, w, h)
        canvas.restore()
    }

    private fun drawWorld(canvas: Canvas, proj: Projection, w: Float, h: Float, nav: Boolean) {
        // 기울이면 화면 위쪽이 넓게 보이므로 넉넉히 덮습니다
        canvas.drawRect(-w, -h * 2, w * 2, h * 2, water)
        baseMap?.let { map ->
            map.others.forEach { canvas.drawPath(proj.rings(it), otherLand) }
            map.provinces.forEach {
                val p = proj.rings(it)
                canvas.drawPath(p, land)
                canvas.drawPath(p, border)
            }
            if (!nav) map.labels.forEach { (name, at) ->
                val (x, y) = proj.plane(at)
                if (x in 40f..(w - 40f) && y in 60f..(h - 50f)) canvas.drawText(name, x, y, provinceText)
            }
        }
        // 주행 화면: 옅은 격자로 움직임과 기울기를 보여 줍니다 (약 1km 간격)
        if (nav) car?.let { c ->
            val step = 0.01
            val n = 14
            val baseLat = Math.floor(c.lat / step) * step
            val baseLon = Math.floor(c.lon / step) * step
            for (i in -n..n) {
                val lat = baseLat + i * step
                canvas.drawPath(proj.line(listOf(GeoPoint(lat, baseLon - n * step), GeoPoint(lat, baseLon + n * step))), grid)
                val lon = baseLon + i * step
                canvas.drawPath(proj.line(listOf(GeoPoint(baseLat - n * step, lon), GeoPoint(baseLat + n * step, lon))), grid)
            }
        }
        val wide = if (nav) 1f else 0.6f
        roads.filter { !it.first }.forEach { canvas.drawPath(proj.line(it.second), minorRoad.scaled(wide)) }
        roads.filter { it.first }.forEach {
            val p = proj.line(it.second)
            canvas.drawPath(p, majorCasing.scaled(wide))
            canvas.drawPath(p, majorRoad.scaled(wide))
        }

        if (plannedPath.size >= 2) {
            val cut = passedOnPath.toInt()
            val done = plannedPath.take(cut + 1) + listOfNotNull(car)
            val rest = listOfNotNull(car) + plannedPath.drop(cut + 1)
            canvas.drawPath(proj.line(done), passed.scaled(wide))
            val r = proj.line(rest)
            canvas.drawPath(r, routeCasing.scaled(wide))
            canvas.drawPath(r, remaining.scaled(wide))
        } else {
            if (trail.size >= 2) canvas.drawPath(proj.line(trail), passed.scaled(wide))
            val ahead = listOfNotNull(car) + waypoints + listOfNotNull(destination)
            if (car != null && ahead.size >= 2) canvas.drawPath(proj.line(ahead), overview)
        }
        val target = pins.firstOrNull { it.name == highlight }
        if (target != null && restState == RestState.ADDED) car?.let { c -> canvas.drawPath(proj.line(listOf(c, target.point)), toRest) }
    }

    private fun drawMarkers(canvas: Canvas, proj: Projection) {
        val target = pins.firstOrNull { it.name == highlight }
        pins.forEach { if (it.name != highlight) drawPin(canvas, proj, it, null) }
        target?.let { drawPin(canvas, proj, it, if (restState == RestState.ADDED) 0xFF2E9E6B.toInt() else 0xFFE08A1E.toInt()) }
        destination?.let {
            val (x, y) = proj.screen(it)
            if (onScreen(x, y)) {
                canvas.drawCircle(x, y, 11 * dp, destFill)
                canvas.drawCircle(x, y, 11 * dp, white)
            }
        }
        car?.let { drawCar(canvas, proj, it) }
    }

    private fun drawPin(canvas: Canvas, proj: Projection, pin: Pin, color: Int?) {
        val (x, y) = proj.screen(pin.point)
        if (!onScreen(x, y)) return
        if (color != null) {
            val size = 16 * dp
            canvas.drawCircle(x, y, size, paint(color))
            canvas.drawCircle(x, y, size, white)
            pinText.textSize = 17 * sp
            canvas.drawText("P", x, y + 6 * dp, pinText)
            canvas.drawText(pin.name, x, y - size - 10 * dp, label)
        } else {
            val size = 11 * dp
            canvas.drawCircle(x, y, size, pinFill)
            canvas.drawCircle(x, y, size, pinStroke)
            pinText.textSize = 12 * sp
            canvas.drawText("P", x, y + 4 * dp, pinText)
        }
    }

    private fun drawCar(canvas: Canvas, proj: Projection, at: GeoPoint) {
        val (x, y) = proj.screen(at)
        canvas.save()
        canvas.translate(x, y)
        // 주행 화면에서는 지도가 돌아가므로 차는 늘 위를 봅니다
        if (proj.tilt == null) heading?.let { canvas.rotate(it.toFloat()) }
        val s = if (proj.tilt != null) 24 * dp else 16 * dp
        canvas.drawCircle(0f, 0f, s * 1.8f, carHalo)
        val arrow = Path().apply {
            moveTo(0f, -s)
            lineTo(s * 0.8f, s * 0.85f)
            lineTo(0f, s * 0.4f)
            lineTo(-s * 0.8f, s * 0.85f)
            close()
        }
        canvas.drawPath(arrow, carFill)
        canvas.drawPath(arrow, white)
        canvas.restore()
    }

    private fun drawNavOverlay(canvas: Canvas, w: Float, h: Float) {
        // 위: 다음 안내
        banner?.let { b ->
            val r = RectF(20 * dp, 20 * dp, min(w - 20 * dp, 20 * dp + 560 * dp), 20 * dp + 104 * dp)
            canvas.drawRoundRect(r, 18 * dp, 18 * dp, bannerFill)
            canvas.drawText(b.icon, r.left + 56 * dp, r.centerY() + 15 * dp, bannerIcon)
            canvas.drawText(b.distance, r.left + 112 * dp, r.top + 48 * dp, bannerDistance)
            canvas.drawText(ellipsize(b.title, bannerTitle, r.right - r.left - 130 * dp), r.left + 112 * dp, r.top + 86 * dp, bannerTitle)
        }
        // 오른쪽 위: 화면 바꾸기 안내
        val chip = RectF(w - 170 * dp, 24 * dp, w - 20 * dp, 64 * dp)
        canvas.drawRoundRect(chip, 20 * dp, 20 * dp, chipFill)
        canvas.drawText("🗺 전체 경로", chip.centerX(), chip.centerY() + 6 * dp, chipText)
        // 차 아래: 지금 달리는 길
        if (roadName.isNotEmpty()) {
            val tw = roadText.measureText(roadName) + 32 * dp
            val cy = h * CAR_Y + 54 * dp
            val rr = RectF(w / 2 - tw / 2, cy - 20 * dp, w / 2 + tw / 2, cy + 20 * dp)
            canvas.drawRoundRect(rr, 20 * dp, 20 * dp, chipFill)
            canvas.drawText(roadName, w / 2, cy + 7 * dp, roadText)
        }
        // 아래: 남은 거리 · 도착 예정
        if (bottomLeft.isNotEmpty() || bottomRight.isNotEmpty()) {
            val r = RectF(20 * dp, h - 84 * dp, w - 20 * dp, h - 20 * dp)
            canvas.drawRoundRect(r, 18 * dp, 18 * dp, panel)
            canvas.drawText(bottomLeft, r.left + 24 * dp, r.centerY() + 10 * dp, bottomBig)
            val tw = bottomSmall.measureText(bottomRight)
            canvas.drawText(bottomRight, r.right - 24 * dp - tw, r.centerY() + 8 * dp, bottomSmall)
        }
    }

    private fun drawOverviewOverlay(canvas: Canvas, w: Float, h: Float) {
        if (title.isNotEmpty()) canvas.drawText(title, 20 * dp, 36 * dp, titleText)
        if (caption.isNotEmpty()) canvas.drawText(caption, 20 * dp, h - 22 * dp, captionText)
        if (car != null && hasRoute) {
            val chip = RectF(w - 170 * dp, 18 * dp, w - 20 * dp, 58 * dp)
            canvas.drawRoundRect(chip, 20 * dp, 20 * dp, chipFill)
            canvas.drawText("🧭 주행 화면", chip.centerX(), chip.centerY() + 6 * dp, chipText)
        }
    }

    // ---- 화면 맞추기 ----

    private inner class Projection(
        private val cLat: Double,
        private val cLon: Double,
        private val scale: Double,      // 위도 1도가 몇 픽셀인지
        private val rotateDeg: Double,  // 지도를 돌리는 각 (진행 방향을 위로)
        private val ox: Float,
        private val oy: Float,
        val tilt: Matrix?,
    ) {
        private val k = cos(Math.toRadians(cLat))
        private val rad = Math.toRadians(rotateDeg)
        private val pt = FloatArray(2)

        /** 기울이기 전 평면 좌표 */
        fun plane(p: GeoPoint): Pair<Float, Float> {
            val x = (p.lon - cLon) * k * scale
            val y = -(p.lat - cLat) * scale
            val rx = x * cos(rad) - y * sin(rad)
            val ry = x * sin(rad) + y * cos(rad)
            return (ox + rx).toFloat() to (oy + ry).toFloat()
        }

        /** 화면 좌표 (기울임 반영) */
        fun screen(p: GeoPoint): Pair<Float, Float> {
            val (x, y) = plane(p)
            val m = tilt ?: return x to y
            pt[0] = x; pt[1] = y
            m.mapPoints(pt)
            return pt[0] to pt[1]
        }

        fun line(points: List<GeoPoint>) = Path().apply {
            points.forEachIndexed { i, p ->
                val (x, y) = plane(p)
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }

        fun rings(shape: List<List<GeoPoint>>) = Path().apply {
            shape.forEach { ring ->
                ring.forEachIndexed { i, p ->
                    val (x, y) = plane(p)
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }
        }
    }

    private fun navProjection(w: Float, h: Float): Projection {
        val at = car!!
        val target = heading ?: 0.0
        val prev = smoothHeading
        smoothHeading = if (prev == null) target else prev + angleDiff(prev, target) * 0.35
        if (prev != null && kotlin.math.abs(angleDiff(prev, target)) > 0.5) postInvalidateDelayed(60)
        val scale = h / NAV_SPAN_DEG
        val pivotX = w / 2
        val pivotY = h * CAR_Y
        val m = Matrix()
        val cam = Camera()
        cam.setLocation(0f, 0f, -18f)
        cam.rotateX(NAV_TILT_DEG)
        cam.getMatrix(m)
        m.preTranslate(-pivotX, -pivotY)
        m.postTranslate(pivotX, pivotY)
        return Projection(at.lat, at.lon, scale, -(smoothHeading ?: 0.0), pivotX, pivotY, m)
    }

    private fun overviewProjection(w: Float, h: Float): Projection {
        val points = when {
            plannedPath.isNotEmpty() -> plannedPath
            destination != null || waypoints.isNotEmpty() -> trail.takeLast(200) + listOfNotNull(car) + waypoints + listOfNotNull(destination)
            car != null -> listOf(car!!)
            else -> emptyList()
        }
        val (minLat, maxLat, minLon, maxLon) = if (points.isEmpty()) {
            listOf(33.0, 38.7, 124.5, 130.0) // 우리나라 전체
        } else {
            listOf(points.minOf { it.lat }, points.maxOf { it.lat }, points.minOf { it.lon }, points.maxOf { it.lon })
        }
        val cLat = (minLat + maxLat) / 2
        val cLon = (minLon + maxLon) / 2
        val k = cos(Math.toRadians(cLat))
        val spanLat = max((maxLat - minLat) * 1.2, MIN_SPAN_DEG)
        val spanLon = max((maxLon - minLon) * 1.2, MIN_SPAN_DEG)
        val scale = min(w / (spanLon * k), (h - 100 * dp) / spanLat)
        return Projection(cLat, cLon, scale, 0.0, w / 2, h / 2, null)
    }

    // ---- 도움 함수 ----

    private fun pointAhead(position: Double, steps: Double): GeoPoint {
        val p = (position + steps).coerceAtMost((plannedPath.size - 1).toDouble())
        val i = p.toInt().coerceAtMost(plannedPath.size - 2)
        val f = p - i
        val a = plannedPath[i]
        val b = plannedPath[i + 1]
        return GeoPoint(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
    }

    private fun onScreen(x: Float, y: Float) = x > -30 && y > -30 && x < width + 30 && y < height + 30

    private fun angleDiff(from: Double, to: Double): Double {
        var d = (to - from) % 360
        if (d > 180) d -= 360
        if (d < -180) d += 360
        return d
    }

    private fun bearing(a: GeoPoint, b: GeoPoint): Double {
        val dx = (b.lon - a.lon) * cos(Math.toRadians(a.lat))
        val dy = b.lat - a.lat
        return Math.toDegrees(atan2(dx, dy))
    }

    private fun km(a: GeoPoint, b: GeoPoint): Double {
        val k = cos(Math.toRadians((a.lat + b.lat) / 2))
        return hypot((a.lon - b.lon) * k, a.lat - b.lat) * 111.0
    }

    private fun ellipsize(s: String, p: Paint, maxWidth: Float): String {
        if (p.measureText(s) <= maxWidth) return s
        var t = s
        while (t.isNotEmpty() && p.measureText("$t…") > maxWidth) t = t.dropLast(1)
        return "$t…"
    }

    private fun Paint.scaled(factor: Float): Paint = if (factor == 1f) this else Paint(this).also { it.strokeWidth = strokeWidth * factor }

    private fun paint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }

    private fun stroke(color: Int, widthDp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = widthDp * dp
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private fun text(color: Int, sizeSp: Float, bold: Boolean = false, center: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = sizeSp * sp
        isFakeBoldText = bold
        if (center) textAlign = Paint.Align.CENTER
    }

    private companion object {
        const val MAX_TRAIL = 2000
        const val MIN_SPAN_DEG = 0.6
        const val NAV_SPAN_DEG = 0.09   // 주행 화면 세로 폭 (약 10km)
        const val NAV_TILT_DEG = 45f
        const val CAR_Y = 0.70f         // 차를 화면 위에서 70% 지점에
    }
}
