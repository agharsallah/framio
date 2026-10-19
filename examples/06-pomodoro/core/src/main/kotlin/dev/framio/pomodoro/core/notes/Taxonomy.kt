package dev.framio.pomodoro.core.notes

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/** A reason tag ("phone"). Built-in tags have fixed ids; your own get `u-…` ids. Never reused for another meaning. */
@Serializable
@JvmInline
public value class TagId(public val value: String)

/** A glyph the app draws itself (no icon font, no Material). Unknown names from a newer version decode as [Dots]. */
@Serializable
public enum class TagIcon {
    @SerialName("phone") Phone,
    @SerialName("person") Person,
    @SerialName("noise") Noise,
    @SerialName("cup") Cup,
    @SerialName("moon") Moon,
    @SerialName("cloud") Cloud,
    @SerialName("bolt") Bolt,
    @SerialName("wrench") Wrench,
    @SerialName("dots") Dots,
    @SerialName("check") Check,
    @SerialName("door") Door,
    @SerialName("star") Star,
    @SerialName("book") Book,
    @SerialName("leaf") Leaf,
    @SerialName("heart") Heart,
    @SerialName("pin") Pin,
}

/** Where a tag is offered. [EndOnly] tags ("Step done") only make sense as a reason to stop, never as a distraction. */
@Serializable
public enum class TagScope {
    @SerialName("any") Any,
    @SerialName("end") EndOnly,
}

/**
 * One reason in the user-editable taxonomy.
 *
 * @param hue a slot 0..7 of the validated categorical chart palette (DESIGN-history §7), or [NEUTRAL] for gray.
 *   Colour is never the only signal: a chip always shows its icon and label too.
 * @param hidden hidden tags are no longer offered, but history that used them still shows their label
 */
@Serializable
public data class ReasonTag(
    @SerialName("id") val id: TagId,
    @SerialName("label") val label: String,
    @SerialName("icon") val icon: TagIcon = TagIcon.Dots,
    @SerialName("hue") val hue: Int = NEUTRAL,
    @SerialName("scope") val scope: TagScope = TagScope.Any,
    @SerialName("hidden") val hidden: Boolean = false,
    @SerialName("builtIn") val builtIn: Boolean = false,
) {
    public companion object {
        public const val NEUTRAL: Int = -1
    }
}

/**
 * The reason tags, in the order the chips appear. The order is the user's and stays put: chips never reshuffle by
 * usage, so a finger can learn where "Phone" is (DESIGN-history §3.1). Tags are renamed, reordered, hidden and added;
 * never deleted, because history keeps pointing at them.
 */
