package sh.zeron.android.background

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.zeron_core.SendState

/**
 * The delivery hold's rule (#58, audit 05 M1; iOS `AppModel.swift:293-311`):
 * signed in, and a send in the last 120 s or a row still sending/queued.
 */
class DeliveryHoldTest {

    @Test
    fun aRecentSendHolds() {
        assertTrue(DeliveryHold.shouldHold(last = 1_000, now = 1_000 + 119_999, signedIn = true, pending = false))
    }

    @Test
    fun aSendOlderThanTwoMinutesDoesNot() {
        assertFalse(DeliveryHold.shouldHold(last = 1_000, now = 1_000 + 120_000, signedIn = true, pending = false))
    }

    @Test
    fun noSendNothingPendingDoesNot() {
        assertFalse(DeliveryHold.shouldHold(last = null, now = 5_000, signedIn = true, pending = false))
    }

    @Test
    fun aPendingRowHoldsWithoutARecentSend() {
        assertTrue(DeliveryHold.shouldHold(last = null, now = 500_000, signedIn = true, pending = true))
    }

    @Test
    fun signedOutNeverHolds() {
        assertFalse(DeliveryHold.shouldHold(last = 1_000, now = 1_000, signedIn = false, pending = true))
    }

    @Test
    fun sendingAndQueuedArePendingFailedIsNot() {
        assertTrue(DeliveryHold.anyPending(sequenceOf(null, SendState.SENDING)))
        assertTrue(DeliveryHold.anyPending(sequenceOf(SendState.QUEUED)))
        assertFalse(DeliveryHold.anyPending(sequenceOf(null, SendState.FAILED)))
        assertFalse(DeliveryHold.anyPending(emptySequence()))
    }

    @Test
    fun noteActivityFeedsTheDecision() {
        DeliveryHold.resetForTest()
        assertFalse(DeliveryHold.shouldHold(now = 10_000, signedIn = true, pending = false))
        DeliveryHold.noteActivity(at = 10_000)
        assertTrue(DeliveryHold.shouldHold(now = 20_000, signedIn = true, pending = false))
        DeliveryHold.resetForTest()
    }
}
