package com.gilstory.app.platform

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 위도·경도 한 점. */
data class GeoPoint(val lat: Double, val lon: Double)

/** 시·도 경계(우리나라)와 주변 나라 윤곽. 지도 바탕에만 씁니다. */
data class BaseMap(
    val provinces: List<List<List<GeoPoint>>>,
    val others: List<List<List<GeoPoint>>>,
    /** 시·도 이름과 글자를 놓을 위치(가장 큰 고리의 중심) */
    val labels: List<Pair<String, GeoPoint>> = emptyList(),
)

/** 시연 주행 경로와, 지역(시·군)마다 지도에서 차가 있을 위치. */
data class DemoRoute(val path: List<GeoPoint>, val regionAt: List<Pair<String, GeoPoint>>)

object MapData {

    /** assets/korea_map.json (Natural Earth, 퍼블릭 도메인) */
    fun loadBaseMap(context: Context): BaseMap {
        val o = JSONObject(context.assets.open("korea_map.json").bufferedReader().use { it.readText() })
        fun shapes(array: JSONArray) = (0 until array.length()).map { i -> rings(array.getJSONObject(i).getJSONArray("rings")) }
        val provinces = o.getJSONArray("provinces")
        val labels = (0 until provinces.length()).map { i ->
            val p = provinces.getJSONObject(i)
            val ring = rings(p.getJSONArray("rings")).maxByOrNull { it.size } ?: emptyList()
            p.getString("name") to GeoPoint(ring.map { it.lat }.average(), ring.map { it.lon }.average())
        }
        return BaseMap(shapes(provinces), shapes(o.optJSONArray("others") ?: JSONArray()), labels)
    }

    /** assets/korea_roads.json (Natural Earth 1:10m 도로, 퍼블릭 도메인). (고속도로 여부, 선) */
    fun loadRoads(context: Context): List<Pair<Boolean, List<GeoPoint>>> {
        val lines = JSONObject(context.assets.open("korea_roads.json").bufferedReader().use { it.readText() }).getJSONArray("lines")
        return (0 until lines.length()).map { i ->
            val l = lines.getJSONObject(i)
            (l.getString("k") == "major") to points(l.getJSONArray("p"))
        }
    }

    /** assets/demo_route.json (영동고속도로 강릉 방향, 대략 위치) */
    fun loadDemoRoute(context: Context): DemoRoute {
        val o = JSONObject(context.assets.open("demo_route.json").bufferedReader().use { it.readText() })
        val legs = o.getJSONArray("legs")
        return DemoRoute(
            path = points(o.getJSONArray("path")),
            regionAt = (0 until legs.length()).map { i ->
                val leg = legs.getJSONObject(i)
                leg.getString("region") to point(leg.getJSONArray("at"))
            },
        )
    }

    private fun rings(array: JSONArray) = (0 until array.length()).map { points(array.getJSONArray(it)) }

    private fun points(array: JSONArray) = (0 until array.length()).map { point(array.getJSONArray(it)) }

    // 파일은 [경도, 위도] 순서입니다
    private fun point(pair: JSONArray) = GeoPoint(lat = pair.getDouble(1), lon = pair.getDouble(0))
}
