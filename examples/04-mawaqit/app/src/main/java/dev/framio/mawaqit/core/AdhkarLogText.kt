package dev.framio.mawaqit.core

/**
 * The words of the adhkar log and the reminder schedules, in the display's six languages: a table of its own next to
 * [Strings] (read with [Strings.log]), so the log's many keys stay together. Missing keys fall back to English.
 */
object AdhkarLogText {
    fun get(lang: String, key: String): String = TABLES[lang]?.get(key) ?: EN[key] ?: key

    private val EN = mapOf(
        "sch_before_prayer" to "before {p}", "log" to "Log", "log_title" to "Adhkar log", "log_today" to "Today", "log_streak" to "Current streak",
        "log_best" to "Best streak", "log_days" to "days", "log_day" to "day", "log_streak_hint" to "all planned done",
        "log_rate" to "Completed", "log_7" to "7 days", "log_30" to "30 days", "log_heatmap" to "The last four months",
        "log_less" to "Less", "log_more" to "More", "log_tasbih" to "Tasbih", "log_usual" to "Usually done at",
        "log_empty" to "Nothing logged yet. Each collection you read is kept here, day by day.",
        "log_prayers" to "{n}/5 prayers", "log_next" to "Next: {c} at {t}", "log_collections" to "Collections",
        "log_goal" to "Planned: {c}", "log_none_today" to "Not started", "log_week" to "this week",
        "sch_title" to "Reminders", "sch_add" to "Add a reminder", "sch_edit" to "Reminder", "sch_clock" to "Clock time",
        "sch_prayer" to "Around a prayer", "sch_after" to "{n} min after {p}", "sch_before" to "{n} min before {p}",
        "sch_at_prayer" to "after {p}", "sch_every_day" to "Every day", "sch_remind" to "Remind me", "sch_open" to "Open by itself",
        "sch_phone" to "Phone", "sch_wake" to "Wake the screen", "sch_delete" to "Delete", "sch_save" to "Save",
        "sch_cancel" to "Cancel", "sch_empty" to "No reminders yet.", "sch_collection" to "Collection", "sch_days" to "Days",
        "sch_mode" to "At that time", "sch_off" to "Off", "sch_on" to "On", "sch_minutes" to "Minutes",
        "sch_hint" to "Never during the adhan, iqama or prayer: it waits until they are over (up to 45 min). At night only a quiet line.",
        "sch_push_on" to "Phone notifications: on (ntfy)", "sch_push_off" to "Phone notifications: off · set them up on the panel",
        "push_text" to "{c}: {d}/{n} said · until {u}", "push_test" to "Test from your Mawaqit frame: phone reminders work.",
    )

