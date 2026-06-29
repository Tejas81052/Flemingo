package me.thimmaiah.ebors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTranslatorTest {
    @Test
    fun catalogueContainsBroadAndIndianLanguageCoverage() {
        assertTrue(PageTranslator.languages.size >= 180)
        assertNotNull(PageTranslator.find("hi"))
        assertNotNull(PageTranslator.find("kn"))
        assertNotNull(PageTranslator.find("ta"))
        assertNotNull(PageTranslator.find("mni-Mtei"))
        assertNotNull(PageTranslator.find("zh-TW"))
    }

    @Test
    fun preferredUsesPersistedThenDeviceThenEnglish() {
        assertEquals("fr", PageTranslator.preferred("de-DE", "fr").code)
        assertEquals("de", PageTranslator.preferred("de-DE", null).code)
        assertEquals("en", PageTranslator.preferred("xx-ZZ", null).code)
    }

    @Test
    fun proxyUrlValidatesAndEncodesSource() {
        val result = PageTranslator.buildProxyUrl(
            "https://example.com/story?q=hello world",
            "kn",
        )
        assertNotNull(result)
        assertTrue(result!!.contains("tl=kn"))
        assertTrue(result.contains("u=https%3A%2F%2Fexample.com"))
        assertNull(PageTranslator.buildProxyUrl("file:///tmp/story", "en"))
        assertNull(PageTranslator.buildProxyUrl("https://example.com", "not-a-language"))
    }

    @Test
    fun translatedUrlDetectionAndSourceRecoveryAreStable() {
        val proxy = PageTranslator.buildProxyUrl("https://example.com/a?b=1", "es")!!
        assertTrue(PageTranslator.isTranslationUrl(proxy))
        assertTrue(PageTranslator.isTranslationUrl("https://example-com.translate.goog/a"))
        assertEquals("https://example.com/a?b=1", PageTranslator.extractSourceUrl(proxy))
    }
}
