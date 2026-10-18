package dev.framio.mawaqit.core

/**
 * The words of the presence policy (the settings sheet's "Screen" row) and of the "adhan soon" notice, in the
 * display's six languages: a table of its own next to [Strings] (read with [Strings.presence]); missing keys fall
 * back to English.
 */
object PresenceText {
    fun get(lang: String, key: String): String = TABLES[lang]?.get(key) ?: EN[key] ?: key

    /** "20 min", Arabic with its plural forms ("دقيقتان", "5 دقائق", "20 دقيقة"). */
    fun minutes(lang: String, n: Int): String = when (lang) {
        "ar" -> when {
            n == 1 -> "دقيقة"
            n == 2 -> "دقيقتين"
            n in 3..10 -> "$n دقائق"
            else -> "$n دقيقة"
        }
        else -> get(lang, "min").replace("{n}", n.toString())
    }

    private val EN = mapOf(
        "min" to "{n} min", "screen" to "Screen", "always_on" to "Always on", "screen_off" to "Turn off",
        "home_screen" to "Home screen", "away_after" to "After {m} untouched", "before_adhan" to "Notice {m} before the adhan",
        "before_adhan_off" to "No notice before the adhan", "periodic_off" to "Adhkar now and then: off",
        "periodic_every" to "Adhkar every {m}", "periodic_random" to "Adhkar at random, {a}–{b} min",
        "wake_messages" to "Messages wake it", "wake_reminders" to "Reminders wake it",
    )

    private val AR = mapOf(
        "screen" to "الشاشة", "always_on" to "تعمل دائمًا", "screen_off" to "إطفاء", "home_screen" to "الشاشة الرئيسية",
        "away_after" to "بعد {m} دون لمس", "before_adhan" to "تنبيه قبل الأذان بـ {m}", "before_adhan_off" to "لا تنبيه قبل الأذان",
        "periodic_off" to "أذكار من حين لآخر: لا", "periodic_every" to "أذكار كل {m}", "periodic_random" to "أذكار عشوائيًا، {a}–{b} دقيقة",
        "wake_messages" to "الرسائل توقظها", "wake_reminders" to "التذكير يوقظها",
    )

    private val FR = mapOf(
        "min" to "{n} min", "screen" to "Écran", "always_on" to "Toujours allumé", "screen_off" to "Éteindre",
        "home_screen" to "Écran d'accueil", "away_after" to "Après {m} sans contact", "before_adhan" to "Avis {m} avant l'adhan",
        "before_adhan_off" to "Pas d'avis avant l'adhan", "periodic_off" to "Adhkar de temps en temps : non",
        "periodic_every" to "Adhkar toutes les {m}", "periodic_random" to "Adhkar au hasard, {a}–{b} min",
        "wake_messages" to "Les messages l'allument", "wake_reminders" to "Les rappels l'allument",
    )

    private val DE = mapOf(
        "min" to "{n} Min.", "screen" to "Bildschirm", "always_on" to "Immer an", "screen_off" to "Ausschalten",
        "home_screen" to "Startbildschirm", "away_after" to "Nach {m} ohne Berührung", "before_adhan" to "Hinweis {m} vor dem Adhan",
        "before_adhan_off" to "Kein Hinweis vor dem Adhan", "periodic_off" to "Adhkar ab und zu: aus",
        "periodic_every" to "Adhkar alle {m}", "periodic_random" to "Adhkar zufällig, {a}–{b} Min.",
        "wake_messages" to "Nachrichten wecken", "wake_reminders" to "Erinnerungen wecken",
    )

    private val NL = mapOf(
        "min" to "{n} min", "screen" to "Scherm", "always_on" to "Altijd aan", "screen_off" to "Uitzetten",
        "home_screen" to "Startscherm", "away_after" to "Na {m} onaangeraakt", "before_adhan" to "Melding {m} voor de adhan",
        "before_adhan_off" to "Geen melding voor de adhan", "periodic_off" to "Adhkar af en toe: uit",
        "periodic_every" to "Adhkar elke {m}", "periodic_random" to "Adhkar willekeurig, {a}–{b} min",
        "wake_messages" to "Berichten wekken", "wake_reminders" to "Herinneringen wekken",
    )

    private val TR = mapOf(
        "min" to "{n} dk", "screen" to "Ekran", "always_on" to "Hep açık", "screen_off" to "Kapat",
        "home_screen" to "Ana ekran", "away_after" to "{m} dokunulmazsa", "before_adhan" to "Ezandan {m} önce uyarı",
        "before_adhan_off" to "Ezandan önce uyarı yok", "periodic_off" to "Ara sıra zikir: kapalı",
        "periodic_every" to "Her {m} zikir", "periodic_random" to "Rastgele zikir, {a}–{b} dk",
        "wake_messages" to "Mesajlar uyandırır", "wake_reminders" to "Hatırlatmalar uyandırır",
    )

    private val TABLES = mapOf("en" to EN, "ar" to AR, "fr" to FR, "de" to DE, "nl" to NL, "tr" to TR)
}

fun Strings.presence(key: String): String = PresenceText.get(lang, key)

/** "{m}" in [key] replaced by [minutes] in words. */
fun Strings.presence(key: String, minutes: Int): String = presence(key).replace("{m}", PresenceText.minutes(lang, minutes))
