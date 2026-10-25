package ch.castellon.grok.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageRefreshTest {
    @Test
    fun pullsOnlyForSignedInFiguresThatAreShown() {
        assertTrue(UsageRefresh.wanted(hasSession = true, hasSnapshot = true, widgets = 1, notify = false))
        assertTrue(UsageRefresh.wanted(hasSession = true, hasSnapshot = true, widgets = 0, notify = true))
        assertFalse(UsageRefresh.wanted(hasSession = true, hasSnapshot = true, widgets = 0, notify = false))
        assertFalse(UsageRefresh.wanted(hasSession = false, hasSnapshot = true, widgets = 2, notify = true))
        assertFalse(UsageRefresh.wanted(hasSession = true, hasSnapshot = false, widgets = 2, notify = true))
    }

    @Test
    fun intervalIsTheAndroidMinimum() {
        assertEquals(15L, UsageRefresh.INTERVAL_MINUTES)
    }

    @Test
    fun backgroundFollowsTheSessionBackToGrok() {
        assertTrue(backgroundMayNavigate("grok.com"))
        assertTrue(backgroundMayNavigate("accounts.x.ai"))
        assertTrue(backgroundMayNavigate("accounts.spacex.ai"))
        assertFalse(backgroundMayNavigate("accounts.google.com"))
        assertFalse(backgroundMayNavigate("example.com"))
    }

    @Test
    fun separateBotMeterFillsAMissingBotCard() {
        val parsed = ParsedUsage(42f, "r", emptyList(), null, null)
        val snap = UsageSnapshot.from(parsed, BotMeter(60f, "b"), 7L)
        assertEquals(60f, snap.botPercent!!, 0f)
        assertEquals("b", snap.botResetRaw)
        assertEquals(7L, snap.updatedAtEpochMs)
    }

    @Test
    fun pageBotCardWinsOverSeparateMeter() {
        val parsed = ParsedUsage(42f, "r", emptyList(), 10f, "page")
        val snap = UsageSnapshot.from(parsed, BotMeter(60f, "b"), 7L)
        assertEquals(10f, snap.botPercent!!, 0f)
        assertEquals("page", snap.botResetRaw)
        assertNull(UsageSnapshot.from(parsed.copy(botPercent = null, botResetRaw = null), null, 1L).botPercent)
    }
}