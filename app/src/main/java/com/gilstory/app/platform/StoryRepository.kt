package com.gilstory.app.platform

import android.content.Context
import com.gilstory.app.core.Genre
import com.gilstory.app.core.Interaction
import com.gilstory.app.core.InteractionType
import com.gilstory.app.core.Story
import org.json.JSONArray
import org.json.JSONObject

import com.gilstory.app.core.RestStopPoint

object StoryRepository {

    fun loadStories(context: Context): List<Story> {
        val json = context.assets.open("stories.json").bufferedReader().use { it.readText() }
        val array = JSONArray(json)
        return (0 until array.length()).map { parseStory(array.getJSONObject(it)) }
    }

    fun loadRestStops(context: Context): List<RestStopPoint> {
        val json = context.assets.open("rest_stops.json").bufferedReader().use { it.readText() }
        val array = JSONObject(json).getJSONArray("stops")
        return (0 until array.length()).map {
            val o = array.getJSONObject(it)
            RestStopPoint(
                name = o.getString("name"),
                kind = o.getString("kind"),
                direction = o.getString("direction"),
                latitude = o.optDoubleOrNull("lat"),
                longitude = o.optDoubleOrNull("lon"),
            )
        }
    }

    private fun parseStory(o: JSONObject): Story {
        val interaction = o.optJSONObject("interaction")?.let { i ->
            Interaction(
                type = InteractionType.valueOf(i.getString("type")),
                question = i.getString("question"),
                options = i.getJSONArray("options").toStrings(),
                responses = i.getJSONArray("responses").toStrings(),
                answer = if (i.has("answer")) i.getInt("answer") else null,
            )
        }
        // 긴 이야기는 문단(parts)으로 나눠 두고, 읽을 때는 이어서 읽습니다
        val text = o.optJSONArray("parts")?.toStrings()?.joinToString(" ") ?: o.getString("text")
        return Story(
            id = o.getString("id"),
            genre = Genre.valueOf(o.getString("genre")),
            region = if (o.isNull("region")) null else o.optString("region").ifBlank { null },
            title = o.getString("title"),
            text = text,
            interaction = interaction,
            ending = o.optJSONArray("parts")?.toStrings()?.lastOrNull() ?: text,
            fiction = o.optBoolean("fiction", false),
        )
    }

    private fun JSONArray.toStrings() = (0 until length()).map { getString(it) }

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (!has(key) || isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }
}
