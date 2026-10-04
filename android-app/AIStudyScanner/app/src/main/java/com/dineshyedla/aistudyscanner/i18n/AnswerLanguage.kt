package com.aistudyscanner.agent.i18n

import android.content.Context

/**
 * A language the AI can explain in.
 *
 * [code] is what the backend expects (see `prompts.LANGUAGES`); [nativeName]
 * is deliberately shown in the language's own script, because a student who
 * needs Telugu explanations is exactly the student who may not read a picker
 * written only in English.
 */
data class AnswerLanguage(
    val code: String,
    val englishName: String,
    val nativeName: String,
) {
    /** "తెలుగు (Telugu)" — the script to recognise plus the name to search for. */
    val label: String
        get() = if (code == DEFAULT_LANGUAGE_CODE) englishName
        else "$nativeName ($englishName)"
}

const val DEFAULT_LANGUAGE_CODE = "en"

/** Kept in step with `LANGUAGES` in the backend's prompts.py. */
val ANSWER_LANGUAGES = listOf(
    AnswerLanguage("en", "English", "English"),
    AnswerLanguage("hi", "Hindi", "हिन्दी"),
    AnswerLanguage("te", "Telugu", "తెలుగు"),
    AnswerLanguage("ta", "Tamil", "தமிழ்"),
    AnswerLanguage("kn", "Kannada", "ಕನ್ನಡ"),
    AnswerLanguage("ml", "Malayalam", "മലയാളം"),
    AnswerLanguage("mr", "Marathi", "मराठी"),
    AnswerLanguage("bn", "Bengali", "বাংলা"),
    AnswerLanguage("gu", "Gujarati", "ગુજરાતી"),
)

fun languageFor(code: String?): AnswerLanguage =
    ANSWER_LANGUAGES.firstOrNull { it.code == code }
        ?: ANSWER_LANGUAGES.first()

/**
 * The student's chosen explanation language, remembered across sessions.
 *
 * Deliberately a device-wide preference rather than per-screen state: a
 * student who reads Telugu reads Telugu everywhere, and asking again on each
 * screen is the kind of friction that gets an app uninstalled.
 */
object LanguagePrefs {
    private const val PREFS = "ai_study_scanner_language"
    private const val KEY_CODE = "answer_language"

    fun get(context: Context): String =
        prefs(context).getString(KEY_CODE, DEFAULT_LANGUAGE_CODE)
            ?: DEFAULT_LANGUAGE_CODE

    fun set(context: Context, code: String) {
        prefs(context).edit().putString(KEY_CODE, code).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
