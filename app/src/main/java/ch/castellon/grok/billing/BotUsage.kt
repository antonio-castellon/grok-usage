package ch.castellon.grok.billing

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class BotMeter(
    val percent: Float,
    val resetRaw: String?,
)

sealed class BotRead {
    data class Meter(val meter: BotMeter) : BotRead()
    data object None : BotRead()
    data class Failed(val reason: String) : BotRead()
}

object BotUsage {
    private val percentField = Regex("\"percent\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)")
    private val resetField = Regex("\"resetMs\"\\s*:\\s*(-?\\d+)")
    private val errField = Regex("\"err\"\\s*:\\s*\"([a-z]+)\"")
    private val genField = Regex("\"gen\"\\s*:\\s*(\\d+)")
    private val resetFormat = DateTimeFormatter.ofPattern("MMMM d, yyyy 'at' h:mm a", Locale.US)

    fun generation(payload: String?): Int? {
        if (payload.isNullOrBlank()) return null
        return genField.find(payload)?.groupValues?.get(1)?.toIntOrNull()
    }

    fun parse(payload: String?, zone: ZoneId = ZoneId.systemDefault()): BotRead {
        if (payload.isNullOrBlank()) return BotRead.Failed("empty")
        if (Regex("\"none\"\\s*:\\s*true").containsMatchIn(payload)) return BotRead.None
        if (Regex("\"ok\"\\s*:\\s*false").containsMatchIn(payload)) {
            return BotRead.Failed(errField.find(payload)?.groupValues?.get(1) ?: "fail")
        }
        val percent = percentField.find(payload)?.groupValues?.get(1)?.toFloatOrNull()
            ?: return BotRead.Failed("percent")
        if (percent < 0f || percent > 100f) return BotRead.Failed("range")
        val resetMs = normalizeEpoch(resetField.find(payload)?.groupValues?.get(1)?.toLongOrNull() ?: 0L)
        val reset = if (resetMs > 0L) formatReset(resetMs, zone) else null
        return BotRead.Meter(BotMeter(percent, reset))
    }

    private fun normalizeEpoch(raw: Long): Long {
        if (raw <= 0L) return 0L
        return if (raw < 1_000_000_000_000L) raw * 1000L else raw
    }

    private fun formatReset(epochMs: Long, zone: ZoneId): String {
        return Instant.ofEpochMilli(epochMs).atZone(zone).format(resetFormat)
    }
}