    private val AR = mapOf(
        "sch_before_prayer" to "قبل {p}", "log" to "السجل", "log_title" to "سجل الأذكار", "log_today" to "اليوم", "log_streak" to "السلسلة الحالية",
        "log_best" to "أطول سلسلة", "log_days" to "أيام", "log_day" to "يوم", "log_streak_hint" to "كل المقرر",
        "log_rate" to "الإتمام", "log_7" to "7 أيام", "log_30" to "30 يومًا", "log_heatmap" to "الأشهر الأربعة الأخيرة",
        "log_less" to "أقل", "log_more" to "أكثر", "log_tasbih" to "التسبيح", "log_usual" to "الوقت المعتاد",
        "log_empty" to "لا شيء في السجل بعد. كل ما تقرؤه من الأذكار يُحفظ هنا يومًا بيوم.",
        "log_prayers" to "{n}/5 صلوات", "log_next" to "التالي: {c} الساعة {t}", "log_collections" to "الأذكار",
        "log_goal" to "المقرر: {c}", "log_none_today" to "لم تبدأ", "log_week" to "هذا الأسبوع",
        "sch_title" to "التذكير", "sch_add" to "إضافة تذكير", "sch_edit" to "تذكير", "sch_clock" to "ساعة محددة",
        "sch_prayer" to "حول الصلاة", "sch_after" to "بعد {p} بـ {n} دقيقة", "sch_before" to "قبل {p} بـ {n} دقيقة",
        "sch_at_prayer" to "بعد {p}", "sch_every_day" to "كل يوم", "sch_remind" to "ذكّرني", "sch_open" to "افتحها تلقائيًا",
        "sch_phone" to "الهاتف", "sch_wake" to "أيقظ الشاشة", "sch_delete" to "حذف", "sch_save" to "حفظ",
        "sch_cancel" to "إلغاء", "sch_empty" to "لا يوجد تذكير بعد.", "sch_collection" to "الأذكار", "sch_days" to "الأيام",
        "sch_mode" to "في ذلك الوقت", "sch_off" to "متوقف", "sch_on" to "مفعّل", "sch_minutes" to "الدقائق",
        "sch_hint" to "لا يظهر أبدًا أثناء الأذان أو الإقامة أو الصلاة: ينتظر انتهاءها (حتى 45 دقيقة). في الليل سطر هادئ فقط.",
        "sch_push_on" to "إشعارات الهاتف: مفعّلة (ntfy)", "sch_push_off" to "إشعارات الهاتف: متوقفة · فعّلها من لوحة التحكم",
        "push_text" to "{c}: {d}/{n} · حتى {u}", "push_test" to "تجربة من شاشة مواقيت: تذكير الهاتف يعمل.",
    )

    private val FR = mapOf(
        "sch_before_prayer" to "avant {p}", "log" to "Journal", "log_title" to "Journal des adhkar", "log_today" to "Aujourd'hui", "log_streak" to "Série en cours",
        "log_best" to "Meilleure série", "log_days" to "jours", "log_day" to "jour", "log_streak_hint" to "tout le prévu fait",
        "log_rate" to "Terminés", "log_7" to "7 jours", "log_30" to "30 jours", "log_heatmap" to "Les quatre derniers mois",
        "log_less" to "Moins", "log_more" to "Plus", "log_tasbih" to "Tasbih", "log_usual" to "Fait d'habitude à",
        "log_empty" to "Rien encore. Chaque recueil lu est gardé ici, jour après jour.",
        "log_prayers" to "{n}/5 prières", "log_next" to "Prochain : {c} à {t}", "log_collections" to "Recueils",
        "log_goal" to "Prévu : {c}", "log_none_today" to "Pas commencé", "log_week" to "cette semaine",
        "sch_title" to "Rappels", "sch_add" to "Ajouter un rappel", "sch_edit" to "Rappel", "sch_clock" to "Heure fixe",
        "sch_prayer" to "Autour d'une prière", "sch_after" to "{n} min après {p}", "sch_before" to "{n} min avant {p}",
        "sch_at_prayer" to "après {p}", "sch_every_day" to "Tous les jours", "sch_remind" to "Me le rappeler", "sch_open" to "Ouvrir tout seul",
        "sch_phone" to "Téléphone", "sch_wake" to "Rallumer l'écran", "sch_delete" to "Supprimer", "sch_save" to "Enregistrer",
        "sch_cancel" to "Annuler", "sch_empty" to "Aucun rappel.", "sch_collection" to "Recueil", "sch_days" to "Jours",
        "sch_mode" to "À ce moment", "sch_off" to "Désactivé", "sch_on" to "Activé", "sch_minutes" to "Minutes",
        "sch_hint" to "Jamais pendant l'adhan, l'iqama ou la prière : il attend qu'ils soient finis (45 min au plus). La nuit, une ligne discrète.",
        "sch_push_on" to "Notifications téléphone : activées (ntfy)", "sch_push_off" to "Notifications téléphone : désactivées · à régler sur le panneau",
        "push_text" to "{c} : {d}/{n} dites · jusqu'à {u}", "push_test" to "Test de votre cadre Mawaqit : les rappels sur téléphone fonctionnent.",
    )

