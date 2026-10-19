package dev.framio.pomodoro.data

import dev.framio.pomodoro.core.AMSTERDAM
import dev.framio.pomodoro.core.history.HistorySeed
import dev.framio.pomodoro.core.model.Outcome
import dev.framio.pomodoro.core.model.ParkedThought
import dev.framio.pomodoro.core.notes.Entry
import dev.framio.pomodoro.core.notes.EntryId
import dev.framio.pomodoro.core.notes.SessionAmend
import dev.framio.pomodoro.core.notes.Taxonomy
import dev.framio.pomodoro.core.record
import dev.framio.pomodoro.core.time.Moment
import dev.framio.pomodoro.core.wallAt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.time.Duration.Companion.minutes

/** The history repository on real files: lazy loading, the journal, the stats cache, export and seeding. */
class HistoryRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val a = record("2026-10-18T09:00")
    private val b = record("2026-10-17T10:00", outcome = Outcome.Partly)
    private val wall = wallAt("2026-10-18T20:00")

    private fun <T> withRepo(block: suspend (HistoryRepository, SessionLogFile) -> T): T = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val log = SessionLogFile(File(tmp.root, "sessions.jsonl"))
            val repo = HistoryRepository(
                sessions = log,
                journalFile = LineFile(File(tmp.root, "history.jsonl")),
                cacheFile = File(tmp.root, "stats-cache.json"),
                tagStore = TagStore(File(tmp.root, "tags.json"), scope, Dispatchers.IO),
                scope = scope,
                io = Dispatchers.IO,
                zone = { AMSTERDAM },
                wall = { wall },
            )
            withTimeout(10_000) { block(repo, log) }
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    private val d = Entry.Distraction(EntryId("d1"), a.id, 3.minutes, null, listOf(Taxonomy.PHONE))

    @Test
    fun `a write before loading only appends, and loading later sees it`() = withRepo { repo, log ->
        log.append(a)
        repo.put(d)
        repo.flush()
        assertNull(repo.history.value)
        assertEquals(listOf(d), repo.live.value[a.id])
        assertEquals(1, File(tmp.root, "history.jsonl").readLines().size)
        repo.need()
        val h = repo.history.first { it != null }!!
        assertEquals(listOf(d), h.index.session(a.id)?.entries)
    }

    @Test
    fun `edits, deletes, undo, amends and drops persist across a restart`() {
        withRepo { repo, log ->
            log.append(a)
            log.append(b)
            repo.need()
            repo.put(d)
            repo.put(d.copy(tags = listOf(Taxonomy.NOISE)))
            repo.amend(SessionAmend(a.id, rating = 5))
            repo.drop(b.id)
            repo.flush()
            val idx = repo.history.value!!.index
            assertEquals(listOf(Taxonomy.NOISE), (idx.session(a.id)?.entries?.single() as Entry.Distraction).tags)
            assertEquals(5, idx.session(a.id)?.record?.rating)
            assertNull(idx.session(b.id))
        }
        withRepo { repo, _ ->
            repo.need()
            val idx = repo.history.first { it != null }!!.index
            assertEquals(1, idx.size)
            assertEquals(5, idx.session(a.id)?.record?.rating)
            repo.delete(idx.session(a.id)!!.entries.single())
            repo.flush()
            assertTrue(repo.history.value!!.index.session(a.id)!!.entries.isEmpty())
        }
    }

    @Test
    fun `a logged session joins a loaded history and Insights' buckets`() = withRepo { repo, log ->
        repo.needStats()
        repo.flush()
        assertTrue(repo.stats.value!!.buckets.isEmpty())
        log.append(a)
        repo.onLogged(a)
        repo.flush()
        assertEquals(1, repo.stats.value!!.buckets.single().completed)
    }

    @Test
    fun `Insights opens from the cache while the files are unchanged, and rebuilds once they grow`() {
        withRepo { repo, log ->
            log.append(a)
            repo.put(d)
            repo.needStats()
            repo.flush()
            assertFalse(repo.stats.value!!.fromCache)
            repo.flush() // the cache write is queued after the rebuild
            assertTrue(File(tmp.root, "stats-cache.json").exists())
        }
        withRepo { repo, _ ->
            repo.needStats()
            repo.flush()
            val s = repo.stats.value!!
            assertTrue(s.fromCache)
            assertEquals(1, s.buckets.single().tags.single().count)
            assertNull(repo.history.value) // the log wasn't read
        }
        withRepo { repo, log ->
            log.append(b)
            repo.needStats()
            repo.flush()
            assertFalse(repo.stats.value!!.fromCache)
            assertEquals(2, repo.stats.value!!.buckets.size)
        }
    }

    @Test
    fun `parked thoughts from the engine are kept, and clearing marks them resolved`() = withRepo { repo, log ->
        log.append(a)
        val t = ParkedThought("a0b1", Moment("boot-1", 4_200, null))
        repo.parked(a.id, t, 3.minutes)
        repo.resolved(listOf(t))
        repo.need()
        repo.flush()
        val p = repo.history.value!!.index.session(a.id)!!.entries.single() as Entry.Parked
        assertEquals(EntryId.parked(t.at), p.id)
        assertTrue(p.resolved)
        assertEquals(3.minutes, p.at)
    }

    @Test
    fun `export writes the JSON and two CSVs`() = withRepo { repo, log ->
        log.append(a)
        repo.put(d)
        val files = repo.export(File(tmp.root, "export"), wall)
        assertEquals(listOf("pomodoro-20261018-2000.json", "pomodoro-20261018-2000-sessions.csv", "pomodoro-20261018-2000-entries.csv"), files.map { it.name })
        assertTrue(files[1].readText().contains(a.id.value))
        assertTrue(files[2].readText().contains("distraction"))
    }

    @Test
    fun `seeding adds marked history and removes exactly it again`() = withRepo { repo, log ->
        log.append(a)
        repo.put(d)
        repo.need()
        val n = repo.seed(30)
        assertTrue(n > 20)
        assertEquals(n + 1, repo.history.value!!.index.size)
        assertEquals(n, repo.seed(0))
        val idx = repo.history.value!!.index
        assertEquals(listOf(a.id), idx.sessions.map { it.id })
        assertEquals(listOf(d), idx.session(a.id)?.entries)
        assertTrue(log.lines().none { HistorySeed.PREFIX in it })
    }

    @Test
    fun `tags persist through the store`() {
        withRepo { repo, _ ->
            repo.tags.first() // loaded (defaults)
            repo.updateTags { it.rename(Taxonomy.PHONE, "Mobile") }
            assertEquals("Mobile", repo.tags.value.label(Taxonomy.PHONE))
            kotlinx.coroutines.delay(300) // conflated store write
        }
        withRepo { repo, _ ->
            assertEquals("Mobile", repo.tags.first { it.label(Taxonomy.PHONE) == "Mobile" }.label(Taxonomy.PHONE))
        }
    }
}
