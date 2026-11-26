package app.lody.android.home

import app.lody.android.auth.legalPathPrefix
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class LocalePresentationTest {
    @Test
    fun displayLanguageDistinguishesSimplifiedAndTraditionalChinese() {
        assertEquals("简体中文", formatDisplayLanguage(Locale.forLanguageTag("zh-Hans")))
        assertEquals("简体中文", formatDisplayLanguage(Locale.forLanguageTag("zh-CN")))
        assertEquals("简体中文", formatDisplayLanguage(Locale.forLanguageTag("zh-SG")))
        assertEquals("繁體中文", formatDisplayLanguage(Locale.forLanguageTag("zh-Hant")))
        assertEquals("繁體中文", formatDisplayLanguage(Locale.forLanguageTag("zh-TW")))
        assertEquals("繁體中文", formatDisplayLanguage(Locale.forLanguageTag("zh-HK")))
        assertEquals("繁體中文", formatDisplayLanguage(Locale.forLanguageTag("zh-MO")))
        assertEquals("English", formatDisplayLanguage(Locale.forLanguageTag("en")))
        assertEquals("日本語", formatDisplayLanguage(Locale.forLanguageTag("ja")))
        assertEquals("한국어", formatDisplayLanguage(Locale.forLanguageTag("ko")))
        assertEquals("Español", formatDisplayLanguage(Locale.forLanguageTag("es")))
        assertEquals("Français", formatDisplayLanguage(Locale.forLanguageTag("fr")))
        assertEquals("Русский", formatDisplayLanguage(Locale.forLanguageTag("ru")))
    }

    @Test
    fun legalPathPrefixMapsAllSupportedLocales() {
        assertEquals("", legalPathPrefix(Locale.forLanguageTag("en-US")))
        assertEquals("/zh", legalPathPrefix(Locale.forLanguageTag("zh-Hans")))
        assertEquals("/zh", legalPathPrefix(Locale.forLanguageTag("zh-CN")))
        assertEquals("/zh-hant", legalPathPrefix(Locale.forLanguageTag("zh-Hant")))
        assertEquals("/zh-hant", legalPathPrefix(Locale.forLanguageTag("zh-TW")))
        assertEquals("/zh-hant", legalPathPrefix(Locale.forLanguageTag("zh-HK")))
        assertEquals("/ja", legalPathPrefix(Locale.forLanguageTag("ja-JP")))
        assertEquals("/ko", legalPathPrefix(Locale.forLanguageTag("ko-KR")))
        assertEquals("/es", legalPathPrefix(Locale.forLanguageTag("es-ES")))
        assertEquals("/fr", legalPathPrefix(Locale.forLanguageTag("fr-FR")))
        assertEquals("/ru", legalPathPrefix(Locale.forLanguageTag("ru-RU")))
        assertEquals("", legalPathPrefix(Locale.forLanguageTag("de-DE")))
    }
}
