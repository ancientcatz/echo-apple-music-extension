package dev.brahmkshatriya.echo.extension

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.regex.Pattern

class AppleTokenManager(private val client: OkHttpClient) {
    private var cachedToken: String = ""
    private val mutex = Mutex()

    suspend fun clearToken() = mutex.withLock { cachedToken = "" }

    suspend fun getToken(): String = mutex.withLock {
        if (cachedToken.isNotEmpty()) return cachedToken

        val req1 = Request.Builder().url("https://beta.music.apple.com").get().build()
        val body1 = client.newCall(req1).execute().body?.string()
            ?: throw IOException("empty Apple Music homepage")

        val indexRe = Pattern.compile("/assets/index~[^/]+\\.js")
        val m = indexRe.matcher(body1)
        if (!m.find()) throw IOException("could not find index js")
        val indexURI = m.group()

        val req2 = Request.Builder().url("https://beta.music.apple.com$indexURI").get().build()
        val jsBody = client.newCall(req2).execute().body?.string()
            ?: throw IOException("empty index js")

        val tokenRe = Pattern.compile("eyJh[^\"]*")
        val m2 = tokenRe.matcher(jsBody)
        if (!m2.find()) throw IOException("could not find token")

        cachedToken = m2.group()
        cachedToken
    }
}
