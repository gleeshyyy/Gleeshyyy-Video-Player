package com.gleeshyyy.videoplayer

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * Centralized app-language configuration. Language choice is stored by AppCompat
 * and survives process restarts. English is the default.
 */
object LanguageManager {
    data class Language(val label: String, val tag: String)

    val supported = listOf(
        Language("English", "en"),
        Language("Français", "fr"),
        Language("Español", "es"),
        Language("Kiswahili", "sw"),
        Language("Русский", "ru"),
        Language("中文（简体）", "zh-CN"),
        Language("हिन्दी", "hi"),
        Language("తెలుగు", "te"),
        Language("தமிழ்", "ta")
    )

    fun currentTag(): String = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        .ifBlank { "en" }

    fun apply(tag: String) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }
}
