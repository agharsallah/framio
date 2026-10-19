package dev.framio.meadow.worlds.music

import dev.framio.meadow.play.PlayScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** Waits until the narration (e.g. the activity's intro) has finished, so a prompt doesn't cut it off. */
internal suspend fun PlayScope.awaitQuiet() {
    delay(250)
    app.voice.speaking.first { !it }
}
