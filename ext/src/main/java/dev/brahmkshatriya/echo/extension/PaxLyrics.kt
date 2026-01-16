package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Lyrics
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.math.max

private val paxJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class PaxResponse(
    val type: String,
    val content: List<PaxLine>? = null
)

@Serializable
private data class PaxLine(
    val text: List<PaxSyllable>,
    val timestamp: Int,
    val oppositeTurn: Boolean = false,
    val background: Boolean = false,
    val backgroundText: List<PaxSyllable> = emptyList()
)

@Serializable
private data class PaxSyllable(
    val text: String,
    val part: Boolean = false,
    val timestamp: Int? = null,
    val endtime: Int? = null
)

fun getSyncedLyrics(apiResponse: String): Lyrics.Timed? {
    val lines: List<PaxLine> = try {
        val wrapped = paxJson.decodeFromString(PaxResponse.serializer(), apiResponse)
        wrapped.content ?: return null
    } catch (_: Exception) {
        try {
            paxJson.decodeFromString(ListSerializer(PaxLine.serializer()), apiResponse)
        } catch (_: Exception) {
            return null
        }
    }

    if (lines.isEmpty()) return null

    val items = mutableListOf<Lyrics.Item>()

    for (i in lines.indices) {
        val line = lines[i]

        var start = line.timestamp.toLong()
        if (i == 0) start = max(0L, start)

        val end = if (i < lines.lastIndex)
            lines[i + 1].timestamp.toLong()
        else
            start + 2000L

        val text = buildString {
            for (seg in line.text) {
                append(seg.text)
                if (!seg.part) append(" ")
            }
        }.trim()

        items.add(Lyrics.Item(text, start, end))
    }

    return Lyrics.Timed(items)
}