    private val DE = mapOf(
        "sch_before_prayer" to "vor {p}", "log" to "Verlauf", "log_title" to "Adhkar-Verlauf", "log_today" to "Heute", "log_streak" to "Aktuelle Serie",
        "log_best" to "Längste Serie", "log_days" to "Tage", "log_day" to "Tag", "log_streak_hint" to "alles Geplante",
        "log_rate" to "Erledigt", "log_7" to "7 Tage", "log_30" to "30 Tage", "log_heatmap" to "Die letzten vier Monate",
        "log_less" to "Weniger", "log_more" to "Mehr", "log_tasbih" to "Tasbih", "log_usual" to "Meist erledigt um",
        "log_empty" to "Noch nichts. Jede gelesene Sammlung wird hier Tag für Tag festgehalten.",
        "log_prayers" to "{n}/5 Gebete", "log_next" to "Nächste: {c} um {t}", "log_collections" to "Sammlungen",
        "log_goal" to "Geplant: {c}", "log_none_today" to "Nicht begonnen", "log_week" to "diese Woche",
        "sch_title" to "Erinnerungen", "sch_add" to "Erinnerung hinzufügen", "sch_edit" to "Erinnerung", "sch_clock" to "Uhrzeit",
        "sch_prayer" to "Um ein Gebet", "sch_after" to "{n} Min. nach {p}", "sch_before" to "{n} Min. vor {p}",
        "sch_at_prayer" to "nach {p}", "sch_every_day" to "Jeden Tag", "sch_remind" to "Erinnern", "sch_open" to "Von selbst öffnen",
        "sch_phone" to "Handy", "sch_wake" to "Bildschirm wecken", "sch_delete" to "Löschen", "sch_save" to "Speichern",
        "sch_cancel" to "Abbrechen", "sch_empty" to "Noch keine Erinnerungen.", "sch_collection" to "Sammlung", "sch_days" to "Tage",
        "sch_mode" to "Zu dieser Zeit", "sch_off" to "Aus", "sch_on" to "An", "sch_minutes" to "Minuten",
        "sch_hint" to "Nie während Adhan, Iqama oder Gebet: sie wartet, bis sie vorbei sind (höchstens 45 Min.). Nachts nur eine leise Zeile.",
        "sch_push_on" to "Handy-Benachrichtigungen: an (ntfy)", "sch_push_off" to "Handy-Benachrichtigungen: aus · im Panel einrichten",
        "push_text" to "{c}: {d}/{n} gesagt · bis {u}", "push_test" to "Test von deinem Mawaqit-Rahmen: Erinnerungen aufs Handy funktionieren.",
    )

    private val NL = mapOf(
        "sch_before_prayer" to "voor {p}", "log" to "Logboek", "log_title" to "Adhkar-logboek", "log_today" to "Vandaag", "log_streak" to "Huidige reeks",
        "log_best" to "Langste reeks", "log_days" to "dagen", "log_day" to "dag", "log_streak_hint" to "al het geplande",
        "log_rate" to "Voltooid", "log_7" to "7 dagen", "log_30" to "30 dagen", "log_heatmap" to "De laatste vier maanden",
        "log_less" to "Minder", "log_more" to "Meer", "log_tasbih" to "Tasbih", "log_usual" to "Meestal gedaan om",
        "log_empty" to "Nog niets. Elke gelezen verzameling wordt hier dag na dag bewaard.",
        "log_prayers" to "{n}/5 gebeden", "log_next" to "Volgende: {c} om {t}", "log_collections" to "Verzamelingen",
        "log_goal" to "Gepland: {c}", "log_none_today" to "Niet begonnen", "log_week" to "deze week",
        "sch_title" to "Herinneringen", "sch_add" to "Herinnering toevoegen", "sch_edit" to "Herinnering", "sch_clock" to "Kloktijd",
        "sch_prayer" to "Rond een gebed", "sch_after" to "{n} min na {p}", "sch_before" to "{n} min voor {p}",
        "sch_at_prayer" to "na {p}", "sch_every_day" to "Elke dag", "sch_remind" to "Herinneren", "sch_open" to "Vanzelf openen",
        "sch_phone" to "Telefoon", "sch_wake" to "Scherm wekken", "sch_delete" to "Verwijderen", "sch_save" to "Opslaan",
        "sch_cancel" to "Annuleren", "sch_empty" to "Nog geen herinneringen.", "sch_collection" to "Verzameling", "sch_days" to "Dagen",
        "sch_mode" to "Op dat moment", "sch_off" to "Uit", "sch_on" to "Aan", "sch_minutes" to "Minuten",
        "sch_hint" to "Nooit tijdens adhan, iqama of gebed: ze wacht tot die voorbij zijn (hoogstens 45 min). 's Nachts alleen een rustige regel.",
        "sch_push_on" to "Telefoonmeldingen: aan (ntfy)", "sch_push_off" to "Telefoonmeldingen: uit · in te stellen op het paneel",
        "push_text" to "{c}: {d}/{n} gezegd · tot {u}", "push_test" to "Test van je Mawaqit-lijst: herinneringen op de telefoon werken.",
    )

