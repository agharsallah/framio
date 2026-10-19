package dev.framio.pomodoro.ui.ready

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.framio.pomodoro.R
import dev.framio.pomodoro.di.EnginePort
import dev.framio.pomodoro.ui.components.QuietButton
import dev.framio.pomodoro.ui.components.QuietText
import dev.framio.pomodoro.ui.components.QuietTextButton
import dev.framio.pomodoro.ui.components.TodayProgress
import dev.framio.pomodoro.ui.theme.QuietTheme

@Composable
fun ReadyRoute(
    port: EnginePort,
    modifier: Modifier = Modifier,
    vm: ReadyViewModel = viewModel(factory = ReadyViewModel.factory(port)),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    ReadyScreen(ui, vm::start, vm::home, modifier)
}

/**
 * "Ready?": nothing starts unseen; one big tap begins the next block with the same intention. Gentle start asks
 * "Another 10?" in warm words, and makes stopping here an equal, kind choice (never "you gave up").
 */
@Composable
fun ReadyScreen(ui: ReadyUi, onStart: () -> Unit, onHome: () -> Unit, modifier: Modifier = Modifier) {
    val colors = QuietTheme.colors
    val accent = colors.accent(ui.accent)
    Box(modifier.fillMaxSize()) {
        TodayProgress(ui.done, ui.target, accent, Modifier.align(Alignment.TopStart).padding(start = QuietTheme.spacing.gutter, top = 44.dp))
        QuietTextButton(stringResource(R.string.home), onHome, Modifier.align(Alignment.TopEnd).padding(top = 28.dp, end = 40.dp))
        Column(Modifier.align(Alignment.Center).padding(horizontal = QuietTheme.spacing.gutter), horizontalAlignment = Alignment.CenterHorizontally) {
            QuietText(stringResource(if (ui.warm) R.string.gentle_ready_title else R.string.ready_title), QuietTheme.type.display, align = TextAlign.Center, maxLines = 2)
            if (ui.warm) {
                Spacer(Modifier.height(QuietTheme.spacing.s))
                QuietText(stringResource(R.string.gentle_ready_detail), QuietTheme.type.title, color = colors.inkDim, align = TextAlign.Center, maxLines = 2)
            } else if (ui.intention != null) {
                Spacer(Modifier.height(QuietTheme.spacing.s))
                QuietText(ui.intention, QuietTheme.type.title, color = colors.inkDim, align = TextAlign.Center, maxLines = 1)
            }
            Spacer(Modifier.height(QuietTheme.spacing.xl))
            QuietButton(
                stringResource(if (ui.warm) R.string.gentle_another else R.string.ready_start),
                onStart,
                fill = accent,
                big = true,
                modifier = Modifier.width(460.dp),
            )
            Spacer(Modifier.height(QuietTheme.spacing.m))
            if (ui.warm) {
                QuietTextButton(stringResource(R.string.gentle_enough), onHome, color = colors.ink)
            } else {
                QuietText(
                    if (ui.nextMinutes != null) pluralStringResource(R.plurals.ready_next, ui.nextMinutes, ui.nextMinutes) else stringResource(R.string.ready_next_flow),
                    QuietTheme.type.body,
                    color = colors.inkDim,
                    maxLines = 1,
                )
            }
        }
    }
}
