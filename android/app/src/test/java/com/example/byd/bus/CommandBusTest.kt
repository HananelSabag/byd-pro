package com.example.byd.bus

import com.example.byd.data.ApiError
import com.example.byd.data.CarCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The command bus on virtual time against a simulated server (old 2.7 and new v2 contract).
 * Every test runs the real pipeline; only the network, the car state and the clock are fake.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CommandBusTest {

    // ------------------------------------------------------------------ the old (2.7) server

    @Test
    fun legacyServerGetsOneSyncRequestAndConfirms() = runTest {
        val env = Env(this, v2 = false)
        val r = env.submit(CarCommand.LOCK)
        advanceUntilIdle()
        assertEquals(Outcome.CONFIRMED, env.final(r.id).outcome)
        assertEquals(listOf("lock ${r.id} sync"), env.server.calls)
        assertEquals(ServerKind.LEGACY, env.bus.server.kind)
        assertEquals(listOf(CarCommand.LOCK), env.hooks.followUps)
    }

    @Test
    fun legacyLostAnswerIsNeverResentAndResolvedByLooking() = runTest {
        val env = Env(this, v2 = false)
        env.server.failures += Failure.Lost()
        var reads = 0
        env.hooks.matcher = { reads++ >= 1 } // the car shows it on the second look
        val r = env.submit(CarCommand.UNLOCK)
        advanceUntilIdle()
        val done = env.final(r.id)
        assertEquals(Outcome.CONFIRMED, done.outcome)
        assertTrue(done.late)
        assertEquals(1, env.server.calls.size)
        assertTrue(env.hooks.reads >= 2)
    }

    @Test
    fun legacyLostAnswerThatTheCarDoesntShowEndsUnconfirmed() = runTest {
        val env = Env(this, v2 = false)
        env.server.failures += Failure.Lost()
        env.hooks.matcher = { false }
        val r = env.submit(CarCommand.TRUNK)
        advanceUntilIdle()
        assertEquals(Outcome.UNCONFIRMED, env.final(r.id).outcome)
        assertEquals(1, env.server.calls.size) // the tailgate is never toggled twice
        assertEquals(3, env.hooks.reads)
    }

    @Test
    fun legacyTimeoutIsCheckedNotResent() = runTest {
        val env = Env(this, v2 = false)
        env.server.replies += Wire(504, false, error = "timeout")
        env.hooks.matcher = { true }
        val r = env.submit(CarCommand.LOCK)
        advanceUntilIdle()
        assertEquals(Outcome.CONFIRMED, env.final(r.id).outcome)
        assertTrue(env.final(r.id).late)
        assertEquals(1, env.server.calls.size)
    }

    @Test
    fun legacyBusyIsRetriedAfterAPause() = runTest {
        val env = Env(this, v2 = false)
        env.server.replies += Wire(409, false, error = "busy")
        val r = env.submit(CarCommand.LOCK)
        advanceUntilIdle()
        assertEquals(Outcome.CONFIRMED, env.final(r.id).outcome)
        assertEquals(2, env.server.calls.size)
        assertTrue(env.server.callTimes[1] - env.server.callTimes[0] >= 3_000)
    }

    @Test
    fun legacyRejectionIsFinal() = runTest {
        val env = Env(this, v2 = false)
        env.server.replies += Wire(502, false, error = "rejected")
        val r = env.submit(CarCommand.LOCK)
        advanceUntilIdle()
        assertEquals(Outcome.REJECTED, env.final(r.id).outcome)
        assertEquals(ApiError.REJECTED, env.final(r.id).error)
        assertEquals(1, env.server.calls.size)
    }

    // ------------------------------------------------------------------ the new server

    @Test
    fun newServerJobIsFollowedThroughItsStages() = runTest {
        val env = Env(this, v2 = true)
        val stages = env.recordStages()
        val r = env.submit(CarCommand.LOCK)
        advanceUntilIdle()
        assertEquals(Outcome.CONFIRMED, env.final(r.id).outcome)
        assertEquals(listOf("lock ${r.id} async"), env.server.calls) // the request id is the record's id
        assertEquals(ServerKind.V2, env.bus.server.kind)
        val seen = stages.filter { it.first == r.id }.map { it.second }.distinct()
        assertEquals(listOf(Stage.QUEUED, Stage.WARMING, Stage.SENDING, Stage.WAITING_CAR, Stage.DONE), seen)
    }

    @Test
    fun newServerLostAnswerAsksForTheJobInsteadOfResending() = runTest {
        val env = Env(this, v2 = true)
        env.server.failures += Failure.Lost(arrived = true)
        val r = env.submit(CarCommand.UNLOCK)
        advanceUntilIdle()
        assertEquals(Outcome.CONFIRMED, env.final(r.id).outcome)
        assertEquals(1, env.server.calls.size)
        assertTrue(env.server.jobPolls > 0)
    }

    @Test
    fun newServerThatNeverGotItIsSentAgainUnderTheSameId() = runTest {
        val env = Env(this, v2 = true)
        env.server.failures += Failure.Lost(arrived = false)
        val r = env.submit(CarCommand.UNLOCK)
        advanceUntilIdle()
        assertEquals(Outcome.CONFIRMED, env.final(r.id).outcome)
        assertEquals(listOf("unlock ${r.id} async", "unlock ${r.id} async"), env.server.calls)
    }

    @Test
    fun restartedServerNeverRepeatsATailgateToggle() = runTest {
        val env = Env(this, v2 = true)
        env.server.failures += Failure.Lost(arrived = false, restart = true)
        env.hooks.matcher = { false }
        val r = env.submit(CarCommand.TRUNK)
        advanceUntilIdle()
        assertEquals(Outcome.UNCONFIRMED, env.final(r.id).outcome)
        assertEquals(1, env.server.calls.size)
    }

    @Test
    fun restartedServerGetsAnIdempotentCommandAgain() = runTest {
        val env = Env(this, v2 = true)
        env.server.failures += Failure.Lost(arrived = false, restart = true)
        val r = env.submit(CarCommand.LOCK)
        advanceUntilIdle()
        assertEquals(Outcome.CONFIRMED, env.final(r.id).outcome)
        assertEquals(2, env.server.calls.size)
    }

    @Test
    fun unconfirmedJobThatTheServerConfirmsLateEndsConfirmed() = runTest {
        val env = Env(this, v2 = true)
        env.server.timeline = { _, _ -> listOf(300L to "running", 800L to "sent", 3_000L to "unconfirmed", 12_000L to "confirmed_late") }
        val r = env.submit(CarCommand.QUICK_COOL)
        advanceUntilIdle()
        val done = env.final(r.id)
        assertEquals(Outcome.CONFIRMED, done.outcome)
        assertTrue(done.late)
    }

    @Test
    fun unconfirmedJobThatStaysUnconfirmedEndsUnconfirmed() = runTest {
        val env = Env(this, v2 = true)
        env.server.timeline = { _, _ -> listOf(300L to "running", 3_000L to "unconfirmed") }
        val r = env.submit(CarCommand.LOCK)
        advanceUntilIdle()
        assertEquals(Outcome.UNCONFIRMED, env.final(r.id).outcome)
        assertEquals(1, env.server.calls.size)
    }

    @Test
    fun jobNotSentIsRetriedUnderANewId() = runTest {
        val env = Env(this, v2 = true)
        env.server.timeline = { _, rid ->
            if (rid.endsWith(".2")) listOf(300L to "running", 2_000L to "confirmed") else listOf(500L to "not_sent")
        }
        env.server.jobError = "byd_unreachable"
        val r = env.submit(CarCommand.LOCK)
        advanceUntilIdle()
        assertEquals(Outcome.CONFIRMED, env.final(r.id).outcome)
        assertEquals(listOf("lock ${r.id} async", "lock ${r.id}.2 async"), env.server.calls)
    }

    @Test
    fun rejectedJobIsFinal() = runTest {
        val env = Env(this, v2 = true)
        env.server.timeline = { _, _ -> listOf(300L to "running", 2_000L to "rejected") }
        val r = env.submit(CarCommand.LOCK)
        advanceUntilIdle()
        assertEquals(Outcome.REJECTED, env.final(r.id).outcome)
        assertEquals(1, env.server.calls.size)
    }

    @Test
    fun failedJobThatWasSentIsNotRetried() = runTest {
        val env = Env(this, v2 = true)
        env.server.timeline = { _, _ -> listOf(300L to "running", 2_000L to "failed") }
        env.server.jobSent = "unknown"
        val r = env.submit(CarCommand.TRUNK)
        advanceUntilIdle()
        assertEquals(Outcome.FAILED, env.final(r.id).outcome)
        assertEquals(1, env.server.calls.size)
        assertFalse(Outcomes.of(env.final(r.id)).retry) // a tailgate that may have moved: no Retry button
    }

    @Test
    fun failedWithALostSendCanStillTurnConfirmedLate() = runTest {
        val env = Env(this, v2 = true)
        env.server.timeline = { _, _ -> listOf(300L to "running", 2_000L to "failed", 10_000L to "confirmed_late") }
        env.server.jobSent = "unknown"
        val r = env.submit(CarCommand.LOCATE)
        advanceUntilIdle()
        assertEquals(Outcome.CONFIRMED, env.final(r.id).outcome)
        assertTrue(env.final(r.id).late)
        assertEquals(1, env.server.calls.size) // the horn is never sent twice
    }

    @Test
    fun stillChargingAfterPauseIsASoftAnswer() = runTest {
        val env = Env(this, v2 = true)
        env.server.timeline = { _, _ -> listOf(300L to "running", 2_000L to "confirmed") }
        env.server.jobError = "still_charging"
        val r = env.submit(CarCommand.CHARGE_PAUSE)
        advanceUntilIdle()
        assertEquals(ApiError.STILL_CHARGING, env.final(r.id).error)
        assertEquals(Tone.WARN, Outcomes.of(env.final(r.id)).tone)
    }

    @Test
    fun serverStillLoggingInIsWaitedFor() = runTest {
        val env = Env(this, v2 = true)
        env.server.replies += Wire(503, false, error = "warming_up", retryAfterS = 2, rev = 0, boot = "boot-1")
        val r = env.submit(CarCommand.LOCK)
        advanceUntilIdle()
        assertEquals(Outcome.CONFIRMED, env.final(r.id).outcome)
        assertEquals(2, env.server.calls.size)
    }

    @Test
    fun aPromptCommandIsNotRetriedPastItsMoment() = runTest {
        val env = Env(this, v2 = true)
        env.server.timeline = { _, _ -> listOf(500L to "not_sent") }
        env.server.jobError = "busy" // waited its 20 s in the server's queue
        val r = env.submit(CarCommand.TRUNK, tappedAt = env.clock.wall() - 44_000)
        advanceUntilIdle()
        assertEquals(Outcome.NOT_SENT, env.final(r.id).outcome)
        assertEquals(ApiError.BUSY, env.final(r.id).error)
        assertEquals(1, env.server.calls.size)
    }

    @Test
    fun serverQueueFullWaitsForRetryAfter() = runTest {
        val env = Env(this, v2 = true)
        env.server.replies += Wire(429, false, error = "queue_full", retryAfterS = 5, rev = 1, boot = "boot-1")
        val r = env.submit(CarCommand.LOCK)
        advanceUntilIdle()
        assertEquals(Outcome.CONFIRMED, env.final(r.id).outcome)
        assertEquals(2, env.server.calls.size)
        assertTrue(env.server.callTimes[1] - env.server.callTimes[0] >= 5_000)
        assertTrue(env.server.calls[1].startsWith("lock ${r.id}.2")) // the 429 is final for the first id
    }

    // ------------------------------------------------------------------ queue rules

    @Test
    fun oneCommandAtATime() = runTest {
        val env = Env(this, v2 = true)
        val unlock = env.submit(CarCommand.UNLOCK)
        val trunk = env.submit(CarCommand.TRUNK, source = Source.WATCH)
        advanceUntilIdle()
        assertEquals(Outcome.CONFIRMED, env.final(unlock.id).outcome)
        assertEquals(Outcome.CONFIRMED, env.final(trunk.id).outcome)
        // The tailgate went out only after the unlock's job was final (car time 3 s).
        assertTrue(env.server.callTimes[1] - env.server.callTimes[0] >= env.server.carMs)
    }

    @Test
    fun oneCommandAtATimeOnTheOldServerToo() = runTest {
        val env = Env(this, v2 = false)
        env.submit(CarCommand.UNLOCK)
        env.submit(CarCommand.TRUNK)
        env.submit(CarCommand.LOCATE)
        advanceUntilIdle()
        assertEquals(3, env.server.calls.size)
        assertEquals(1, env.server.maxInFlight)
    }

    @Test
    fun repeatTapJoinsInsteadOfResending() = runTest {
        val env = Env(this, v2 = true)
        val first = env.submit(CarCommand.LOCK)
        val second = env.submit(CarCommand.LOCK, source = Source.WATCH, id = "watch-tap")
        assertEquals(first.id, second.id)
        assertTrue("watch-tap" in second.joined)
        assertEquals(first.id, env.bus.record("watch-tap")?.id)
        advanceUntilIdle()
        assertEquals(1, env.server.calls.size)
        assertEquals(setOf(Source.APP, Source.WATCH), env.final(first.id).sources)
    }

    @Test
    fun sameIdTwiceIsTheSameCommand() = runTest {
        val env = Env(this, v2 = true)
        val first = env.submit(CarCommand.UNLOCK, source = Source.WATCH, id = "abc")
        val again = env.submit(CarCommand.UNLOCK, source = Source.WATCH, id = "abc") // the watch resent its message
        assertEquals(first.id, again.id)
        advanceUntilIdle()
        assertEquals(1, env.server.calls.size)
    }

    @Test
    fun newerTapReplacesAQueuedOneOfTheSameControl() = runTest {
        val env = Env(this, v2 = true)
        val cool = env.submit(CarCommand.QUICK_COOL)
        runCurrent() // on its way
        val off = env.submit(CarCommand.CLIMATE_OFF)
        val heat = env.submit(CarCommand.QUICK_HEAT)
        advanceUntilIdle()
        assertEquals(Outcome.CONFIRMED, env.final(cool.id).outcome)
        assertEquals(Outcome.SUPERSEDED, env.final(off.id).outcome)
        assertEquals(Outcome.CONFIRMED, env.final(heat.id).outcome)
        assertFalse(env.server.calls.any { it.startsWith("ac-off") })
    }

    @Test
    fun fullQueueSaysNotSent() = runTest {
        val env = Env(this, v2 = true)
        env.submit(CarCommand.LOCK)
        runCurrent()
        env.submit(CarCommand.TRUNK)
        env.submit(CarCommand.LOCATE)
        env.submit(CarCommand.FLASH)
        val fourth = env.submit(CarCommand.WINDOWS_CLOSE)
        assertEquals(Outcome.NOT_SENT, fourth.outcome)
        assertEquals(ApiError.QUEUE_FULL, fourth.error)
        advanceUntilIdle()
        assertEquals(4, env.server.calls.size)
    }

    @Test
    fun lateTailgateIsDroppedButALateLockStillGoes() = runTest {
        val env = Env(this, v2 = true)
        val tapped = env.clock.wall() - 60_000
        val trunk = env.submit(CarCommand.TRUNK, tappedAt = tapped)
        val lock = env.submit(CarCommand.LOCK, tappedAt = tapped)
        advanceUntilIdle()
        assertEquals(Outcome.EXPIRED, env.final(trunk.id).outcome)
        assertEquals(Outcome.CONFIRMED, env.final(lock.id).outcome)
        assertEquals(listOf("lock ${lock.id} async"), env.server.calls)
    }

    // ------------------------------------------------------------------ network failures and warm-up

    @Test
    fun notSentIsRetriedAndThenGoesThrough() = runTest {
        val env = Env(this, v2 = true)
        env.server.failures += Failure.NotSent
        env.server.failures += Failure.NotSent
        val r = env.submit(CarCommand.TRUNK) // safe to resend: it provably never went out
        advanceUntilIdle()
        val done = env.final(r.id)
        assertEquals(Outcome.CONFIRMED, done.outcome)
        assertEquals(3, done.attempts)
        assertEquals(3, env.server.calls.size)
    }

    @Test
    fun notSentGivesUpAfterTwoRetries() = runTest {
        val env = Env(this, v2 = false)
        repeat(3) { env.server.failures += Failure.NotSent }
        val r = env.submit(CarCommand.LOCK)
        advanceUntilIdle()
        assertEquals(Outcome.NOT_SENT, env.final(r.id).outcome)
        assertEquals(ApiError.NETWORK, env.final(r.id).error)
        assertEquals(3, env.server.calls.size)
    }

    @Test
    fun sleepingServerIsWokenBeforeTheCommand() = runTest {
        val env = Env(this, v2 = true)
        env.server.healthLost = 2 // Render still starting
        val r = env.submit(CarCommand.LOCK)
        advanceUntilIdle()
        assertEquals(Outcome.CONFIRMED, env.final(r.id).outcome)
        assertEquals(3, env.server.healthCalls)
        assertEquals(1, env.server.calls.size)
    }

    @Test
    fun unreachableServerFailsFastWithoutSending() = runTest {
        val env = Env(this, v2 = true)
        env.server.healthNotSent = 99 // no network
        val r = env.submit(CarCommand.LOCK)
        advanceUntilIdle()
        val done = env.final(r.id)
        assertEquals(Outcome.NOT_SENT, done.outcome)
        assertEquals(ApiError.NETWORK, done.error)
        assertTrue(env.server.calls.isEmpty())
        assertTrue((done.finishedAt ?: 0) - done.createdAt < 15_000)
    }

    @Test
    fun recentlySeenServerIsNotWokenAgain() = runTest {
        val env = Env(this, v2 = true)
        env.bus.noteReply(Wire(200, true, rev = 7, boot = "boot-1"))
        env.submit(CarCommand.LOCK)
        advanceUntilIdle()
        assertEquals(0, env.server.healthCalls)
    }

    // ------------------------------------------------------------------ persistence and lifecycle

    @Test
    fun commandsCutOffByTheAppsDeathAreSettledOnStart() = runTest {
        val server = SimServer(this).apply { v2 = true }
        val now = BASE
        server.register("job-1", "unlock", created = -10_000) // the server finished it meanwhile
        val store = MemoryStore(
            listOf(
                stored("queued-1", CarCommand.UNLOCK, Stage.QUEUED, now),
                stored("sent-legacy", CarCommand.LOCK, Stage.SENDING, now, expect = "locked" to true),
                stored("job-1", CarCommand.UNLOCK, Stage.WAITING_CAR, now, job = true),
            ),
        )
        val env = Env(this, v2 = true, server = server, store = store)
        env.hooks.matcher = { it.expectKey == "locked" }
        advanceUntilIdle()
        assertEquals(Outcome.NOT_SENT, env.final("queued-1").outcome)
        assertEquals(ApiError.INTERRUPTED, env.final("queued-1").error)
        assertEquals(Outcome.CONFIRMED, env.final("sent-legacy").outcome)
        assertEquals(Outcome.CONFIRMED, env.final("job-1").outcome)
        assertTrue(server.calls.isEmpty()) // nothing is sent again after a restart
    }

    @Test
    fun everyStepIsPersisted() = runTest {
        val env = Env(this, v2 = true)
        val r = env.submit(CarCommand.LOCK)
        advanceUntilIdle()
        assertEquals(Outcome.CONFIRMED, env.store.saved.single { it.id == r.id }.outcome)
        assertTrue(env.store.saves > 3)
    }

    @Test
    fun idleOnlyAfterTheFollowUpLook() = runTest {
        val env = Env(this, v2 = true)
        env.hooks.followUpMs = 8_000
        assertTrue(env.bus.idle.value)
        val r = env.submit(CarCommand.QUICK_COOL)
        assertFalse(env.bus.idle.value)
        while (env.bus.record(r.id)?.ok != true) advanceTimeBy(100)
        assertFalse(env.bus.idle.value) // confirmed, but the look at the car is still running
        advanceTimeBy(7_500)
        runCurrent()
        assertFalse(env.bus.idle.value)
        advanceUntilIdle()
        assertTrue(env.bus.idle.value)
    }

    // ------------------------------------------------------------------ fakes

    private class Env(
        val test: TestScope,
        v2: Boolean,
        val server: SimServer = SimServer(test),
        val store: MemoryStore = MemoryStore(),
    ) {
        val hooks = FakeHooks()
        val logs = mutableListOf<String>()
        val clock = object : BusClock {
            override fun wall() = BASE + test.testScheduler.currentTime
            override fun mono() = test.testScheduler.currentTime
        }
        // Its own scope on the test's virtual clock (not backgroundScope: advanceUntilIdle must run the bus).
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler))
        val bus = CommandBus(scope, server, hooks, store, clock, { id, m -> logs += "${id?.take(8)} $m" })

        init {
            server.v2 = v2
            bus.start()
        }

        fun submit(
            command: CarCommand,
            source: Source = Source.APP,
            id: String = newRequestId(),
            tappedAt: Long? = null,
            params: Map<String, String> = emptyMap(),
        ) = bus.submit(CommandRequest(command, params, source, id, tappedAt))

        fun final(id: String): CommandRecord {
            val r = bus.record(id)
            assertNotNull("no record $id", r)
            assertTrue("$id not final: ${r!!.stage}\n${logs.joinToString("\n")}", r.terminal)
            return r
        }

        fun recordStages(): MutableList<Pair<String, Stage>> {
            val seen = mutableListOf<Pair<String, Stage>>()
            test.backgroundScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                bus.records.collect { list -> list.forEach { r -> if (seen.lastOrNull { it.first == r.id }?.second != r.stage) seen += r.id to r.stage } }
            }
            return seen
        }
    }

    private class FakeHooks : BusHooks {
        var matcher: (CommandRecord) -> Boolean? = { null }
        var reads = 0
        var followUpMs = 0L
        val followUps = mutableListOf<CarCommand>()
        override fun absorb(wire: Wire) = Unit
        override suspend fun readStatus(fresh: Boolean): Boolean {
            reads++
            delay(500)
            return true
        }
        override fun matches(record: CommandRecord): Boolean? = matcher(record)
        override suspend fun followUp(record: CommandRecord) {
            followUps += record.command
            delay(followUpMs)
        }
    }

    private class MemoryStore(initial: List<CommandRecord> = emptyList()) : RecordStore {
        var saved: List<CommandRecord> = initial
        var saves = 0
        override fun load() = saved
        override fun save(records: List<CommandRecord>) {
            saved = records
            saves++
        }
    }

    sealed interface Failure {
        data object NotSent : Failure

        /** The answer is lost; [arrived]: the server got the request (and runs it); [restart]: it restarted meanwhile. */
        data class Lost(val arrived: Boolean = false, val restart: Boolean = false) : Failure
    }

    /** A server on virtual time: v2 (jobs, ids, rev) or the 2.7 one (one synchronous request). */
    class SimServer(private val test: TestScope) : CarTransport {
        var v2 = true
        var boot = "boot-1"
        var carMs = 3_000L
        var timeline: (path: String, rid: String) -> List<Pair<Long, String>> =
            { _, _ -> listOf(300L to "running", 800L to "sent", carMs to "confirmed") }
        var jobError: String? = null
        var jobSent: String = "true"
        val failures = ArrayDeque<Failure>()
        val replies = ArrayDeque<Wire>()
        var healthLost = 0
        var healthNotSent = 0
        var healthCalls = 0
        val calls = mutableListOf<String>()
        val callTimes = mutableListOf<Long>()
        var jobPolls = 0
        var inFlight = 0
        var maxInFlight = 0
        private var rev = 0L
        private val jobs = HashMap<String, Pair<String, Long>>() // rid -> (path, created)

        private val now get() = test.testScheduler.currentTime

        fun register(rid: String, path: String, created: Long = now) {
            jobs[rid] = path to created
        }

        override suspend fun health(): Health {
            healthCalls++
            delay(100)
            if (healthNotSent > 0) {
                healthNotSent--
                throw NotSentException("connect failed")
            }
            if (healthLost > 0) {
                healthLost--
                delay(20_000)
                throw LostReplyException("timeout")
            }
            return if (v2) Health(true, boot) else Health(false)
        }

        override suspend fun command(path: String, query: Map<String, String>, requestId: String, async: Boolean): Wire {
            calls += "$path $requestId ${if (async) "async" else "sync"}"
            callTimes += now
            when (val f = failures.removeFirstOrNull()) {
                Failure.NotSent -> {
                    delay(100)
                    throw NotSentException("connect failed")
                }
                is Failure.Lost -> {
                    if (f.arrived && v2) register(requestId, path)
                    if (f.restart) {
                        boot = "boot-2"
                        jobs.clear()
                    }
                    delay(25_000)
                    throw LostReplyException("timeout")
                }
                null -> Unit
            }
            replies.removeFirstOrNull()?.let {
                delay(200)
                return it
            }
            if (v2 && async) {
                delay(150)
                if (requestId !in jobs) register(requestId, path)
                return Wire(202, true, requestId = requestId, job = view(requestId), rev = ++rev, boot = boot)
            }
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            try {
                delay(carMs)
            } finally {
                inFlight--
            }
            return Wire(200, true, rev = if (v2) ++rev else null, boot = if (v2) boot else null)
        }

        override suspend fun job(requestId: String): Wire {
            jobPolls++
            delay(50)
            if (requestId !in jobs) return Wire(404, false, error = "unknown_job", rev = ++rev, boot = boot)
            return Wire(200, true, job = view(requestId), rev = ++rev, boot = boot)
        }

        private fun view(rid: String): ServerJob {
            val (path, created) = jobs.getValue(rid)
            val age = now - created
            val word = timeline(path, rid).lastOrNull { it.first <= age }?.second ?: "queued"
            val final = word !in setOf("queued", "running", "sent")
            return if (final) ServerJob(rid, "done", outcome = word, error = jobError, sent = if (word == "not_sent") "false" else jobSent)
            else ServerJob(rid, word)
        }
    }

    private companion object {
        const val BASE = 1_800_000_000_000L

        fun stored(id: String, command: CarCommand, stage: Stage, now: Long, job: Boolean = false, expect: Pair<String, Boolean>? = null) =
            CommandRecord(
                id = id, command = command, params = emptyMap(), source = Source.WIDGET, stage = stage,
                createdAt = now - 5_000, tappedAt = now - 5_000, updatedAt = now - 2_000, jobAccepted = job,
                expectKey = expect?.first, expectValue = expect?.second,
            )
    }
}
