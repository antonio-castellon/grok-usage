package ch.castellon.grok.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class UsageParserFixTest {
    @Test
    fun screenshotSuperGrokHeavyAndBot() {
        val parsed = UsageParser.parse(
            """
            Usage
            Weekly SuperGrok Heavy Limit
            10% of total
            Resets Tue, Dec 1, 7:58 AM
            ● Grok Build
            10% of total
            Included usage with your subscription. Resets weekly. Used before your credits.
            Weekly Grok Bot Limit
            18% used
            Resets Sun, Nov 29, 8:13 PM
            Grok Bot uses a separate weekly limit with its own reset schedule.
            Extra Usage Credits
            Balance
            $0.62
            Buy More
            Set Up Auto Top-Up
            $5.00 when balance is below $3.00, up to $10.00 per month
            """.trimIndent(),
        )
        requireNotNull(parsed)
        assertEquals(10f, parsed.overallPercent!!, 0.01f)
        assertEquals("Tue, Dec 1, 7:58 AM", parsed.overallResetRaw)
        assertEquals(listOf(UsageSegment("Grok Build", 10f)), parsed.segments)
        assertEquals(18f, parsed.botPercent!!, 0.01f)
        assertEquals("Sun, Nov 29, 8:13 PM", parsed.botResetRaw)
    }

    @Test
    fun extraUsageCreditsDiscountDoesNotLeak() {
        val parsed = UsageParser.parse(
            """
            Weekly SuperGrok Limit
            0% of total
            Resets Tue, Dec 1, 7:58 AM
            Extra Usage Credits
            Up to 20% Off
            Balance $0.00
            """.trimIndent(),
        )
        requireNotNull(parsed)
        assertEquals(0f, parsed.overallPercent!!, 0.01f)
        assertNull(parsed.botPercent)
        assertTrue(parsed.segments.isEmpty())
    }

    @Test
    fun spanishLocaleVariant() {
        val parsed = UsageParser.parse(
            """
            Uso
            Límite semanal de SuperGrok Heavy
            10 % del total
            Se restablece el mar, 1 dic, 7:58
            ● Grok Build
            10 % del total
            Uso incluido con tu suscripción. Se restablece semanalmente.
            Límite semanal de Grok Bot
            18 % utilizado
            Se restablece el dom, 29 nov, 20:13
            Créditos de uso adicional
            Saldo
            0,62 $
            """.trimIndent(),
        )
        requireNotNull(parsed)
        assertEquals(10f, parsed.overallPercent!!, 0.01f)
        assertEquals("mar, 1 dic, 7:58", parsed.overallResetRaw)
        assertEquals(listOf(UsageSegment("Grok Build", 10f)), parsed.segments)
        assertEquals(18f, parsed.botPercent!!, 0.01f)
        assertEquals("dom, 29 nov, 20:13", parsed.botResetRaw)
    }

    @Test
    fun germanLocaleVariantWithCommaDecimalsAndCursor() {
        val parsed = UsageParser.parse(
            """
            Nutzung
            Wöchentliches Premium-Limit
            24,5 % des Gesamtlimits
            Zurücksetzung Di., 1. Dez., 07:58
            Grok Build 14,5 %
            Cursor 10 %
            Wöchentliches Grok Bot-Limit
            0 % verwendet
            Zurücksetzung So., 29. Nov., 20:13
            """.trimIndent(),
        )
        requireNotNull(parsed)
        assertEquals(24.5f, parsed.overallPercent!!, 0.01f)
        assertEquals("Di., 1. Dez., 07:58", parsed.overallResetRaw)
        assertEquals(
            listOf(UsageSegment("Grok Build", 14.5f), UsageSegment("Cursor", 10f)),
            parsed.segments,
        )
        assertEquals(0f, parsed.botPercent!!, 0.01f)
        assertEquals("So., 29. Nov., 20:13", parsed.botResetRaw)
    }

    @Test
    fun frenchLessThanOnePercentVariant() {
        val parsed = UsageParser.parse(
            """
            Utilisation
            Limite hebdomadaire Ultra
            <1% utilisé
            Réinitialisation le mar. 1 déc. à 07:58
            Grok Build <1%
            Chat 0%
            """.trimIndent(),
        )
        requireNotNull(parsed)
        assertEquals(0.5f, parsed.overallPercent!!, 0.01f)
        assertEquals("mar. 1 déc. à 07:58", parsed.overallResetRaw)
        assertEquals(
            listOf(UsageSegment("Grok Build", 0.5f), UsageSegment("Chat", 0f)),
            parsed.segments,
        )
    }

    @Test
    fun grokBotInBreakdownAndSeparateBotSection() {
        val parsed = UsageParser.parse(
            """
            Weekly Ultra Limit
            25% of total
            Resets Tue, Dec 1, 7:58 AM
            Grok Build 12% of total
            Grok Bot 5% of total
            Cursor 8% of total
            Weekly Grok Bot Limit
            18% used
            Resets Sun, Nov 29, 8:13 PM
            """.trimIndent(),
        )
        requireNotNull(parsed)
        assertEquals(25f, parsed.overallPercent!!, 0.01f)
        assertEquals(
            listOf(
                UsageSegment("Grok Build", 12f),
                UsageSegment("Grok Bot", 5f),
                UsageSegment("Cursor", 8f),
            ),
            parsed.segments,
        )
        assertEquals(18f, parsed.botPercent!!, 0.01f)
        assertEquals("Sun, Nov 29, 8:13 PM", parsed.botResetRaw)
    }

    @Test
    fun zeroPercentAndEmptyInputs() {
        assertNull(UsageParser.parse(null))
        assertNull(UsageParser.parse(""))
        assertNull(UsageParser.parse("   "))
        assertNull(UsageParser.parse("short"))

        val zero = UsageParser.parse(
            """
            Weekly SuperGrok Heavy Limit
            0% of total
            Resets Tue, Dec 1, 7:58 AM
            """.trimIndent(),
        )
        requireNotNull(zero)
        assertEquals(0f, zero.overallPercent!!, 0.01f)
        assertTrue(zero.segments.isEmpty())
    }

    @Test
    fun structuredCreditsJsonParsing() {
        val json = """
            {
              "config": {
                "currentPeriod": {
                  "type": "USAGE_PERIOD_TYPE_WEEKLY",
                  "end": "2026-12-01T07:58:00Z"
                },
                "creditUsagePercent": 10,
                "productUsage": [
                  {"product": "PRODUCT_GROK_BUILD", "usagePercent": 10},
                  {"product": 10, "usagePercent": 0}
                ],
                "isUnifiedBillingUser": true
              }
            }
        """.trimIndent()
        val parsed = UsageParser.parse(json, ZoneOffset.UTC)
        requireNotNull(parsed)
        assertEquals(10f, parsed.overallPercent!!, 0.01f)
        assertEquals("Dec 1, 2026, 7:58 AM", parsed.overallResetRaw)
        assertEquals(listOf(UsageSegment("Grok Build", 10f)), parsed.segments)
        assertNull(parsed.botPercent)

        val protoZero = """
            {
              "config": {
                "currentPeriod": {
                  "type": 2,
                  "end": {"seconds": "1796111880", "nanos": 0}
                },
                "productUsage": []
              }
            }
        """.trimIndent()
        val zeroParsed = UsageParser.parseCreditsJson(protoZero, ZoneOffset.UTC)
        requireNotNull(zeroParsed)
        assertEquals(0f, zeroParsed.overallPercent!!, 0.01f)
        assertEquals("Dec 1, 2026, 7:58 AM", zeroParsed.overallResetRaw)

        assertNull(UsageParser.parseCreditsJson("""{"code":16,"message":"No credentials presented."}"""))
    }

    @Test
    fun cookieLooksSignedInRejectsAnonymousCookies() {
        assertFalse(cookieLooksSignedIn("gb_anon_id=anon123; grok_device_id=dev456; __cf_bm=abc"))
        assertFalse(cookieLooksSignedIn("grok_device_id=dev456; i18next=es"))
        assertFalse(cookieLooksSignedIn("sso=; __cf_bm=abc"))
        assertTrue(cookieLooksSignedIn("grok_device_id=dev456; sso=tok123"))
        assertTrue(cookieLooksSignedIn("__cf_bm=abc; sso-rw=rw123"))
    }

    @Test
    fun partialBotSnapshotAndMerge() {
        val partial = UsageSnapshot.partialBot(BotMeter(18f, "Sun, Nov 29, 8:13 PM"), 99L)
        assertNull(partial.overallPercent)
        assertTrue(partial.segments.isEmpty())
        assertEquals(18f, partial.botPercent!!, 0.01f)
        assertEquals("Sun, Nov 29, 8:13 PM", partial.botResetRaw)

        val credits = ParsedUsage(10f, "Dec 1, 2026, 7:58 AM", listOf(UsageSegment("Grok Build", 10f)), null, null)
        val text = ParsedUsage(10f, "Tue, Dec 1, 7:58 AM", listOf(UsageSegment("Grok Build", 10f)), 18f, "Sun, Nov 29, 8:13 PM")
        val merged = credits.mergeWith(text)
        assertEquals(10f, merged.overallPercent!!, 0.01f)
        assertEquals(18f, merged.botPercent!!, 0.01f)
        assertEquals("Sun, Nov 29, 8:13 PM", merged.botResetRaw)
    }

    @Test
    fun flexRowTabSeparatedScreenshotLayout() {
        val parsed = UsageParser.parse(
            """
            Usage
            Weekly SuperGrok Heavy Limit
            10% of total	Resets Tue, Dec 1, 7:58 AM
            ● Grok Build	10% of total
            Included usage with your subscription. Resets weekly. Used before your credits.
            Weekly Grok Bot Limit
            18% used	Resets Sun, Nov 29, 8:13 PM
            Grok Bot uses a separate weekly limit with its own reset schedule.
            Extra Usage Credits
            Balance	$0.62
            """.trimIndent(),
        )
        requireNotNull(parsed)
        assertEquals(10f, parsed.overallPercent!!, 0.01f)
        assertEquals("Tue, Dec 1, 7:58 AM", parsed.overallResetRaw)
        assertEquals(listOf(UsageSegment("Grok Build", 10f)), parsed.segments)
        assertEquals(18f, parsed.botPercent!!, 0.01f)
        assertEquals("Sun, Nov 29, 8:13 PM", parsed.botResetRaw)
    }
}
