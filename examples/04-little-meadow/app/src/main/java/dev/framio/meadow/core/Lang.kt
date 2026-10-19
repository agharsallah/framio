package dev.framio.meadow.core

/** The narration language. Children hear it; the few on-screen words are for grown-ups. */
enum class Lang(val code: String, val label: String) {
    EN("en", "English"),
    DE("de", "Deutsch");

    companion object {
        fun of(code: String?): Lang = entries.firstOrNull { it.code == code } ?: EN
    }
}

/** A short on-screen text in every language. German falls back to English when not given. */
data class Txt(val en: String, val de: String = en) {
    operator fun get(lang: Lang): String = when (lang) {
        Lang.EN -> en
        Lang.DE -> de
    }
}
