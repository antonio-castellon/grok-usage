package ch.castellon.grok.billing

object UsageParser {
    private val botHeader = Regex(
        "(?i)weekly\\s+grok\\s+bot\\s+limit|grok\\s+bot\\s+limit|weekly\\s+bot\\s+limit",
    )
    private val used = Regex("(?i)(\\d+(?:\\.\\d+)?)\\s*%\\s*used")
    private val segment = Regex(
        "(?i)(?<![A-Za-z])(grok\\s+build|imagine|voice|chat|api)(?![A-Za-z])\\s*[:\\-–—]?\\s*(\\d+(?:\\.\\d+)?)\\s*%",
    )
    private val reset = Regex("(?i)\\bresets?\\b\\s*[:\\-]?\\s*(.*)$")
    private val numberOnly = Regex("^(\\d+(?:\\.\\d+)?)\\s*%$")
    private val anyPercent = Regex("(\\d+(?:\\.\\d+)?)\\s*%")
    private val usageWord = Regex("(?i)\\busage\\b")

    fun parse(raw: String?): ParsedUsage? {
        if (raw.isNullOrBlank() || raw.trim().length < 8) return null
        val text = raw.replace("\r\n", "\n").replace('\r', '\n')
        val header = botHeader.find(text)
        val overallText = if (header == null) text else text.substring(0, header.range.first)
        val botText = if (header == null) "" else text.substring(header.range.first)
        val overallLines = linesOf(overallText)
        val botLines = linesOf(botText)
        val segments = segmentsFrom(overallLines)
        var overall = usedPercent(overallLines)
        val bot = if (botLines.isEmpty()) {
            null
        } else {
            usedPercent(botLines) ?: firstLoosePercent(botText)
        }
        if (overall == null && segments.isNotEmpty()) {
            val sum = segments.sumOf { it.percent.toDouble() }.toFloat()
            if (sum in 0f..100f) overall = sum
        }
        val confident = used.containsMatchIn(text) ||
            header != null ||
            (segments.size >= 2 && usageWord.containsMatchIn(text))
        if (!confident || (overall == null && bot == null)) return null
        return ParsedUsage(
            overallPercent = overall,
            overallResetRaw = resetRaw(overallLines),
            segments = if (overall == null) emptyList() else segments,
            botPercent = bot,
            botResetRaw = resetRaw(botLines),
        )
    }

    private fun linesOf(text: String): List<String> {
        return text.lines().map { cleanLabel(it) }.filter { it.isNotEmpty() }
    }

    private fun cleanLabel(line: String): String {
        return line.trim().trimStart('●', '•', '·', '-', '–', '—', '*', '■', '▪').trim()
    }

    private fun canonical(raw: String): String? = when (raw.trim().lowercase().replace(Regex("\\s+"), " ")) {
        "grok build", "build" -> "Grok Build"
        "chat" -> "Chat"
        "imagine" -> "Imagine"
        "voice" -> "Voice"
        "api" -> "API"
        else -> null
    }

    private fun usedPercent(lines: List<String>): Float? {
        val text = lines.joinToString("\n")
        used.find(text)?.groupValues?.get(1)?.toFloatOrNull()?.let {
            return it.coerceIn(0f, 100f)
        }
        lines.forEachIndexed { index, line ->
            val match = numberOnly.matchEntire(line) ?: return@forEachIndexed
            val next = lines.getOrNull(index + 1)?.lowercase() ?: return@forEachIndexed
            if (next == "used" || next.startsWith("used ")) {
                return match.groupValues[1].toFloat().coerceIn(0f, 100f)
            }
        }
        return null
    }

    private fun firstLoosePercent(text: String): Float? {
        return anyPercent.find(text)?.groupValues?.get(1)?.toFloatOrNull()?.coerceIn(0f, 100f)
    }

    private fun segmentsFrom(lines: List<String>): List<UsageSegment> {
        val found = linkedMapOf<String, Float>()
        segment.findAll(lines.joinToString("\n")).forEach { match ->
            val label = canonical(match.groupValues[1]) ?: return@forEach
            val percent = match.groupValues[2].toFloatOrNull() ?: return@forEach
            if (label !in found) found[label] = percent.coerceIn(0f, 100f)
        }
        lines.forEachIndexed { index, line ->
            val label = canonical(line) ?: return@forEachIndexed
            if (label in found) return@forEachIndexed
            val next = lines.getOrNull(index + 1) ?: return@forEachIndexed
            val match = numberOnly.matchEntire(next) ?: return@forEachIndexed
            found[label] = match.groupValues[1].toFloat().coerceIn(0f, 100f)
        }
        return found.map { UsageSegment(it.key, it.value) }
    }

    private fun resetRaw(lines: List<String>): String? {
        lines.forEachIndexed { index, line ->
            val match = reset.find(line) ?: return@forEachIndexed
            val inline = match.groupValues[1].trim().trimEnd('.', '·', '|')
            if (inline.isNotEmpty()) return inline
            val next = lines.getOrNull(index + 1)?.trim().orEmpty()
            if (next.isNotEmpty() && canonical(next) == null && !next.contains("%")) return next
        }
        return null
    }
}
