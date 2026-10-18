package dev.framio.mawaqit.core

/**
 * The Arabic texts of the screens around a prayer. The Arabic is copied verbatim from Mawaqit's own mosque
 * screens (mawaqit.net, v4.134), not retyped; the translations are ours.
 */
object Texts {

    /** One block of a screen: the Arabic, then a line in the screen's language (translation or phonetics). */
    data class Block(val arabic: String, val other: Map<String, String> = emptyMap(), val source: String? = null) {
        fun other(lang: String): String? = if (lang == "ar") null else other[lang] ?: other["en"]
    }

    val duaAfterAdhan = Block(
        "اللّهُـمَّ رَبَّ هَذِهِ الدّعْـوَةِ التّـامَّة وَالصّلاةِ القَـائِمَة آتِ محَـمَّداً الوَسيـلةَ وَالْفَضـيلَة وَابْعَـثْه مَقـامـاً مَحـموداً الَّذي وَعَـدْتَه",
        mapOf(
            "en" to "O Allah, Lord of this perfect call and of the prayer about to be established, grant Muhammad the intercession and the virtue, and raise him to the praised station You have promised him.",
            "fr" to "Ô Allah, Seigneur de cet appel parfait et de la prière qui va être accomplie, accorde à Muhammad l'intercession et le rang d'honneur, et élève-le à la station louable que Tu lui as promise.",
            "de" to "O Allah, Herr dieses vollkommenen Rufes und des Gebets, das nun verrichtet wird, gewähre Muhammad die Fürsprache und den Vorzug, und erhebe ihn zu der lobenswerten Stufe, die Du ihm versprochen hast.",
            "nl" to "O Allah, Heer van deze volmaakte oproep en van het gebed dat zal worden verricht, schenk Mohammed de voorspraak en de voortreffelijkheid, en verhef hem tot de geprezen positie die U hem hebt beloofd.",
            "tr" to "Allah'ım! Bu eksiksiz davetin ve kılınacak namazın Rabbi! Muhammed'e vesileyi ve fazileti ver, onu vaad ettiğin övülmüş makama ulaştır.",
        ),
        "al-Bukhari",
    )

    val betweenAdhanIqama = Block(
        "عَنْ أَنَسِ بْنِ مَالِكٍ قَالَ: قَالَ رَسُولُ اللَّهِ صَلَّى اللَّهُ عَلَيْهِ وَسَلَّمَ: الدُّعَاءُ لَا يُرَدُّ بَيْنَ الْأَذَانِ وَالْإِقَامَةِ",
        mapOf(
            "en" to "Anas ibn Malik reported that the Messenger of Allah ﷺ said: “The supplication between the adhan and the iqama is not rejected.”",
            "fr" to "Anas ibn Malik rapporte que le Messager d'Allah ﷺ a dit : « L'invocation entre l'adhan et l'iqama n'est pas rejetée. »",
            "de" to "Anas ibn Malik berichtete, dass der Gesandte Allahs ﷺ sagte: „Das Bittgebet zwischen Adhan und Iqama wird nicht zurückgewiesen.“",
            "nl" to "Anas ibn Malik overleverde dat de Boodschapper van Allah ﷺ zei: „De smeekbede tussen de adhan en de iqama wordt niet afgewezen.”",
            "tr" to "Enes bin Malik'ten: Resulullah ﷺ şöyle buyurdu: “Ezan ile kamet arasında yapılan dua reddedilmez.”",
        ),
        "Abu Dawud, at-Tirmidhi",
    )

    val jumua = Block(
        "عَنْ أَبِي هُرَيْرَةَ قَالَ قَالَ رَسُولُ اللَّهِ صَلَّى اللَّه عَلَيْهِ وَسَلَّمَ مَنْ تَوَضَّأَ فَأَحْسَنَ الْوُضُوءَ ثُمَّ أَتَى الْجُمُعَةَ فَاسْتَمَعَ وَأَنْصَتَ غُفِرَ لَهُ مَا بَيْنَهُ وَبَيْنَ الْجُمُعَةِ وَزِيَادَةُ ثَلاثَةِ أَيَّامٍ وَمَنْ مَسَّ الْحَصَى فَقَدْ لَغَا",
        mapOf(
            "en" to "Whoever performs ablution well, then comes to Jumu'a, listens and keeps silent, is forgiven what is between that and the next Friday, and three more days. And whoever fiddles with pebbles has engaged in idle talk.",
            "fr" to "Celui qui fait bien ses ablutions, puis vient à la Joumoua, écoute et se tait, il lui est pardonné ce qui est entre ce jour et le vendredi suivant, plus trois jours. Et celui qui touche les cailloux s'est distrait.",
            "de" to "Wer die Waschung gut vollzieht, dann zum Freitagsgebet kommt, zuhört und schweigt, dem wird vergeben, was zwischen diesem und dem nächsten Freitag liegt, und drei Tage mehr. Und wer mit den Kieseln spielt, hat sinnlos gehandelt.",
            "nl" to "Wie de wassing goed verricht, dan naar het vrijdaggebed komt, luistert en zwijgt, hem wordt vergeven wat tussen die en de volgende vrijdag ligt, plus drie dagen. En wie met de kiezels speelt, heeft zich met nutteloosheid beziggehouden.",
            "tr" to "Kim güzelce abdest alır, sonra Cuma'ya gelir, dinler ve susarsa, o Cuma ile diğer Cuma arasındaki ve fazladan üç günün günahları bağışlanır. Kim de çakıl taşlarıyla oynarsa boş işle uğraşmış olur.",
        ),
        "Muslim",
    )

