package dev.framio.pomodoro.ui.why

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.unit.dp
import dev.framio.pomodoro.R
import dev.framio.pomodoro.ui.LocalLandscape
import dev.framio.pomodoro.ui.components.QuietButton
import dev.framio.pomodoro.ui.components.QuietText
import dev.framio.pomodoro.ui.theme.QuietTheme

/**
 * How strong the evidence behind a feature is (PLAN §3.2's S / M / W). Shown as words, never only as a colour.
 */
enum class Strength(@param:StringRes val label: Int) {
    Strong(R.string.why_strong),
    ModerateStrong(R.string.why_moderate_strong),
    Moderate(R.string.why_moderate),
    ModerateWeak(R.string.why_moderate_weak),
    Weak(R.string.why_weak),
}

/**
 * One sentence of evidence for a feature, its strength and its sources: only the citations of PLAN §3, worded no
 * stronger than the studies (principle 4 of the plan's goals: the app never claims more than they show). [note] is an
 * honest qualifier ("helps how you feel more than output").
 */
enum class WhyTopic(
    @param:StringRes val title: Int,
    @param:StringRes val evidence: Int,
    val strength: Strength,
    @param:StringRes val source: Int,
    @param:StringRes val note: Int? = null,
) {
    BreakAway(R.string.why_break_title, R.string.why_break_evidence, Strength.Moderate, R.string.why_break_source, R.string.why_break_note),
    Nature(R.string.why_nature_title, R.string.why_nature_evidence, Strength.ModerateWeak, R.string.why_nature_source),
    Batching(R.string.why_batching_title, R.string.why_batching_evidence, Strength.Moderate, R.string.why_batching_source),
    Intention(R.string.why_intention_title, R.string.why_intention_evidence, Strength.Strong, R.string.why_intention_source),
    Phone(R.string.why_phone_title, R.string.why_phone_evidence, Strength.Moderate, R.string.why_phone_source),
    StillScreen(R.string.why_still_title, R.string.why_still_evidence, Strength.Moderate, R.string.why_still_source),
    FlowTiers(R.string.why_tiers_title, R.string.why_tiers_evidence, Strength.Weak, R.string.why_tiers_source, R.string.why_tiers_note),
    Sound(R.string.why_sound_title, R.string.why_sound_evidence, Strength.ModerateStrong, R.string.why_sound_source, R.string.why_sound_note),
    EveningLight(R.string.why_evening_title, R.string.why_evening_evidence, Strength.Moderate, R.string.why_evening_source, R.string.why_evening_note),
}

/** What each (i) opens. An enum, so the open sheet survives rotation (rememberSaveable). */
enum class Why(val topics: List<WhyTopic>) {
    /** The break prompt (and its landscape). */
    Break(listOf(WhyTopic.BreakAway, WhyTopic.Nature)),

    /** The long break's "check messages now". */
    LongBreak(listOf(WhyTopic.Batching, WhyTopic.BreakAway)),

    /** The ritual's intention and checklist. */
    Ritual(listOf(WhyTopic.Intention, WhyTopic.Phone)),

    /** Deep's Calm / screen-off display. */
    Display(listOf(WhyTopic.StillScreen)),

    /** Flow's break tiers. */
    Tiers(listOf(WhyTopic.FlowTiers)),

    /** Soundscapes (PLAN §3.2 row 11). */
    Sound(listOf(WhyTopic.Sound)),

    /** Evening amber (row 13). */
    Evening(listOf(WhyTopic.EveningLight)),

    /** Settings → About: every topic (the sheet scrolls). */
    All(WhyTopic.entries.toList()),
}

/**
 * The (i) that opens a Why? sheet: a small circled "i", inside a full 72 px target. Quiet: ink-dim, no fill.
 */
@Composable
fun InfoButton(onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    val ink = if (color == Color.Unspecified) QuietTheme.colors.inkDim else color
    val description = stringResource(R.string.why_open)
    Box(
        modifier
            .size(QuietTheme.Target)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick { onClick(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(36.dp).border(2.dp, ink, CircleShape), contentAlignment = Alignment.Center) {
            QuietText("i", QuietTheme.type.label, color = ink, maxLines = 1)
        }
    }
}

/**
 * The Why? sheet (PLAN §3.3): for each topic, one sentence of evidence, its strength in words, and where it comes
 * from. Over a scrim that closes it on a tap; Close is a big target at the bottom right (the hot corner stays free).
 */
@Composable
fun WhySheet(why: Why, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val colors = QuietTheme.colors
    val landscape = LocalLandscape.current
    Box(
        modifier
            .fillMaxSize()
            .background(colors.background.copy(alpha = 0.88f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(if (landscape) 1120.dp else 704.dp)
                .heightIn(max = if (landscape) 760.dp else 1200.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(colors.surface)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {} // taps on the card stay
                .padding(horizontal = 44.dp, vertical = 36.dp),
        ) {
            QuietText(stringResource(R.string.why_title), QuietTheme.type.label, color = colors.inkDim, maxLines = 1)
            Spacer(Modifier.height(QuietTheme.spacing.s))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                why.topics.forEachIndexed { i, topic ->
                    if (i > 0) Spacer(Modifier.height(QuietTheme.spacing.m))
                    Topic(topic)
                }
            }
            Spacer(Modifier.height(QuietTheme.spacing.m))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                QuietButton(stringResource(R.string.close), onClose, modifier = Modifier.width(200.dp))
            }
        }
    }
}

@Composable
private fun Topic(topic: WhyTopic, modifier: Modifier = Modifier) {
    val colors = QuietTheme.colors
    Column(modifier) {
        QuietText(stringResource(topic.title), QuietTheme.type.title, maxLines = 2)
        Spacer(Modifier.height(10.dp))
        QuietText(stringResource(topic.evidence), QuietTheme.type.body, maxLines = 4)
        Spacer(Modifier.height(12.dp))
        val strength = stringResource(topic.strength.label)
        val line = topic.note?.let { stringResource(R.string.why_strength_note, strength, stringResource(it)) } ?: strength
        Row(verticalAlignment = Alignment.CenterVertically) {
            StrengthMeter(topic.strength)
            Spacer(Modifier.width(14.dp))
            QuietText(line, QuietTheme.type.label, color = colors.ink, maxLines = 2)
        }
        Spacer(Modifier.height(6.dp))
        QuietText(stringResource(topic.source), QuietTheme.type.caption, color = colors.inkDim, maxLines = 2)
    }
}

/** Three small bars, filled by strength (the label next to it says it in words). */
@Composable
private fun StrengthMeter(strength: Strength, modifier: Modifier = Modifier) {
    val colors = QuietTheme.colors
    val filled = when (strength) {
        Strength.Strong -> 3
        Strength.ModerateStrong -> 3
        Strength.Moderate -> 2
        Strength.ModerateWeak -> 2
        Strength.Weak -> 1
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.Bottom) {
        for (i in 1..3) {
            Box(
                Modifier
                    .size(10.dp, (10 + 7 * i).dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (i <= filled) colors.ink else colors.line),
            )
        }
    }
}
