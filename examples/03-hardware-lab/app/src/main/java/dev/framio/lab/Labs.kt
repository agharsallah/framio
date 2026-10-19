package dev.framio.lab

import dev.framio.lab.core.Lab
import dev.framio.lab.labs.background.BackgroundLab
import dev.framio.lab.labs.display.DisplayLab
import dev.framio.lab.labs.graphics.GraphicsLab
import dev.framio.lab.labs.input.InputLab
import dev.framio.lab.labs.media.MediaLab
import dev.framio.lab.labs.mic.MicLab
import dev.framio.lab.labs.missing.MissingLab
import dev.framio.lab.labs.network.NetworkLab
import dev.framio.lab.labs.overview.OverviewLab
import dev.framio.lab.labs.root.RootLab
import dev.framio.lab.labs.sensors.SensorsLab
import dev.framio.lab.labs.speaker.SpeakerLab
import dev.framio.lab.labs.storage.StorageLab
import dev.framio.lab.labs.system.SystemLab
import dev.framio.lab.labs.time.TimeLab
import dev.framio.lab.labs.touch.TouchLab

/** Every lab, in rail order. Add a lab: create `labs/<name>/<Name>Lab.kt` exposing a [Lab] and list it here. */
object Labs {
    val all: List<Lab> by lazy {
        listOf(
            OverviewLab.lab,
            SystemLab.lab,
            DisplayLab.lab,
            TouchLab.lab,
            SensorsLab.lab,
            SpeakerLab.lab,
            MicLab.lab,
            MediaLab.lab,
            GraphicsLab.lab,
            NetworkLab.lab,
            TimeLab.lab,
            BackgroundLab.lab,
            InputLab.lab,
            StorageLab.lab,
            MissingLab.lab,
            RootLab.lab,
        )
    }
}
