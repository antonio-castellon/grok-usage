package ch.castellon.grok.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
