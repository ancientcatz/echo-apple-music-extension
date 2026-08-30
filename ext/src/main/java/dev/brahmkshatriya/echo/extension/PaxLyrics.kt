package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.common.models.Lyrics
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
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
 * original line's order and timing.
 *
 * When the API provides a transliteration for a line, that text always takes
 * precedence. For lines the API did not transliterate:
 *   - when [forced] is true, lines containing CJK characters are romanized via
 *     the Google Translate romanization endpoint (see [romanizeLine]); lines
 *     without CJK characters fall back to the original text.
 *   - when [forced] is false, the original line text is kept.
 *
 * Returns null only when the response has no `content` at all, so the caller
 * can fall back to [getSyncedLyrics].
 */
suspend fun getRomanizedLyrics(
    apiResponse: String,
    client: OkHttpClient,
    forced: Boolean
): Lyrics.Timed? {
    val response = parsePaxResponse(apiResponse) ?: return null
    val lines = response.content ?: return null
    if (lines.isEmpty()) return null

    val translit = response.metadata?.transliterations?.firstOrNull()
    val byKey = translit?.lines
        ?.filter { it.key != null }
        ?.associateBy { it.key!! }
        .orEmpty()

    val items = mutableListOf<Lyrics.Item>()
    for (i in lines.indices) {
        val line = lines[i]

        var start = line.timestamp.toLong()
        if (i == 0) start = max(0L, start)
        val end = if (i < lines.lastIndex)
            lines[i + 1].timestamp.toLong()
        else
            line.endtime?.toLong() ?: (start + 2000L)

        val original = buildString {
            for (seg in line.text) {
                append(seg.text)
                if (!seg.part) append(" ")
            }
        }.trim()

        // API-provided transliteration takes precedence; the Google fallback
        // only runs when forced romanization is enabled.
        val apiText = line.key?.let { byKey[it]?.text }?.takeIf { it.isNotBlank() }
        val text = apiText
            ?: (if (forced) romanizeLine(client, original) else null)
            ?: original

        items.add(Lyrics.Item(text, start, end))
    }

    return Lyrics.Timed(items)
}

/**
 * Returns true when [text] contains Han, Hiragana, Katakana, or Hangul
 * characters. Latin characters in the same line do not suppress this; a
 * single CJK character is enough to trigger romanization.
 */
private fun needsRomanization(text: String): Boolean {
    for (ch in text) {
        val c = ch.code
        // Hiragana / Katakana, CJK Unified (incl. Ext A), Hangul (Jamo,
        // Syllables, Compatibility Jamo).
        if (c in 0x3040..0x30FF ||
            c in 0x3400..0x4DBF ||
            c in 0x4E00..0x9FFF ||
            c in 0xAC00..0xD7AF ||
            c in 0x1100..0x11FF ||
            c in 0x3130..0x318F
        ) return true
    }
    return false
}

/**
 * Romanizes a single line via the Google Translate romanization endpoint
 * (`translate_a/single?...&dt=rm`).
 *
 * Returns null when the line has no CJK characters, when the request fails,
 * or when the response does not contain a usable romanization; the caller
 * then keeps the original line. OkHttpClient defaults to 10s connect/read/
 * write timeouts, matching the reference implementation's 10s timeout.
 */
private suspend fun romanizeLine(client: OkHttpClient, text: String): String? {
    if (text.isBlank() || !needsRomanization(text)) return null

    return try {
        val encoded = URLEncoder.encode(text, "UTF-8")
        val req = Request.Builder()
            .url(
                "https://translate.googleapis.com/translate_a/single?" +
                    "client=gtx&sl=auto&tl=en&dt=rm&q=$encoded"
            )
            .build()
        val resp = client.newCall(req).await()
        if (!resp.isSuccessful) return null
        parseGoogleRomanization(resp.body.string())
    } catch (_: Exception) {
        null
    }
}

/**
 * Parses the Google Translate romanization response.
 *
 * The `dt=rm` payload is a nested array; the romanized text lives at
 * `root[0][0][3]`. Any structural mismatch returns null so the caller
 * keeps the original line.
 */
private fun parseGoogleRomanization(body: String): String? {
    val root = try {
        paxJson.parseToJsonElement(body)
    } catch (_: Exception) {
        return null
    }
    if (root !is JsonArray) return null

    val level1 = root.getOrNull(0)
    if (level1 !is JsonArray) return null

    val level2 = level1.getOrNull(0)
    if (level2 !is JsonArray) return null

    val romanized = runCatching {
        level2.getOrNull(3)?.jsonPrimitive?.contentOrNull
    }.getOrNull()
    return romanized?.takeIf { it.isNotBlank() }
}
