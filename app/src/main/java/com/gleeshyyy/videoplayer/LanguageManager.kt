package com.gleeshyyy.videoplayer

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

object LanguageManager {
    data class Language(val label: String, val tag: String)
    val supported = listOf(
        Language("English", "en"), Language("Français", "fr"), Language("Español", "es"),
        Language("Kiswahili", "sw"), Language("Русский", "ru"), Language("中文（简体）", "zh-CN"),
        Language("हिन्दी", "hi"), Language("తెలుగు", "te"), Language("தமிழ்", "ta")
    )

    private val words = mapOf(
        "fr" to mapOf("Settings" to "Paramètres", "General" to "Général", "Language" to "Langue", "Choose language" to "Choisir la langue", "Video" to "Vidéo", "Subtitles" to "Sous-titres", "Theme" to "Thème", "Show History" to "Afficher l’historique", "Version" to "Version", "Developer" to "Développeur", "Assistant Developer" to "Développeur assistant", "Cancel" to "Annuler", "Close" to "Fermer", "PLAYBACK" to "LECTURE", "ABOUT" to "À PROPOS", "HISTORY" to "HISTORIQUE", "Controls auto-hide" to "Masquage automatique des commandes", "Remember brightness" to "Mémoriser la luminosité", "Remember Subtitles" to "Mémoriser les sous-titres"),
        "es" to mapOf("Settings" to "Ajustes", "General" to "General", "Language" to "Idioma", "Choose language" to "Elegir idioma", "Video" to "Vídeo", "Subtitles" to "Subtítulos", "Theme" to "Tema", "Show History" to "Mostrar historial", "Version" to "Versión", "Developer" to "Desarrollador", "Assistant Developer" to "Desarrollador asistente", "Cancel" to "Cancelar", "Close" to "Cerrar", "PLAYBACK" to "REPRODUCCIÓN", "ABOUT" to "ACERCA DE", "HISTORY" to "HISTORIAL", "Controls auto-hide" to "Ocultar controles automáticamente", "Remember brightness" to "Recordar brillo", "Remember Subtitles" to "Recordar subtítulos"),
        "sw" to mapOf("Settings" to "Mipangilio", "General" to "Jumla", "Language" to "Lugha", "Choose language" to "Chagua lugha", "Video" to "Video", "Subtitles" to "Manukuu", "Theme" to "Mandhari", "Show History" to "Onyesha historia", "Version" to "Toleo", "Developer" to "Msanidi", "Cancel" to "Ghairi", "Close" to "Funga", "ABOUT" to "KUHUSU", "HISTORY" to "HISTORIA", "Controls auto-hide" to "Ficha vidhibiti kiotomatiki", "Remember Subtitles" to "Kumbuka manukuu"),
        "ru" to mapOf("Settings" to "Настройки", "General" to "Общие", "Language" to "Язык", "Choose language" to "Выберите язык", "Video" to "Видео", "Subtitles" to "Субтитры", "Theme" to "Тема", "Show History" to "Показывать историю", "Version" to "Версия", "Developer" to "Разработчик", "Cancel" to "Отмена", "Close" to "Закрыть", "ABOUT" to "О ПРИЛОЖЕНИИ", "HISTORY" to "ИСТОРИЯ", "Controls auto-hide" to "Автоскрытие элементов управления", "Remember Subtitles" to "Запоминать субтитры"),
        "zh" to mapOf("Settings" to "设置", "General" to "常规", "Language" to "语言", "Choose language" to "选择语言", "Video" to "视频", "Subtitles" to "字幕", "Theme" to "主题", "Show History" to "显示历史记录", "Version" to "版本", "Developer" to "开发者", "Cancel" to "取消", "Close" to "关闭", "ABOUT" to "关于", "HISTORY" to "历史记录", "Controls auto-hide" to "控制栏自动隐藏", "Remember Subtitles" to "记住字幕"),
        "hi" to mapOf("Settings" to "सेटिंग्स", "General" to "सामान्य", "Language" to "भाषा", "Choose language" to "भाषा चुनें", "Video" to "वीडियो", "Subtitles" to "उपशीर्षक", "Theme" to "थीम", "Show History" to "इतिहास दिखाएँ", "Version" to "संस्करण", "Developer" to "डेवलपर", "Cancel" to "रद्द करें", "Close" to "बंद करें", "ABOUT" to "ऐप के बारे में", "HISTORY" to "इतिहास", "Controls auto-hide" to "कंट्रोल अपने आप छिपाएँ", "Remember Subtitles" to "उपशीर्षक याद रखें"),
        "te" to mapOf("Settings" to "సెట్టింగ్‌లు", "General" to "సాధారణం", "Language" to "భాష", "Choose language" to "భాషను ఎంచుకోండి", "Video" to "వీడియో", "Subtitles" to "ఉపశీర్షికలు", "Theme" to "థీమ్", "Show History" to "చరిత్రను చూపించు", "Version" to "వెర్షన్", "Developer" to "డెవలపర్", "Cancel" to "రద్దు", "Close" to "మూసివేయి", "ABOUT" to "గురించి", "HISTORY" to "చరిత్ర", "Controls auto-hide" to "నియంత్రణలను ఆటో దాచు", "Remember Subtitles" to "ఉపశీర్షికలను గుర్తుంచుకో"),
        "ta" to mapOf("Settings" to "அமைப்புகள்", "General" to "பொது", "Language" to "மொழி", "Choose language" to "மொழியைத் தேர்ந்தெடு", "Video" to "வீடியோ", "Subtitles" to "வசனங்கள்", "Theme" to "தீம்", "Show History" to "வரலாற்றைக் காட்டு", "Version" to "பதிப்பு", "Developer" to "உருவாக்குநர்", "Cancel" to "ரத்து செய்", "Close" to "மூடு", "ABOUT" to "பற்றி", "HISTORY" to "வரலாறு", "Controls auto-hide" to "கட்டுப்பாடுகளைத் தானாக மறை", "Remember Subtitles" to "வசனங்களை நினைவில் கொள்")
    )

    fun currentTag(): String = AppCompatDelegate.getApplicationLocales().toLanguageTags().ifBlank { "en" }
    fun translate(context: Context, source: String): String =
        words[currentTag().substringBefore('-').lowercase()]?.get(source) ?: source
    fun apply(tag: String) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }
}
