package dev.framio.lab.core.ui

import androidx.compose.runtime.Composable
import dev.framio.lab.core.LocalHost

/** The "Self-test" card at the bottom of each lab: runs that lab's probes and lists the results. */
@Composable
fun SelfTestSection(labId: String, note: String? = null) {
    val runner = LocalHost.current.selfTest
    val checks = runner.forLab(labId)
    Section(
        "Self-test",
        note = note ?: "Automated probes for this lab. Also logged as CHECK|… under tag FramioLab.",
        trailing = {
            ActionButton(if (runner.running) "Running…" else "Run", { runner.run(onlyLab = labId) }, enabled = !runner.running)
        },
    ) {
        if (checks.isEmpty()) Note("Not run yet.") else CheckRows(checks)
    }
}
