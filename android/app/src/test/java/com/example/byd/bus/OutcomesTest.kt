package com.example.byd.bus

import com.example.byd.R
import com.example.byd.data.ApiError
import com.example.byd.data.CarCommand
import com.example.byd.system.WearSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one outcome table every surface reads, the log redaction and the watch message format. */
class OutcomesTest {
    private fun record(command: CarCommand = CarCommand.LOCK, stage: Stage = Stage.DONE, outcome: Outcome? = null, error: ApiError? = null) =
        CommandRecord(
            id = "id", command = command, params = emptyMap(), source = Source.APP, stage = stage, outcome = outcome,
            error = error, createdAt = 0, tappedAt = 0, updatedAt = 0, finishedAt = 1_000,
        )

    @Test
    fun everyStageAndOutcomeHasALook() {
        Stage.entries.filter { it != Stage.DONE }.forEach { stage ->
            val d = Outcomes.of(record(stage = stage))
            assertEquals(Tone.BUSY, d.tone)
            assertEquals(Feel.NONE, d.feel)
        }
        Outcome.entries.forEach { outcome ->
            ApiError.entries.forEach { error -> Outcomes.of(record(outcome = outcome, error = error)) } // never throws
            Outcomes.of(record(outcome = outcome))
        }
    }

    @Test
    fun busyStagesSayWhatIsHappening() {
        assertEquals(R.string.st_waking_server, Outcomes.of(record(stage = Stage.WARMING)).label)
        assertEquals(R.string.sending, Outcomes.of(record(stage = Stage.SENDING)).label)
        assertEquals(R.string.st_waiting_car, Outcomes.of(record(stage = Stage.WAITING_CAR)).label)
        assertEquals(R.string.st_checking, Outcomes.of(record(stage = Stage.CHECKING)).label)
    }

    @Test
    fun answersLookAndFeelTheSameEverywhere() {
        Outcomes.of(record(outcome = Outcome.CONFIRMED)).run {
            assertEquals(Tone.OK, tone); assertEquals(Feel.SUCCESS, feel); assertEquals(CarCommand.LOCK.done, message); assertFalse(retry)
        }
        Outcomes.of(record(outcome = Outcome.UNCONFIRMED, error = ApiError.NOT_CONFIRMED)).run {
            assertEquals(Tone.WARN, tone); assertEquals(Feel.SOFT, feel); assertFalse(retry) // may have happened: no Retry
        }
        Outcomes.of(record(outcome = Outcome.NOT_SENT, error = ApiError.NETWORK)).run {
            assertEquals(Tone.ERR, tone); assertEquals(Feel.FAILURE, feel); assertTrue(retry)
        }
        Outcomes.of(record(outcome = Outcome.NOT_SENT, error = ApiError.CLIMATE_IS_OFF)).run {
            assertEquals(Tone.WARN, tone); assertFalse(retry)
        }
        Outcomes.of(record(outcome = Outcome.REJECTED, error = ApiError.STILL_CHARGING)).run { assertEquals(Tone.WARN, tone) }
        Outcomes.of(record(outcome = Outcome.EXPIRED, error = ApiError.TOO_LATE)).run { assertEquals(Tone.ERR, tone) }
        Outcomes.of(record(outcome = Outcome.SUPERSEDED)).run { assertEquals(Tone.IDLE, tone); assertEquals(Feel.NONE, feel) }
    }

    @Test
    fun aToggleThatMayHaveRunIsNotOfferedAgain() {
        assertFalse(Outcomes.of(record(CarCommand.TRUNK, outcome = Outcome.FAILED, error = ApiError.SERVER)).retry)
        assertTrue(Outcomes.of(record(CarCommand.LOCK, outcome = Outcome.FAILED, error = ApiError.SERVER)).retry)
        assertTrue(Outcomes.of(record(CarCommand.TRUNK, outcome = Outcome.NOT_SENT, error = ApiError.NETWORK)).retry)
    }

    @Test
    fun answersShowForAMomentOnly() {
        val done = record(outcome = Outcome.CONFIRMED)
        assertTrue(Outcomes.showing(done, 1_000 + Outcomes.SHOW_MS - 1))
        assertFalse(Outcomes.showing(done, 1_000 + Outcomes.SHOW_MS))
        assertTrue(Outcomes.showing(record(stage = Stage.SENDING), Long.MAX_VALUE)) // busy always shows
        assertFalse(Outcomes.showing(record(outcome = Outcome.SUPERSEDED), 1_000))
    }

    @Test
    fun logsNeverCarrySecretsAddressesOrTheVin() {
        val redactor = Redactor { listOf("s3cretPass", "my-server.example.net") }
        val clean = redactor.clean(
            "POST https://my-server.example.net/lock?x=1 Bearer abc.def with s3cretPass for LGXCE4CB0N1234567 at 32.0853123,34.7818123",
        )
        assertFalse(clean.contains("s3cretPass"))
        assertFalse(clean.contains("my-server"))
        assertFalse(clean.contains("abc.def"))
        assertFalse(clean.contains("LGXCE4CB0N1234567"))
        assertFalse(clean.contains("32.0853123"))
        assertTrue(clean.contains("<vin>"))
    }

    @Test
    fun watchMessagesOfBothVersionsAreUnderstood() {
        WearSync.parse("LOCK")!!.run {
            assertEquals("LOCK", command); assertNull(id); assertNull(tapAt)
        }
        WearSync.parse("UNLOCK|1b2c|1800000000000")!!.run {
            assertEquals("UNLOCK", command); assertEquals("1b2c", id); assertEquals(1_800_000_000_000L, tapAt)
        }
        assertNull(WearSync.parse(""))
        assertNull(WearSync.parse("  "))
    }

    @Test
    fun repeatsOfTheSameControlShareAGroup() {
        assertEquals(CommandPolicy.group(CarCommand.LOCK), CommandPolicy.group(CarCommand.UNLOCK))
        assertEquals(CommandPolicy.group(CarCommand.QUICK_COOL), CommandPolicy.group(CarCommand.CLIMATE_OFF))
        assertTrue(CommandPolicy.isToggle(CarCommand.TRUNK))
        assertFalse(CommandPolicy.isToggle(CarCommand.LOCK))
        assertEquals(CommandPolicy.PROMPT_DELAY_MS, CommandPolicy.maxDelayMs(CarCommand.UNLOCK))
        assertEquals(CommandPolicy.MAX_DELAY_MS, CommandPolicy.maxDelayMs(CarCommand.LOCK))
    }
}