    /** After the prayer: Arabic + phonetics (the same in every language, like the mosque screens). */
    val azkar = listOf(
        Block(
            "أَسْـتَغْفِرُ الله، أَسْـتَغْفِرُ الله، أَسْـتَغْفِرُ الله\nاللّهُـمَّ أَنْـتَ السَّلامُ ، وَمِـنْكَ السَّلام ، تَبارَكْتَ يا ذا الجَـلالِ وَالإِكْـرام\nاللَّهُمَّ أَعِنِّي عَلَى ذِكْرِكَ وَشُكْرِكَ وَحُسْنِ عِبَادَتِكَ",
            mapOf("en" to "Astaghfirullah, Astaghfirullah, Astaghfirullah · Allahumma anta-s-salam wa minka-s-salam, tabarakta ya dhal-jalali wal-ikram · Allahumma a'inni 'ala dhikrika wa shukrika wa husni 'ibadatik"),
        ),
        Block(
            "سُـبْحانَ اللهِ، والحَمْـدُ لله، واللهُ أكْـبَر ٣٣\nلا إِلَٰهَ إلاّ اللّهُ وَحْـدَهُ لا شريكَ لهُ، لهُ الملكُ ولهُ الحَمْد، وهُوَ على كُلّ شَيءٍ قَـدير",
            mapOf("en" to "Subhan Allah, wal-hamdu lillah, wa Allahu akbar (33×) · La ilaha illa Allah, wahdahu la sharika lah, lahu-l-mulku wa lahu-l-hamd, wa huwa 'ala kulli shay'in qadir"),
        ),
        Block(
            "لا إِلَٰهَ إلاّ اللّهُ وحدَهُ لا شريكَ لهُ، لهُ المُـلْكُ ولهُ الحَمْد، وهوَ على كلّ شَيءٍ قَدير\nاللّهُـمَّ لا مانِعَ لِما أَعْطَـيْت، وَلا مُعْطِـيَ لِما مَنَـعْت، وَلا يَنْفَـعُ ذا الجَـدِّ مِنْـكَ الجَـد",
            mapOf("en" to "La ilaha illa Allah, wahdahu la sharika lah… · Allahumma la mani'a lima a'tayt, wa la mu'tiya lima mana't, wa la yanfa'u dhal-jaddi minka-l-jadd"),
        ),
        Block(
            "ٱللَّهُ لَآ إِلَٰهَ إِلَّا هُوَ ٱلۡحَيُّ ٱلۡقَيُّومُۚ لَا تَأۡخُذُهُۥ سِنَةٞ وَلَا نَوۡمٞۚ لَّهُۥ مَا فِي ٱلسَّمَٰوَٰتِ وَمَا فِي ٱلۡأَرۡضِۗ مَن ذَا ٱلَّذِي يَشۡفَعُ عِندَهُۥٓ إِلَّا بِإِذۡنِهِۦۚ يَعۡلَمُ مَا بَيۡنَ أَيۡدِيهِمۡ وَمَا خَلۡفَهُمۡۖ وَلَا يُحِيطُونَ بِشَيۡءٖ مِّنۡ عِلۡمِهِۦٓ إِلَّا بِمَا شَآءَۚ وَسِعَ كُرۡسِيُّهُ ٱلسَّمَٰوَٰتِ وَٱلۡأَرۡضَۖ وَلَا يَ‍ُٔودُهُۥ حِفۡظُهُمَاۚ وَهُوَ ٱلۡعَلِيُّ ٱلۡعَظِيمُ",
            mapOf("en" to "Ayat al-Kursi"),
            "al-Baqara 2:255",
        ),
        Block(
            "بِسۡمِ ٱللَّهِ ٱلرَّحۡمَٰنِ ٱلرَّحِيمِ\nقُلۡ هُوَ ٱللَّهُ أَحَدٌ ، ٱللَّهُ ٱلصَّمَدُ ، لَمۡ يَلِدۡ وَلَمۡ يُولَدۡ ، وَلَمۡ يَكُن لَّهُۥ كُفُوًا أَحَدُۢ",
            mapOf("en" to "Surat al-Ikhlas"),
            "112",
        ),
        Block(
            "بِسۡمِ ٱللَّهِ ٱلرَّحۡمَٰنِ ٱلرَّحِيمِ\nقُلۡ أَعُوذُ بِرَبِّ ٱلۡفَلَقِ ، مِن شَرِّ مَا خَلَقَ ، وَمِن شَرِّ غَاسِقٍ إِذَا وَقَبَ ، وَمِن شَرِّ ٱلنَّفَّٰثَٰتِ فِي ٱلۡعُقَدِ ، وَمِن شَرِّ حَاسِدٍ إِذَا حَسَدَ",
            mapOf("en" to "Surat al-Falaq"),
            "113",
        ),
        Block(
            "بِسۡمِ ٱللَّهِ ٱلرَّحۡمَٰنِ ٱلرَّحِيمِ\nقُلۡ أَعُوذُ بِرَبِّ ٱلنَّاسِ ، مَلِكِ ٱلنَّاسِ ، إِلَٰهِ ٱلنَّاسِ ، مِن شَرِّ ٱلۡوَسۡوَاسِ ٱلۡخَنَّاسِ ، ٱلَّذِي يُوَسۡوِسُ فِي صُدُورِ ٱلنَّاسِ ، مِنَ ٱلۡجِنَّةِ وَٱلنَّاسِ",
            mapOf("en" to "Surat an-Nas"),
            "114",
        ),
    )
}
