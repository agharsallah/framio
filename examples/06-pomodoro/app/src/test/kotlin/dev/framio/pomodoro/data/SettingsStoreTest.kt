package dev.framio.pomodoro.data

import dev.framio.pomodoro.core.engine.Phase
import dev.framio.pomodoro.core.engine.Snapshot
import dev.framio.pomodoro.core.model.Presets
import dev.framio.pomodoro.core.model.Settings
import dev.framio.pomodoro.core.model.Theme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The DataStore-backed JSON stores: defaults, round trips, conflated writes, and a corrupt file copied aside. */
class SettingsStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    /** One store per scope; DataStore allows one active instance per file, so each use cancels its scope after. */
    private fun <T> withSettings(file: File, block: suspend (SettingsStore) -> T): T = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            block(SettingsStore(file, scope, Dispatchers.IO))
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test
    fun `no file gives the defaults`() {
        val settings = withSettings(File(tmp.root, "settings.json")) { it.load() }
        assertEquals(Settings(), settings)
    }

    @Test
    fun `a written value is read back by the next process`() {
        val file = File(tmp.root, "settings.json")
        val mine = Settings(theme = Theme.Forest, dailyTarget = 8, selectedMode = Presets.CLASSIC_ID)
        withSettings(file) { it.write(mine) }
        assertEquals(mine, withSettings(file) { it.load() })
    }

    @Test
    fun `offers are conflated and the latest one is stored`() {
        val file = File(tmp.root, "settings.json")
        withSettings(file) { store ->
            for (t in 1..20) store.offer(Settings(dailyTarget = t))
            withTimeout(5_000) { while (store.load().dailyTarget != 20) delay(10) }
        }
        assertEquals(20, withSettings(file) { it.load() }.dailyTarget)
    }

    @Test
    fun `a corrupt file is copied aside and the store starts from the defaults`() {
        val file = File(tmp.root, "settings.json")
        file.writeText("{\"theme\": \"ink\", \"modes\": [ {torn")
        val settings = withSettings(file) { it.load() }
        assertEquals(Settings(), settings)
        val aside = tmp.root.listFiles().orEmpty().filter { it.name.startsWith("settings.bad-") }
        assertEquals(1, aside.size)
        assertEquals("{\"theme\": \"ink\", \"modes\": [ {torn", aside.single().readText())
        // and the store works afterwards
        withSettings(file) { it.write(Settings(dailyTarget = 3)) }
        assertEquals(3, withSettings(file) { it.load() }.dailyTarget)
    }

    @Test
    fun `the snapshot store keeps a running focus phase`() {
        val file = File(tmp.root, "snapshot.json")
        val running = Snapshot(phase = Phase.Ready, completedToday = 2, lastResumeNote = "figures next")
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            SnapshotStore(file, scope, Dispatchers.IO).write(running)
            scope.coroutineContext.job.cancelAndJoin()
            val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            assertEquals(running, SnapshotStore(file, scope2, Dispatchers.IO).load())
            scope2.coroutineContext.job.cancelAndJoin()
        }
        assertTrue(file.readText().contains("figures next"))
    }
}
