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
    val content: List<PaxLine>? = null,
    val metadata: PaxMetadata? = null
)

@Serializable
private data class PaxMetadata(
    val transliterations: List<PaxTransliteration> = emptyList()
)

@Serializable
private data class PaxTransliteration(
    val lang: String? = null,
    val lines: List<PaxTransliterationLine> = emptyList()
)

@Serializable
private data class PaxTransliterationLine(
    val key: String? = null,
    val text: String,
    val timestamp: Int,
    val endtime: Int? = null,
    val duration: Int? = null
)

@Serializable
private data class PaxLine(
    val text: List<PaxSyllable>,
    val timestamp: Int,
    val oppositeTurn: Boolean = false,
    val background: Boolean = false,
    val backgroundText: List<PaxSyllable> = emptyList(),
    val key: String? = null,
    val endtime: Int? = null
)

@Serializable
private data class PaxSyllable(
    val text: String,
    val part: Boolean = false,
    val timestamp: Int? = null,
    val endtime: Int? = null
)

/**
 * Parses the [PaxResponse.content] lines from the given API response.
 *
 * Handles both the wrapped `{"type": ..., "content": [...]}` form and a bare
 * `[...]` line-list form, returning null when neither can be decoded. The
 * returned list may be empty.
 */
private fun parsePaxLines(apiResponse: String): List<PaxLine>? {
    return try {
        paxJson.decodeFromString(PaxResponse.serializer(), apiResponse).content
    } catch (_: Exception) {
        try {
            paxJson.decodeFromString(ListSerializer(PaxLine.serializer()), apiResponse)
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Parses the wrapped [PaxResponse] (including [PaxResponse.metadata]) from the
 * given API response. Returns null when the response is not in the wrapped form
 * (e.g. a bare line list), since metadata is only present in the wrapped form.
 */
private fun parsePaxResponse(apiResponse: String): PaxResponse? {
    return try {
        paxJson.decodeFromString(PaxResponse.serializer(), apiResponse)
    } catch (_: Exception) {
        null
    }
}

fun getSyncedLyrics(apiResponse: String): Lyrics.Timed? {
    val lines = parsePaxLines(apiResponse) ?: return null
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

/**
 * Parses transliterated (romanized) line-synchronized lyrics from the API
 * response.
 *
 * The Paxsenix API exposes transliterations under `metadata.transliterations`,
 * where each transliteration line is keyed by the same `key` as the
 * corresponding original line in `content`. Each romanized line keeps the
 * original line's order and timing; lines without a matching transliteration
 * fall back to the original line text.
 *
 * Returns null when no transliteration is available so the caller can fall
 * back to [getSyncedLyrics].
 */
fun getRomanizedLyrics(apiResponse: String): Lyrics.Timed? {
    val response = parsePaxResponse(apiResponse) ?: return null
    val lines = response.content ?: return null
    if (lines.isEmpty()) return null

    val translit = response.metadata?.transliterations?.firstOrNull()
    if (translit == null || translit.lines.isEmpty()) return null

    val byKey = translit.lines.filter { it.key != null }.associateBy { it.key!! }

    val items = mutableListOf<Lyrics.Item>()
    for (i in lines.indices) {
        val line = lines[i]

        var start = line.timestamp.toLong()
        if (i == 0) start = max(0L, start)
        val end = if (i < lines.lastIndex)
            lines[i + 1].timestamp.toLong()
        else
            line.endtime?.toLong() ?: (start + 2000L)

        val text = line.key?.let { byKey[it]?.text }?.takeIf { it.isNotBlank() }
            ?: buildString {
                for (seg in line.text) {
                    append(seg.text)
                    if (!seg.part) append(" ")
                }
            }.trim()

        items.add(Lyrics.Item(text, start, end))
    }

    return Lyrics.Timed(items)
}
