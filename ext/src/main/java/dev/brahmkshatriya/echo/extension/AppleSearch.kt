package dev.brahmkshatriya.echo.extension

import kotlinx.serialization.json.*
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder

// Must be public because returned from a public function
data class SongInfo(
    val name: String,
    val artist: String,
    val artwork: String,
    val appleId: Long
)

suspend fun searchApple(
    client: okhttp3.OkHttpClient,
    token: String,
    query: String,
    offset: Int
): SongInfo {
    val q = URLEncoder.encode(query, "UTF-8")
    val reqURL = "https://amp-api.music.apple.com/v1/catalog/us/search?term=$q&types=songs&limit=25&l=en-US&platform=web&format[resources]=map"

    val req = Request.Builder()
        .url(reqURL)
        .addHeader("Authorization", "Bearer $token")
        .addHeader("Origin", "https://music.apple.com")
        .addHeader("Referer", "https://music.apple.com/")
        .addHeader("User-Agent", "Mozilla/5.0")
        .build()

    val resp = client.newCall(req).execute()
    if (resp.code == 401) throw IOException("unauthorized")
    if (!resp.isSuccessful) throw IOException("Apple search status ${resp.code}")

    val text = resp.body?.string() ?: throw IOException("empty search body")
    val root = Json.parseToJsonElement(text).jsonObject

    val results = root["results"]!!.jsonObject
    val songsArr = results["songs"]!!.jsonObject["data"]!!.jsonArray
    if (offset >= songsArr.size) throw IOException("offset out of range")

    val songId = songsArr[offset].jsonObject["id"]!!.jsonPrimitive.content

    val resources = root["resources"]!!.jsonObject
    val songsMap = resources["songs"]!!.jsonObject

    val detail = songsMap[songId]?.jsonObject
        ?: throw IOException("song id $songId not found in resources.songs")

    val attrs = detail["attributes"]!!.jsonObject

    val name = attrs["name"]!!.jsonPrimitive.content
    val artist = attrs["artistName"]!!.jsonPrimitive.content

    var art = attrs["artwork"]!!.jsonObject["url"]!!.jsonPrimitive.content
    art = art.replace("{w}", "100").replace("{h}", "100").replace("{f}", "png")

    val url = attrs["url"]!!.jsonPrimitive.content
    val idStr = url.substringAfterLast("/")
    val appleId = idStr.toLongOrNull()
        ?: detail["id"]!!.jsonPrimitive.content.toLong()

    return SongInfo(name, artist, art, appleId)
}
