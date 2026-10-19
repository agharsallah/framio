package dev.framio.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.framio.lab.core.Lab
import dev.framio.lab.core.LabHost
import dev.framio.lab.core.Status
import dev.framio.lab.core.ui.Hairline
import dev.framio.lab.core.ui.Ink
import dev.framio.lab.core.ui.InkDim
import dev.framio.lab.core.ui.InkFaint
import dev.framio.lab.core.ui.Surface0
import dev.framio.lab.core.ui.Surface1
import dev.framio.lab.core.ui.Surface2
import dev.framio.lab.core.ui.statusColor

/** Side rail with every lab, the selected lab on the right, and an optional full-screen overlay on top. */
@Composable
fun LabApp(host: LabHost, onExit: () -> Unit) {
    val lab = Labs.all.firstOrNull { it.id == host.selected } ?: Labs.all.first()
    Box(Modifier.fillMaxSize().background(Surface0)) {
        Row(Modifier.fillMaxSize()) {
            Rail(host, onExit)
            Box(Modifier.weight(1f).fillMaxHeight()) {
                // key: each lab gets fresh state and its DisposableEffects stop when you leave it
                key(lab.id) { lab.content() }
            }
        }
        host.overlay?.let { overlay -> Box(Modifier.fillMaxSize()) { overlay() } }
    }
}

@Composable
private fun Rail(host: LabHost, onExit: () -> Unit) {
    Column(
        Modifier
            .width(236.dp)
            .fillMaxHeight()
            .background(Surface1)
            .padding(top = 20.dp, bottom = 76.dp), // bottom: keep clear of the launcher's hot corner dot
    ) {
        Text(
            "HARDWARE LAB",
            Modifier.padding(start = 24.dp, bottom = 12.dp),
            style = MaterialTheme.typography.labelSmall.copy(color = InkFaint, letterSpacing = 3.sp),
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Labs.all.forEach { lab -> RailItem(lab, lab.id == host.selected, worst(host, lab)) { host.selected = lab.id } }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
        Text(
            "Exit",
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onExit)
                .padding(horizontal = 24.dp, vertical = 14.dp),
            style = MaterialTheme.typography.bodyLarge.copy(color = InkDim),
        )
    }
}

/** The most notable status the last self-test found for [lab], for the dot in the rail. */
private fun worst(host: LabHost, lab: Lab): Status? {
    val checks = host.selfTest.forLab(lab.id)
    if (checks.isEmpty()) return null
    return listOf(Status.FAIL, Status.WARN, Status.PASS, Status.ABSENT, Status.INFO, Status.MANUAL)
        .firstOrNull { s -> checks.any { it.status == s } }
}

@Composable
private fun RailItem(lab: Lab, selected: Boolean, status: Status?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Surface2 else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(lab.accent.copy(alpha = if (selected) 1f else 0.6f)))
        Text(
            lab.title,
            Modifier.weight(1f).padding(start = 14.dp),
            style = MaterialTheme.typography.bodyLarge.copy(color = if (selected) Ink else InkDim),
        )
        if (status != null) Box(Modifier.size(8.dp).clip(CircleShape).background(statusColor(status)))
    }
}
