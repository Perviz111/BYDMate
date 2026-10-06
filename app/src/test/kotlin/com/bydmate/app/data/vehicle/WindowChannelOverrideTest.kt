package com.bydmate.app.data.vehicle

import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The manual PERCENT override (#64): on firmwares whose CTRL open/close window fid is dead
 * (DiLink 3.0 "trinket" / Destroyer 05, where only TARGET_POSITION moves the glass), the
 * override reroutes open/close to a position write — open = 100, close = 0 — and leaves an
 * explicit percentage untouched. AUTO must keep the pre-existing behaviour unchanged, and the
 * override must pin the channel without ever reading the bus.
 */
class WindowChannelOverrideTest {

    private class OverrideStore(private val value: WindowChannelOverride) : WindowChannelStore {
        override fun winner() = WindowChannel.UNKNOWN
        override fun setWinner(channel: WindowChannel) {}
        override fun ctrlCandidateAtMs() = 0L
        override fun setCtrlCandidateAtMs(ts: Long) {}
        override fun override() = value
    }

    /** Fails the test if the router ever touches the bus — a forced channel must not probe. */
    private class NoReadHelper : HelperClient by mockk(relaxed = true) {
        override suspend fun read(dev: Int, fid: Int, tx: Int): Long? =
            throw AssertionError("forced override must not probe (read dev=$dev fid=$fid)")
    }

    private fun router(store: WindowChannelStore) =
        WindowChannelRouter(NoReadHelper(), store) { 1_700_000_000_000L }

    @Test fun `forced PERCENT reroutes driver open to a full-down position write`() = runTest {
        val routed = router(OverrideStore(WindowChannelOverride.PERCENT))
            .route("window_driver_open", 1)
        assertEquals(RoutedWrite("window_driver_pos", 100), routed)
    }

    @Test fun `forced PERCENT reroutes driver close to a full-up position write`() = runTest {
        val routed = router(OverrideStore(WindowChannelOverride.PERCENT))
            .route("window_driver_close", 2)
        assertEquals(RoutedWrite("window_driver_pos", 0), routed)
    }

    @Test fun `forced PERCENT leaves an explicit percentage untouched`() = runTest {
        val routed = router(OverrideStore(WindowChannelOverride.PERCENT))
            .route("window_driver_pos", 30)
        assertEquals(RoutedWrite("window_driver_pos", 30), routed)
    }

    @Test fun `AUTO leaves open or close as the native CTRL action, as before`() = runTest {
        // AUTO must not reach the rerouting path at all; open/close pass straight through
        // (they are not percent actions, so the router returns them verbatim without probing).
        val routed = router(OverrideStore(WindowChannelOverride.AUTO))
            .route("window_driver_open", 1)
        assertEquals(RoutedWrite("window_driver_open", 1), routed)
    }
}
