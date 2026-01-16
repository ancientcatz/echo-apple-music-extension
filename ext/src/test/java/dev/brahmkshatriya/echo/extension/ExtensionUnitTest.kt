package dev.brahmkshatriya.echo.extension

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ExtensionUnitTest {
    private val extension = AppleMusicExtension()
    private val mainThreadSurrogate = newSingleThreadContext("UI thread")

    @Before
    fun setUp() {
        Dispatchers.setMain(mainThreadSurrogate)
        extension.setSettings(MockedSettings())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        mainThreadSurrogate.close()
    }

    @Test
    fun paxParser_handlesWrappedResponse() = runBlocking {
        val sample = """
        {"type":"Line","content":[{"text":[{"text":"Hello","part":false,"timestamp":0,"endtime":500}],"timestamp":0}]}
        """.trimIndent()

        val lrc = parsePaxLyricsToLrc(sample, multiPersonWordByWord = true)
        println(lrc)

        assertTrue(lrc != null && lrc.contains("[00:00.000]"))
    }
}
