package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.LyricsClient
import dev.brahmkshatriya.echo.common.clients.LyricsSearchClient
import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.Settings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

class AppleMusicExtension : ExtensionClient, LyricsClient, LyricsSearchClient {
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val tokenManager = AppleTokenManager(client)

    private lateinit var setting: Settings

    override suspend fun onExtensionSelected() {}

    override suspend fun getSettingItems(): List<Setting> = emptyList()

    override fun setSettings(settings: Settings) {
        setting = settings
    }

    override suspend fun searchTrackLyrics(clientId: String, track: Track): Feed<Lyrics> {
        val query = (track.title + " " + (track.artists.firstOrNull()?.name ?: "")).trim()
        return searchLyrics(query)
    }

    override suspend fun searchLyrics(query: String): Feed<Lyrics> {
        if (query.isBlank()) return emptyList<Lyrics>().toFeed()

        val token = tokenManager.getToken()
        val encoded = URLEncoder.encode(query, "UTF-8")

        val req = Request.Builder()
            .url(
                "https://amp-api.music.apple.com/v1/catalog/us/search?" +
                    "term=$encoded&types=songs&limit=25&l=en-US&platform=web&format[resources]=map"
            )
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Origin", "https://music.apple.com")
            .addHeader("Referer", "https://music.apple.com/")
            .addHeader("User-Agent", "Mozilla/5.0")
            .build()

        val resp = client.newCall(req).await()
        if (!resp.isSuccessful) return emptyList<Lyrics>().toFeed()

        val body = resp.body.string()
        val root = json.parseToJsonElement(body).jsonObject

        val results = root["results"]?.jsonObject ?: return emptyList<Lyrics>().toFeed()
        val songsBlock = results["songs"]?.jsonObject ?: return emptyList<Lyrics>().toFeed()
        val data = songsBlock["data"]?.jsonArray ?: return emptyList<Lyrics>().toFeed()

        val resources = root["resources"]?.jsonObject
        val songsRes = resources?.get("songs")?.jsonObject

        val list = data.mapNotNull { el ->
            val id = el.jsonObject["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val detail = songsRes?.get(id)?.jsonObject ?: return@mapNotNull null
            val attrs = detail["attributes"]?.jsonObject ?: return@mapNotNull null

            val name = attrs["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val artist = attrs["artistName"]?.jsonPrimitive?.content ?: ""

            Lyrics(
                id = id,
                title = name,
                subtitle = artist
            )
        }

        return list.toFeed()
    }

    override suspend fun loadLyrics(lyrics: Lyrics): Lyrics {
        val id = lyrics.id.toLongOrNull() ?: return lyrics

        val req = Request.Builder()
            .url("https://lyrics.paxsenix.org/apple-music/lyrics?id=$id")
            .build()

        val resp = client.newCall(req).await()
        if (!resp.isSuccessful) return lyrics

        val body = resp.body.string()
        if (body.isBlank()) return lyrics

        val parsed = getSyncedLyrics(body) ?: return lyrics

        return lyrics.copy(lyrics = parsed)
    }
}
