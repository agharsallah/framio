package dev.framio.hub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.text.BasicText
import dev.framio.hub.core.Probe
import java.time.ZonedDateTime

class HubActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val list = mutableListOf("a", "b")
        @Suppress("NewApi") val first = (list as java.util.List<String>).removeFirst()
        val p = ProcessBuilder("true").start()
        p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)
        setContent { BasicText(Probe.hello(ZonedDateTime.now()) + first) }
    }
}
