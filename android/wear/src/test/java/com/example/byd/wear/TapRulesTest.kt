package com.example.byd.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a watch tap is followed: by id with a 2.8 phone, the old way with a 2.7 phone. */
class TapRulesTest {
    private val t0 = 1_800_000_000_000L

    private fun tap(legacy: Boolean = false, acked: Long = 0L) = Tap("id-1", PhoneLink.LOCK, t0, legacy, ackedAt = acked)

    private fun record(final: Boolean, tone: String = "OK", label: String = "Done") =
        WatchState.Record("id-1", PhoneLink.LOCK, if (final) "DONE" else "SENDING", tone, label, "Car locked", t0, final)

    private fun stateWith(vararg records: WatchState.Record) = WatchState(proto = 2, records = records.associateBy { it.id })

    @Test
    fun theAnswerIsMatchedByIdAndBuzzesOnce() {
        val (open, none) = TapRules.settle(listOf(tap()), stateWith(record(final = false)), t0 + 1_000)
        assertTrue(none.isEmpty())
        assertTrue(open.single().ackedAt > 0) // the phone's record counts as an ack
        val (done, settled) = TapRules.settle(open, stateWith(record(final = true)), t0 + 4_000)
        assertEquals("OK", settled.single().tone)
        assertTrue(done.single().done)
        val (_, again) = TapRules.settle(done, stateWith(record(final = true)), t0 + 5_000)
        assertTrue(again.isEmpty())
    }

    @Test
    fun noAckWithinEightSecondsSaysThePhoneDidntReply() {
        val (still, none) = TapRules.settle(listOf(tap()), WatchState(proto = 2), t0 + 7_000)
        assertTrue(none.isEmpty())
        assertFalse(still.single().done)
        val (gaveUp, settled) = TapRules.settle(still, WatchState(proto = 2), t0 + 8_500)
        assertEquals("ERR", settled.single().tone)
        assertEquals(Tap.LOCAL_NO_ACK, gaveUp.single().local)
        assertTrue(gaveUp.single().revivable)
    }

    @Test
    fun aLateAnswerFromThePhoneOverrulesNoReply() {
        val (gaveUp, _) = TapRules.settle(listOf(tap()), WatchState(proto = 2), t0 + 9_000)
        val (revived, none) = TapRules.settle(gaveUp, stateWith(record(final = false)), t0 + 12_000)
        assertTrue(none.isEmpty())
        assertFalse(revived.single().done)
        val (done, settled) = TapRules.settle(revived, stateWith(record(final = true, tone = "WARN", label = "Not confirmed")), t0 + 20_000)
        assertEquals("WARN", settled.single().tone)
        assertEquals("Not confirmed", done.single().label)
    }

    @Test
    fun anAckedTapWaitsForThePhoneInsteadOfGivingUpAt45Seconds() {
        val (open, none) = TapRules.settle(listOf(tap(acked = t0 + 500)), WatchState(proto = 2), t0 + 60_000)
        assertTrue(none.isEmpty())
        assertFalse(open.single().done)
        val (done, settled) = TapRules.settle(open, WatchState(proto = 2), t0 + 500 + Taps.FINAL_TIMEOUT_MS + 1)
        assertEquals("WARN", settled.single().tone)
        assertEquals(Tap.LOCAL_NO_ANSWER, done.single().local)
    }

    @Test
    fun aTimeoutNoticedLongAfterwardsDoesntBuzz() {
        val (_, settled) = TapRules.settle(listOf(tap()), WatchState(proto = 2), t0 + 3 * 60_000)
        assertTrue(settled.single().quiet)
    }

    @Test
    fun aTwoSevenPhoneIsFollowedTheOldWay() {
        val answer = WatchState.Answer(ok = true, error = null, at = t0 + 3_000)
        val state = WatchState(answers = mapOf(PhoneLink.LOCK to answer))
        val (done, settled) = TapRules.settle(listOf(tap(legacy = true)), state, t0 + 4_000)
        assertEquals("OK", settled.single().tone)
        assertEquals(Tap.LOCAL_LEGACY_OK, done.single().local)
        val (gone, timedOut) = TapRules.settle(listOf(tap(legacy = true)), WatchState(), t0 + Taps.LEGACY_TIMEOUT_MS + 1)
        assertEquals("ERR", timedOut.single().tone)
        assertTrue(gone.single().done)
    }

    @Test
    fun aStuckPhoneDoesntKeepButtonsSpinning() {
        val state = WatchState(pending = setOf(PhoneLink.LOCK), published = t0)
        assertEquals(setOf(PhoneLink.LOCK), state.livePending(t0 + 10_000))
        assertTrue(state.livePending(t0 + Taps.FINAL_TIMEOUT_MS + 1).isEmpty())
    }
}