@Serializable
public data class Taxonomy(
    @SerialName("v") val version: Int = VERSION,
    @SerialName("tags") val tags: List<ReasonTag> = Defaults,
) {
    /** Tags offered for [scope] (an end-of-session sheet asks for [TagScope.EndOnly], which also gets the rest). */
    public fun offered(scope: TagScope): List<ReasonTag> = tags.filter { t ->
        !t.hidden && (t.scope == TagScope.Any || scope == TagScope.EndOnly)
    }

    public fun tag(id: TagId): ReasonTag? = tags.firstOrNull { it.id == id }

    /** The label to show for [id], even for a tag this taxonomy doesn't know (from an import or a newer version). */
    public fun label(id: TagId): String = tag(id)?.label ?: id.value.removePrefix("u-").replace('-', ' ')

    public fun rename(id: TagId, label: String): Taxonomy {
        val clean = cleanLabel(label) ?: return this
        return copy(tags = tags.map { if (it.id == id) it.copy(label = clean) else it })
    }

    public fun setHidden(id: TagId, hidden: Boolean): Taxonomy = copy(tags = tags.map { if (it.id == id) it.copy(hidden = hidden) else it })

    public fun restyle(id: TagId, icon: TagIcon? = null, hue: Int? = null): Taxonomy = copy(
        tags = tags.map {
            if (it.id != id) it else it.copy(icon = icon ?: it.icon, hue = hue?.let(::cleanHue) ?: it.hue)
        },
    )

    /** Moves [id] to [index] (clamped). */
    public fun move(id: TagId, index: Int): Taxonomy {
        val from = tags.indexOfFirst { it.id == id }
        if (from < 0) return this
        val list = tags.toMutableList()
        val tag = list.removeAt(from)
        list.add(index.coerceIn(0, list.size), tag)
        return copy(tags = list)
    }

    /**
     * Adds a tag of your own, before the built-in "Other" if it is visible. Returns null when the label is blank, a
     * visible tag already has that label (ignoring case), or the taxonomy is full. A hidden tag of that label is
     * shown again instead of duplicated.
     */
    public fun add(label: String, icon: TagIcon = TagIcon.Dots, hue: Int = ReasonTag.NEUTRAL): Pair<Taxonomy, TagId>? {
        val clean = cleanLabel(label) ?: return null
        val same = tags.firstOrNull { it.label.equals(clean, ignoreCase = true) }
        if (same != null) return if (same.hidden) setHidden(same.id, false) to same.id else null
        if (tags.size >= MAX_TAGS) return null
        val id = freshId(clean)
        val tag = ReasonTag(id, clean, icon, cleanHue(hue))
        val other = tags.indexOfFirst { it.id == OTHER && !it.hidden }
        val list = tags.toMutableList()
        if (other >= 0) list.add(other, tag) else list.add(tag)
        return copy(tags = list) to id
    }

    /**
     * Repairs what a file, an older version or a bad edit could leave: duplicate ids (first wins), blank or long
     * labels, hues out of range, too many tags, and missing built-ins (appended, so ids used by history resolve).
     */
    public fun normalized(): Taxonomy {
        val seen = HashSet<TagId>()
        val kept = tags.mapNotNull { t ->
            if (!seen.add(t.id)) return@mapNotNull null
            val builtIn = Defaults.firstOrNull { it.id == t.id }
            t.copy(
                label = cleanLabel(t.label) ?: builtIn?.label ?: t.id.value,
                hue = cleanHue(t.hue),
                builtIn = builtIn != null,
                scope = builtIn?.scope ?: t.scope,
            )
        }.take(MAX_TAGS)
        val missing = Defaults.filter { it.id !in seen }
        return Taxonomy(VERSION, kept + missing)
    }

    private fun freshId(label: String): TagId {
        val slug = label.lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")
            .trim('-').replace(Regex("-+"), "-").take(16).ifEmpty { "tag" }
        var id = TagId("u-$slug")
        var n = 2
        while (tags.any { it.id == id }) id = TagId("u-$slug-${n++}")
        return id
    }

    public companion object {
        public const val VERSION: Int = 1
        public const val MAX_TAGS: Int = 24
        public const val MAX_LABEL: Int = 24

        public val PHONE: TagId = TagId("phone")
        public val PERSON: TagId = TagId("person")
        public val NOISE: TagId = TagId("noise")
        public val HUNGER: TagId = TagId("hunger")
        public val TIRED: TagId = TagId("tired")
        public val WANDER: TagId = TagId("wander")
        public val URGENT: TagId = TagId("urgent")
        public val TECH: TagId = TagId("tech")
        public val OTHER: TagId = TagId("other")
        public val DONE: TagId = TagId("done")
        public val LEAVE: TagId = TagId("leave")

        /**
         * The defaults: the eight common pulls on attention, each on its own palette slot (in the palette's
         * validated order), then "Other" in neutral gray, then the two end-only reasons in gray.
         */
        public val Defaults: List<ReasonTag> = listOf(
            ReasonTag(PHONE, "Phone", TagIcon.Phone, 0, builtIn = true),
            ReasonTag(PERSON, "Person", TagIcon.Person, 1, builtIn = true),
            ReasonTag(NOISE, "Noise", TagIcon.Noise, 2, builtIn = true),
            ReasonTag(HUNGER, "Hunger/thirst", TagIcon.Cup, 3, builtIn = true),
            ReasonTag(TIRED, "Tired", TagIcon.Moon, 4, builtIn = true),
            ReasonTag(WANDER, "Wandering mind", TagIcon.Cloud, 5, builtIn = true),
            ReasonTag(URGENT, "Urgent task", TagIcon.Bolt, 6, builtIn = true),
            ReasonTag(TECH, "Tool/tech", TagIcon.Wrench, 7, builtIn = true),
            ReasonTag(OTHER, "Other", TagIcon.Dots, ReasonTag.NEUTRAL, builtIn = true),
            ReasonTag(DONE, "Step done", TagIcon.Check, ReasonTag.NEUTRAL, TagScope.EndOnly, builtIn = true),
            ReasonTag(LEAVE, "Had to leave", TagIcon.Door, ReasonTag.NEUTRAL, TagScope.EndOnly, builtIn = true),
        )

        /** The taxonomy file's text (`tags.json`). */
        public fun encode(t: Taxonomy): String = JournalCodec.json.encodeToString(serializer(), t)

        /** Normalized, or null for anything unreadable (the store then keeps the defaults and the bad file aside). */
        public fun decode(text: String): Taxonomy? = try {
            JournalCodec.json.decodeFromString(serializer(), text).normalized()
        } catch (_: kotlinx.serialization.SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

        internal fun cleanLabel(label: String): String? =
            label.trim().replace(Regex("\\s+"), " ").take(MAX_LABEL).trim().takeIf { it.isNotEmpty() }

        internal fun cleanHue(hue: Int): Int = if (hue in 0..7) hue else ReasonTag.NEUTRAL
    }
}
