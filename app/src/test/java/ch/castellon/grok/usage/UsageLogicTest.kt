package ch.castellon.grok.usage

import ch.castellon.grok.usage.session.hostAllowed
import ch.castellon.grok.usage.session.isGrokHost
import ch.castellon.grok.usage.session.prefersXaiAccount
import ch.castellon.grok.usage.session.holdsSignIn
import ch.castellon.grok.usage.session.isLoginReturnUrl
import ch.castellon.grok.usage.session.isXaiAccountHome
import ch.castellon.grok.usage.session.loginHandsOffToDashboard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageLogicTest {
    @Test
    fun classicDashboard() {
        val parsed = UsageParser.parse(
            """
            Usage
            13% used
            Grok Build 9% · Chat 4%
            Resets Oct 20, 2026, 10:48 PM
            Weekly Grok Bot Limit
            65% used
            Resets Oct 19, 2026, 3:00 AM
            """.trimIndent(),
        )
        requireNotNull(parsed)
        assertEquals(13f, parsed.overallPercent!!, 0.01f)
        assertEquals("Oct 20, 2026, 10:48 PM", parsed.overallResetRaw)
        assertEquals(
            listOf(UsageSegment("Grok Build", 9f), UsageSegment("Chat", 4f)),
            parsed.segments,
        )
        assertEquals(65f, parsed.botPercent!!, 0.01f)
        assertEquals("Oct 19, 2026, 3:00 AM", parsed.botResetRaw)
        assertEquals("Usage 13% · Bot 65%", bannerLabel(parsed.overallPercent, parsed.botPercent))
        assertEquals("13", statusIconText(parsed.overallPercent))
        assertEquals("65", statusIconText(parsed.botPercent))
        assertEquals("65%", statusBarFigure(parsed.botPercent, parsed.overallPercent))
        assertEquals("13%", statusBarFigure(null, parsed.overallPercent))
        assertEquals(listOf("65%"), statusIconGlyphs(parsed.botPercent))
        assertEquals(listOf("66", ".5%"), statusIconGlyphs(66.5f))
        assertEquals(listOf("Bot 65%", "Usage 13%"), notificationLines(parsed.overallPercent, parsed.botPercent))
        assertEquals("Bot 66.9%" to "Usage 17%", widgetClockLines(17f, 66.9f))
        assertEquals("Bot —" to "Usage —", widgetClockLines(null, null))
        assertEquals("Resets Oct 20, 2026, 10:48 PM", resetLabel(parsed.overallResetRaw))
    }

    @Test
    fun stackedLabels() {
        val parsed = UsageParser.parse(
            """
            13% used
            Grok Build
            9%
            Chat
            4%
            Resets
            Oct 20, 2026, 10:48 PM
            Weekly Grok Bot Limit
            65%
            used
            Resets Oct 19, 2026, 3:00 AM
            """.trimIndent(),
        )
        requireNotNull(parsed)
        assertEquals(13f, parsed.overallPercent!!, 0.01f)
        assertEquals(2, parsed.segments.size)
        assertEquals("Oct 20, 2026, 10:48 PM", parsed.overallResetRaw)
        assertEquals(65f, parsed.botPercent!!, 0.01f)
    }

    @Test
    fun sharesOfTheUsedAmountStayOnTheSegments() {
        val parsed = UsageParser.parse(
            """
            13% used
            Grok Build 70%
            Chat 30%
            Resets Monday at 10:48 PM
            """.trimIndent(),
        )
        requireNotNull(parsed)
        val fractions = barFractions(13f, listOf(70f, 30f))
        assertEquals(0.091f, fractions[0], 0.001f)
        assertEquals(0.039f, fractions[1], 0.001f)
        assertEquals(70f, parsed.segments[0].percent, 0.01f)
    }

    @Test
    fun poolPercentsUseFaceValue() {
        val fractions = barFractions(13f, listOf(9f, 4f))
        assertEquals(0.09f, fractions[0], 0.001f)
        assertEquals(0.04f, fractions[1], 0.001f)
    }

    @Test
    fun loginPageIsNotUsage() {
        assertNull(
            UsageParser.parse(
                """
                Sign in to Grok
                Email
                Password
                Continue
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun loosePercentIsNotUsage() {
        assertNull(UsageParser.parse("50% of people like chat"))
        assertNull(UsageParser.parse("Chat 4%"))
    }

    @Test
    fun decimalAndBotOnly() {
        val overall = UsageParser.parse("13.5% used\nGrok Build 9.5%\nChat 4%\nResets Nov 1, 2026, 9:00 AM")
        requireNotNull(overall)
        assertEquals(13.5f, overall.overallPercent!!, 0.01f)
        assertEquals("13.5", formatPercent(13.5f))
        assertEquals("13", formatPercent(13f))

        val bot = UsageParser.parse("Weekly Grok Bot Limit\n65% used\nResets Oct 19, 2026, 3:00 AM")
        requireNotNull(bot)
        assertNull(bot.overallPercent)
        assertEquals(65f, bot.botPercent!!, 0.01f)
        assertEquals("Bot 65%", bannerLabel(bot.overallPercent, bot.botPercent))
    }

    @Test
    fun jsResultKeepsNewlines() {
        assertEquals("13% used\nChat 4%", decodeJsResult("\"13% used\\nChat 4%\""))
        assertEquals("Say \"hi\"", decodeJsResult("\"Say \\\"hi\\\"\""))
        assertEquals("", decodeJsResult("null"))
    }

    @Test
    fun spacexLinkHostStaysInTheAppUntilUsage() {
        assertTrue(hostAllowed("api.workos.com"))
        assertTrue(holdsSignIn("api.workos.com"))
        assertFalse(loginHandsOffToDashboard("https://api.workos.com/user_management/authorize"))
        assertTrue(hostAllowed("accounts.spacex.ai"))
        assertTrue(hostAllowed("accounts.x.ai"))
        assertTrue(holdsSignIn("accounts.spacex.ai"))
        assertFalse(loginHandsOffToDashboard("https://accounts.spacex.ai/sign-in"))
        assertFalse(loginHandsOffToDashboard("https://x.com/i/flow/login"))
        assertTrue(loginHandsOffToDashboard("https://accounts.spacex.ai/oauth/callback?code=ok"))
        assertTrue(loginHandsOffToDashboard("https://x.com/home"))
        assertFalse(loginHandsOffToDashboard("https://grok.com/dashboard"))
        assertFalse(loginHandsOffToDashboard("https://grok.com/"))
        assertTrue(isLoginReturnUrl("https://grok.com/dashboard"))
        assertFalse(isLoginReturnUrl("https://accounts.spacex.ai/sign-in"))
        assertTrue(prefersXaiAccount("https://authenticator.cursor.sh/"))
        assertTrue(prefersXaiAccount("https://cursor.com/dashboard?from=grok"))
        assertFalse(prefersXaiAccount("https://accounts.x.ai/sign-in?redirect=grok"))
        assertTrue(isXaiAccountHome("https://accounts.x.ai/account"))
        assertFalse(isXaiAccountHome("https://accounts.x.ai/sign-in?redirect=grok"))
    }

    @Test
    fun superGrokHeavyPanel() {
        val parsed = UsageParser.parse(
            """
            Usage
            Weekly SuperGrok Heavy Limit
            15% used
            Resets October 20, 2026 at 8:58 AM
            Grok Build 11%
            Chat 4%
            Weekly Grok Bot Limit
            66% used
            Resets October 18, 2026 at 9:13 PM
            """.trimIndent(),
        )
        requireNotNull(parsed)
        assertEquals(15f, parsed.overallPercent!!, 0.01f)
        assertEquals(
            listOf(UsageSegment("Grok Build", 11f), UsageSegment("Chat", 4f)),
            parsed.segments,
        )
        assertEquals(66f, parsed.botPercent!!, 0.01f)
    }

    @Test
    fun cursorUpgradePercentIsNotGrokUsage() {
        assertNull(
            UsageParser.parse(
                """
                Usage
                0% used
                Resets Nov 18, 2026
                Upgrade
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun sessionCookieIgnoresCloudflareOnly() {
        assertFalse(cookieLooksSignedIn(null))
        assertFalse(cookieLooksSignedIn("__cf_bm=abc; _cfuvid=xyz"))
        assertTrue(cookieLooksSignedIn("__cf_bm=abc; sso=1"))
    }

    @Test
    fun storedSessionOpensUsageInsteadOfSignIn() {
        assertFalse(showSignInWeb(Phase.SigningIn, readingUsage = true, sessionKnown = true))
        assertFalse(showSignInWeb(Phase.SigningIn, readingUsage = false, sessionKnown = true))
        assertFalse(showSignInWeb(Phase.SigningIn, readingUsage = true, sessionKnown = false))
        assertTrue(showSignInWeb(Phase.SigningIn, readingUsage = false, sessionKnown = false))
        assertTrue(showSessionHome(Phase.SigningIn, sessionKnown = true, hasSnapshot = false))
        assertTrue(showSessionHome(Phase.Ready, sessionKnown = true, hasSnapshot = false))
        assertFalse(showSessionHome(Phase.Ready, sessionKnown = true, hasSnapshot = true))
        assertFalse(showSessionHome(Phase.SignedOut, sessionKnown = false, hasSnapshot = false))
        assertFalse(showSignInActions(sessionKnown = true))
        assertTrue(showSignInActions(sessionKnown = false))
        assertTrue(showReadingCover(Phase.SigningIn, readingUsage = true, sessionKnown = false))
        assertFalse(showReadingCover(Phase.SigningIn, readingUsage = true, sessionKnown = true))
    }

    @Test
    fun signInWaitExpiresAfterThreeMinutes() {
        assertFalse(signInWaitExpired(0L, 180_000L))
        assertFalse(signInWaitExpired(1_000L, 180_999L))
        assertTrue(signInWaitExpired(1_000L, 181_000L))
    }

    @Test
    fun botMeterFromUsageSocket() {
        val ms = java.time.Instant.parse("2026-10-18T21:13:00Z").toEpochMilli()
        val read = BotUsage.parse(
            """{"gen":2,"ok":true,"percent":66,"resetMs":$ms}""",
            java.time.ZoneOffset.UTC,
        )
        val meter = (read as BotRead.Meter).meter
        assertEquals(66f, meter.percent, 0.01f)
        assertEquals("October 18, 2026 at 9:13 PM", meter.resetRaw)
        assertEquals(2, BotUsage.generation("""{"gen":2,"ok":true}"""))
        assertTrue(BotUsage.parse("""{"ok":true,"none":true}""") is BotRead.None)
        assertTrue(BotUsage.parse("""{"ok":false,"err":"timeout"}""") is BotRead.Failed)
        assertTrue(BotUsage.parse("""{"ok":true,"percent":140,"resetMs":0}""") is BotRead.Failed)
        val seconds = BotUsage.parse(
            """{"ok":true,"percent":0,"resetMs":${ms / 1000}}""",
            java.time.ZoneOffset.UTC,
        ) as BotRead.Meter
        assertEquals("October 18, 2026 at 9:13 PM", seconds.meter.resetRaw)
        assertTrue(isGrokHost("grok.com"))
        assertTrue(isGrokHost("assets.grok.com"))
        assertFalse(isGrokHost("grokusercontent.com"))
        assertFalse(isGrokHost("cursor.com"))
    }
}