    private val TR = mapOf(
        "sch_before_prayer" to "{p} öncesi", "log" to "Kayıt", "log_title" to "Zikir kaydı", "log_today" to "Bugün", "log_streak" to "Mevcut seri",
        "log_best" to "En uzun seri", "log_days" to "gün", "log_day" to "gün", "log_streak_hint" to "planlananların hepsi",
        "log_rate" to "Tamamlanan", "log_7" to "7 gün", "log_30" to "30 gün", "log_heatmap" to "Son dört ay",
        "log_less" to "Az", "log_more" to "Çok", "log_tasbih" to "Tesbih", "log_usual" to "Genellikle saat",
        "log_empty" to "Henüz kayıt yok. Okuduğun her koleksiyon burada gün gün saklanır.",
        "log_prayers" to "{n}/5 namaz", "log_next" to "Sıradaki: {c}, {t}", "log_collections" to "Koleksiyonlar",
        "log_goal" to "Planlanan: {c}", "log_none_today" to "Başlanmadı", "log_week" to "bu hafta",
        "sch_title" to "Hatırlatmalar", "sch_add" to "Hatırlatma ekle", "sch_edit" to "Hatırlatma", "sch_clock" to "Saat",
        "sch_prayer" to "Bir namaza göre", "sch_after" to "{p} sonrası {n} dk", "sch_before" to "{p} öncesi {n} dk",
        "sch_at_prayer" to "{p} sonrası", "sch_every_day" to "Her gün", "sch_remind" to "Hatırlat", "sch_open" to "Kendiliğinden aç",
        "sch_phone" to "Telefon", "sch_wake" to "Ekranı uyandır", "sch_delete" to "Sil", "sch_save" to "Kaydet",
        "sch_cancel" to "İptal", "sch_empty" to "Henüz hatırlatma yok.", "sch_collection" to "Koleksiyon", "sch_days" to "Günler",
        "sch_mode" to "O saatte", "sch_off" to "Kapalı", "sch_on" to "Açık", "sch_minutes" to "Dakika",
        "sch_hint" to "Ezan, kamet veya namaz sırasında asla: bitmelerini bekler (en çok 45 dk). Gece yalnızca sessiz bir satır.",
        "sch_push_on" to "Telefon bildirimleri: açık (ntfy)", "sch_push_off" to "Telefon bildirimleri: kapalı · panelden kurun",
        "push_text" to "{c}: {d}/{n} · {u} saatine kadar", "push_test" to "Mawaqit çerçevenden deneme: telefon hatırlatmaları çalışıyor.",
    )

    private val TABLES = mapOf("en" to EN, "ar" to AR, "fr" to FR, "de" to DE, "nl" to NL, "tr" to TR)
}

/** A word of the adhkar log or its schedules ([AdhkarLogText]) in this display language. */
fun Strings.log(key: String): String = AdhkarLogText.get(lang, key)
