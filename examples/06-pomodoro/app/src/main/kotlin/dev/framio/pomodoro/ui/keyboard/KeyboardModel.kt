package dev.framio.pomodoro.ui.keyboard

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

enum class KeyLayer { Letters, Symbols }

/** Shift: off, for the next letter only, or locked (caps). */
enum class Shift { Off, Once, Locked }

/** The long-press strip: [key]'s variants, already in the right case. */
data class AccentStrip(val key: String, val options: List<String>)

/**
 * The built-in keyboard's state and rules, apart from how it is drawn (so the JVM tests type on it):
 *  - letters (QWERTY) and one numbers/symbols layer;
 *  - Shift: a tap is one capital, a second tap within [DOUBLE_TAP_MS] (or a long press) locks caps, a tap on locked
 *    turns it off; a sentence start (empty text, or after ". ", "! ", "? ") shifts by itself;
 *  - a long press shows the key's variants: accents for at least French and German (é è ê ë à â ä ç ô ö œ ù û ü ÿ ß …)
 *    and typographic marks („ “ ” « » – — … €); a key without variants just types itself;
 *  - Backspace deletes one at once and, held, repeats after [REPEAT_START] every [REPEAT_EVERY] (whole words after
 *    [WORDS_AFTER] repeats, for clearing a long line quickly);
 *  - at most [maxLength] characters; Done belongs to the sheet.
 *
 * The text is the model's own copy, updated at once and reported through [onChange]: two quick taps never type onto a
 * stale value. [sync] takes an outside change (another screen cleared it).
 */
@Stable
class KeyboardModel(
    initial: String,
    private val scope: CoroutineScope,
    private val maxLength: Int = 80,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    var onChange: (String) -> Unit = {},
) {
    var text by mutableStateOf(initial.take(maxLength))
        private set
    var layer by mutableStateOf(KeyLayer.Letters)
        private set
    var shift by mutableStateOf(Shift.Off)
        private set
    var accents by mutableStateOf<AccentStrip?>(null)
        private set

    private var lastShiftTap = Long.MIN_VALUE / 2
    private var repeat: Job? = null

    init {
        autoShift()
    }

    /** The screen's value changed by itself (not through this keyboard). */
    fun sync(value: String) {
        if (value != text) {
            text = value.take(maxLength)
            autoShift()
        }
    }

    /** A letter, digit or mark. */
    fun tap(key: String) {
        accents = null
        insert(cased(key))
    }

    /** Shows [key]'s variants, or types it when it has none. */
    fun longPress(key: String) {
        val options = Variants[key.lowercase()]
        if (options == null) {
            tap(key)
            return
        }
        accents = AccentStrip(key, options.map(::cased))
    }

    fun pick(option: String) {
        accents = null
        insert(option)
    }

    fun dismissAccents() {
        accents = null
    }

    fun space() = tap(" ")

    fun shiftTap() {
        accents = null
        val t = clock()
        shift = when {
            shift == Shift.Locked -> Shift.Off
            shift == Shift.Once && t - lastShiftTap <= DOUBLE_TAP_MS -> Shift.Locked
            shift == Shift.Once -> Shift.Off
            else -> Shift.Once
        }
        lastShiftTap = t
    }

    fun shiftLock() {
        accents = null
        shift = Shift.Locked
    }

    fun switchLayer() {
        accents = null
        layer = if (layer == KeyLayer.Letters) KeyLayer.Symbols else KeyLayer.Letters
    }

    /** Backspace pressed: one now, more while held. */
    fun backspaceDown() {
        accents = null
        repeat?.cancel()
        deleteOne()
        repeat = scope.launch {
            delay(REPEAT_START)
            var n = 0
            while (text.isNotEmpty()) {
                if (n++ < WORDS_AFTER) deleteOne() else deleteWord()
                delay(REPEAT_EVERY)
            }
        }
    }

    fun backspaceUp() {
        repeat?.cancel()
        repeat = null
    }

    /** Removes the last character (a surrogate pair as one). */
    fun deleteOne() {
        if (text.isEmpty()) return
        val cut = if (text.length >= 2 && text[text.length - 1].isLowSurrogate()) 2 else 1
        set(text.dropLast(cut))
    }

    private fun deleteWord() {
        val trimmed = text.trimEnd()
        val start = trimmed.lastIndexOf(' ') + 1
        set(trimmed.substring(0, start))
    }

    fun clear() = set("")

    private fun insert(s: String) {
        if (text.length + s.length > maxLength) return
        set(text + s)
        if (shift == Shift.Once && s.any { it.isLetter() }) shift = Shift.Off
        if (layer == KeyLayer.Symbols && s == " ") layer = KeyLayer.Letters
    }

    private fun set(value: String) {
        text = value
        autoShift()
        onChange(value)
    }

    /** Sentence case: a capital at the start and after an ended sentence, unless caps are locked. */
    private fun autoShift() {
        if (shift == Shift.Locked) return
        val start = text.isEmpty() || SentenceEnds.any { text.endsWith(it) }
        if (start) {
            shift = Shift.Once
        } else if (shift == Shift.Once && text.isNotEmpty() && text.last().isLetterOrDigit()) {
            shift = Shift.Off
        }
    }

    /** Upper case under Shift on the letters; ß has no everyday capital (uppercase() would make it "SS"). */
    private fun cased(s: String) = if (shift != Shift.Off && layer == KeyLayer.Letters && s != "ß") s.uppercase() else s

    companion object {
        const val DOUBLE_TAP_MS = 400L
        val REPEAT_START: Duration = 450.milliseconds
        val REPEAT_EVERY: Duration = 70.milliseconds
        const val WORDS_AFTER = 12

        private val SentenceEnds = listOf(". ", "! ", "? ")

        /** QWERTY, as three rows (Shift and Backspace flank the third). */
        val LetterRows: List<List<String>> = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").map { r -> r.map(Char::toString) }

        /** Digits, then marks; the third row's first key sits where Shift is on the letters. */
        val SymbolRows: List<List<String>> = listOf(
            "1234567890".map(Char::toString),
            listOf("@", "#", "€", "&", "-", "+", "=", "(", ")", "/"),
            listOf("%", "*", "\"", "'", ":", ";", "!", "?"),
        )

        /**
         * Long-press variants. French: à â æ ç é è ê ë î ï ô œ ù û ü ÿ « »; German: ä ö ü ß „ “; plus Spanish ñ ¿ ¡, the
         * Nordic å ø, and common marks.
         */
        val Variants: Map<String, List<String>> = mapOf(
            "a" to listOf("à", "â", "ä", "á", "æ", "å", "ã"),
            "c" to listOf("ç", "ć", "č"),
            "e" to listOf("é", "è", "ê", "ë", "ē"),
            "i" to listOf("î", "ï", "í", "ì"),
            "n" to listOf("ñ", "ń"),
            "o" to listOf("ö", "ô", "ó", "ò", "œ", "ø", "õ"),
            "s" to listOf("ß", "ś", "š"),
            "u" to listOf("ü", "û", "ù", "ú"),
            "y" to listOf("ÿ", "ý"),
            "z" to listOf("ž", "ż"),
            "-" to listOf("–", "—", "_"),
            "€" to listOf("$", "£", "¥", "¢"),
            "\"" to listOf("„", "“", "”", "«", "»"),
            "'" to listOf("‚", "‘", "’", "‹", "›"),
            "?" to listOf("¿"),
            "!" to listOf("¡"),
            "." to listOf("…", "·"),
            "/" to listOf("\\", "|"),
            "(" to listOf("[", "{", "<"),
            ")" to listOf("]", "}", ">"),
            "&" to listOf("§"),
            "%" to listOf("‰", "°"),
            "*" to listOf("•", "†"),
            "+" to listOf("±", "×", "÷"),
            "=" to listOf("≠", "≈"),
        )
    }
}
