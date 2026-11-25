package ch.castellon.grok.usage

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object UsageParser {
    private val botLimitHeader = Regex(
        "(?i)(?:weekly|monthly|l[íi]mite|limite|w[öo]chentlich\\w*|monatlich\\w*|hebdomadaire|mensuel\\w*|settimanal\\w*|semanal|mensual|週間|月間|주간|월간|每周|每月).*grok\\s*bot|grok\\s*bot\\s*(?:limit|l[íi]mite|limite|制限|한도|限额)|weekly\\s+bot\\s+limit",
    )
    private val botWord = Regex("(?i)\\bgrok\\s+bot\\b")
    private val extraCreditsHeader = Regex(
        "(?i)^(?:extra\\s+usage\\s+credits|cr[ée]ditos\\s+de\\s+uso\\s+adicional|zus[äa]tzliches\\s+nutzungsguthaben|cr[ée]dits\\s+d['’]utilisation\\s+suppl[ée]mentaires|crediti\\s+di\\s+utilizzo\\s+aggiuntivi|cr[ée]ditos\\s+de\\s+uso\\s+extra|追加.*クレジット|추가\\s*사용\\s*크레딧|额外使用额度|auto\\s+top-?up|buy\\s+more|buy\\s+credits)\\b",
    )
    private val percentToken = Regex("(<)?\\s*(\\d+(?:[.,]\\d+)?)\\s*%")
    private val qualifiedPercent = Regex(
        "(?i)(<)?\\s*(\\d+(?:[.,]\\d+)?)\\s*%\\s*(?:used|of\\s+total|del\\s+total|des\\s+gesamt\\w*|du\\s+total|utilis[ée]\\w*|utilizado\\w*|verwendet\\w*|usado\\w*|usat[oi]\\w*|使用|사용|合計)",
    )
    private val qualifiedWordOnly = Regex(
        "(?i)^(?:used|of\\s+total|del\\s+total|des\\s+gesamt\\w*|du\\s+total|utilis[ée]\\w*|utilizado\\w*|verwendet\\w*|usado\\w*|usat[oi]\\w*|使用|사용|合計)\\b",
    )
    private val percentLine = Regex("^(<)?\\s*(\\d+(?:[.,]\\d+)?)\\s*%(?:\\s+.*)?$")
    private val knownSegmentInline = Regex(
        "(?i)(?<![A-Za-z])(grok\\s+build|grok\\s+bot|app\\s+builder|3rd\\s+party|third\\s+party|bugbot|plugins|automations|cursor|imagine|voice|tasks|build|chat|api|other)(?![A-Za-z])\\s*[:\\-–—]?\\s*(<)?\\s*(\\d+(?:[.,]\\d+)?)\\s*%",
    )
    private val resetPrefix = Regex(
        "(?i)^(?:resets?|se\\s+restablece(?:\\s+el)?|se\\s+reinicia(?:\\s+el)?|restablecimiento|zur[üu]cksetzung(?:\\s+am)?|zur[üu]ckgesetzt|n[äa]chste\\s+zur[üu]cksetzung|wird\\s+am|r[ée]initialisation(?:\\s+le)?|se\\s+r[ée]initialise(?:\\s+le)?|ripristino(?:\\s+il)?|si\\s+ripristina|redefini[çc][ãa]o|reinicia|リセット|초기화|재설정|重置)\\b\\s*[:\\-–—]?\\s*(.*)$",
    )
    private val resetWordAnywhere = Regex(
        "(?i)\\b(?:resets?|se\\s+restablece(?:\\s+el)?|se\\s+reinicia(?:\\s+el)?|zur[üu]cksetzung(?:\\s+am)?|r[ée]initialisation(?:\\s+le)?|ripristino(?:\\s+il)?)\\b\\s*[:\\-]?\\s*(.*)$",
    )
    private val resetSuffix = Regex("(?i)\\s+(?:zur[üu]ckgesetzt|restablecido|reiniciado|r[ée]initialis[ée])\\.?$")
    private val limitCue = Regex(
        "(?i)\\b(?:weekly|monthly|limit|l[íi]mite|limite|semanal|mensual|hebdomadaire|mensuel\\w*|w[öo]chentlich\\w*|monatlich\\w*|of\\s+total|del\\s+total|used|utilizado|verwendet|utilis[ée]|supergrok|premium|ultra)\\b|制限|週間|月間|한도|주간|限额",
    )
    private val footnoteCue = Regex(
        "(?i)\\b(?:included\\s+usage|resets\\s+weekly|resets\\s+monthly|used\\s+before|separate\\s+weekly\\s+limit|when\\s+balance\\s+is\\s+below|up\\s+to\\s+\\d+%|incluido|se\\s+usa\\s+antes|enthalten|inclu[st]|incluso|upgrade\\s+for|buy\\s+extra)\\b",
    )
    private val grokMarker = Regex("(?i)supergrok|grok\\s+build|grok\\s+bot|\\bgrok\\b")
    private val resetJsonFormat = DateTimeFormatter.ofPattern("MMM d, yyyy, h:mm a", Locale.US)

    fun parse(raw: String?, zone: ZoneId = ZoneId.systemDefault()): ParsedUsage? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        if (trimmed.length < 2) return null
        if (trimmed.startsWith("{")) {
            parseJsonEnvelope(trimmed, zone)?.let { return it }
            if (trimmed.length < 8) return null
        }
        if (trimmed.length < 8) return null

        val normalized = raw
            .replace("\u00A0", " ")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        val allLines = normalized.lines().map { cleanLabel(it) }.filter { it.isNotEmpty() }
        if (allLines.isEmpty()) return null

        val creditsIdx = allLines.indexOfFirst { extraCreditsHeader.containsMatchIn(it) }
        val activeLines = if (creditsIdx >= 0) allLines.subList(0, creditsIdx) else allLines
        if (activeLines.isEmpty()) return null

        val botHeaderIdx = findBotHeaderIndex(activeLines)
        val overallLines = if (botHeaderIdx < 0) activeLines else activeLines.subList(0, botHeaderIdx)
        val botLines = if (botHeaderIdx < 0) emptyList() else activeLines.subList(botHeaderIdx, activeLines.size)

        val (segments, segmentLineIndices) = segmentsFrom(overallLines)
        var overall = overallPercentFrom(overallLines, segmentLineIndices)
        val bot = if (botLines.isEmpty()) null else botPercentFrom(botLines)

        if (overall == null && segments.isNotEmpty()) {
            val sum = segments.sumOf { it.percent.toDouble() }.toFloat()
            if (sum in 0f..100f) overall = sum
        }
        if (overall == null && bot == null) return null

        val overallReset = resetRaw(overallLines)
        val botReset = resetRaw(botLines)
        val activeText = activeLines.joinToString("\n")
        if (!looksLikeGrokUsage(activeText, overallLines, overall, segments, bot, overallReset)) {
            return null
        }

        return ParsedUsage(
            overallPercent = overall,
            overallResetRaw = overallReset,
            segments = if (overall == null) emptyList() else segments,
            botPercent = bot,
            botResetRaw = botReset,
        )
    }

    fun parseCreditsJson(raw: String?, zone: ZoneId = ZoneId.systemDefault()): ParsedUsage? {
        if (raw.isNullOrBlank()) return null
        val root = MiniJson.parseObject(raw) ?: return null
        return parseCreditsObject(root, zone)
    }

    private fun parseJsonEnvelope(raw: String, zone: ZoneId): ParsedUsage? {
        val root = MiniJson.parseObject(raw) ?: return null
        val creditsNode = root["credits"]
        val fromCredits = when (creditsNode) {
            is Map<*, *> -> parseCreditsObject(creditsNode, zone)
            else -> parseCreditsObject(root, zone)
        }
        val text = root["text"] as? String
        val fromText = if (!text.isNullOrBlank()) parse(text, zone) else null
        return when {
            fromCredits != null && fromText != null -> fromCredits.mergeWith(fromText)
            fromCredits != null -> fromCredits
            else -> fromText
        }
    }

    private fun parseCreditsObject(cfgParent: Map<*, *>, zone: ZoneId): ParsedUsage? {
        val cfg = (cfgParent["config"] as? Map<*, *>) ?: cfgParent
        val hasCreditsShape = cfg.containsKey("creditUsagePercent") ||
            cfg.containsKey("productUsage") ||
            cfg.containsKey("currentPeriod") ||
            cfg.containsKey("billingPeriodEnd") ||
            cfg.containsKey("isUnifiedBillingUser") ||
            cfg.containsKey("prepaidBalance")
        if (!hasCreditsShape) return null

        val overall = numberOf(cfg["creditUsagePercent"])?.coerceIn(0f, 100f) ?: run {
            if (cfg.containsKey("currentPeriod") || cfg.containsKey("productUsage") || cfg.containsKey("isUnifiedBillingUser")) {
                0f
            } else {
                return null
            }
        }
        val period = cfg["currentPeriod"] as? Map<*, *>
        val resetRaw = formatTimestamp(period?.get("end") ?: cfg["billingPeriodEnd"], zone)
        val segments = mutableListOf<UsageSegment>()
        (cfg["productUsage"] as? List<*>)?.forEach { entry ->
            val item = entry as? Map<*, *> ?: return@forEach
            val pct = (numberOf(item["usagePercent"]) ?: 0f).coerceIn(0f, 100f)
            if (pct <= 0f) return@forEach
            val label = productLabel(item["product"]) ?: return@forEach
            if (segments.none { it.label == label }) {
                segments.add(UsageSegment(label, pct))
            }
        }
        return ParsedUsage(overall, resetRaw, segments, null, null)
    }

    private fun findBotHeaderIndex(lines: List<String>): Int {
        for (i in lines.indices.reversed()) {
            val line = lines[i]
            if (botLimitHeader.containsMatchIn(line)) return i
            if (botWord.containsMatchIn(line) && !percentToken.containsMatchIn(line)) {
                val tail = lines.subList(i + 1, lines.size)
                val hasResetLater = tail.any { isResetLine(it) }
                if (i == 0 || hasResetLater) return i
            }
        }
        return -1
    }

    private fun looksLikeGrokUsage(
        activeText: String,
        overallLines: List<String>,
        overall: Float?,
        segments: List<UsageSegment>,
        bot: Float?,
        overallReset: String?,
    ): Boolean {
        if (bot != null) return true
        val hasPercentKeyword = qualifiedPercent.containsMatchIn(activeText)
        val hasLimitCue = limitCue.containsMatchIn(activeText)
        val hasGrok = grokMarker.containsMatchIn(activeText)
        val hasUpgradeOnlyStub = overallLines.any { it.equals("upgrade", ignoreCase = true) } &&
            !hasGrok &&
            segments.isEmpty() &&
            !hasLimitCue
        if (hasUpgradeOnlyStub) return false
        if (hasGrok && (hasPercentKeyword || segments.isNotEmpty() || overallReset != null || hasLimitCue)) {
            return true
        }
        if (segments.size >= 2 && (hasPercentKeyword || hasLimitCue || overallReset != null)) {
            return true
        }
        if (segments.isNotEmpty() && overall != null && (hasLimitCue || overallReset != null)) {
            return true
        }
        if (hasLimitCue && overall != null && overallReset != null && !overallLines.any { it.equals("upgrade", ignoreCase = true) }) {
            return true
        }
        return false
    }

    private fun cleanLabel(line: String): String {
        return line.trim().trimStart('●', '•', '·', '-', '–', '—', '*', '■', '▪').trim()
    }

    private fun canonical(raw: String): String? = when (raw.trim().lowercase().replace(Regex("\\s+"), " ")) {
        "grok build", "build" -> "Grok Build"
        "grok bot" -> "Grok Bot"
        "chat" -> "Chat"
        "imagine" -> "Imagine"
        "voice" -> "Voice"
        "api" -> "API"
        "cursor" -> "Cursor"
        "bugbot" -> "Bugbot"
        "app builder" -> "App Builder"
        "plugins" -> "Plugins"
        "automations" -> "Automations"
        "tasks" -> "Tasks"
        "3rd party", "third party" -> "3rd party"
        "other" -> "Other"
        else -> null
    }

    private fun productLabel(raw: Any?): String? = when (raw) {
        is Number -> when (raw.toInt()) {
            1 -> "API"
            2 -> "Grok Build"
            3 -> "Plugins"
            4 -> "Chat"
            5 -> "Imagine"
            6 -> "Voice"
            7 -> "App Builder"
            8 -> "Tasks"
            9 -> "Grok Bot"
            10 -> "Cursor"
            11 -> "Bugbot"
            else -> null
        }
        is String -> when (raw.uppercase(Locale.US)) {
            "PRODUCT_API", "API" -> "API"
            "PRODUCT_GROK_BUILD", "GROK_BUILD" -> "Grok Build"
            "PRODUCT_GROK_PLUGINS", "GROK_PLUGINS", "PLUGINS" -> "Plugins"
            "PRODUCT_GROK_CHAT", "GROK_CHAT", "CHAT" -> "Chat"
            "PRODUCT_GROK_IMAGINE", "GROK_IMAGINE", "IMAGINE" -> "Imagine"
            "PRODUCT_GROK_VOICE", "GROK_VOICE", "VOICE" -> "Voice"
            "PRODUCT_GROK_APP_BUILDER", "GROK_APP_BUILDER", "APP_BUILDER" -> "App Builder"
            "PRODUCT_GROK_TASKS", "GROK_TASKS", "TASKS" -> "Tasks"
            "PRODUCT_GROK_BOT", "GROK_BOT" -> "Grok Bot"
            "PRODUCT_CURSOR", "CURSOR" -> "Cursor"
            "PRODUCT_BUGBOT", "BUGBOT" -> "Bugbot"
            "PRODUCT_UNSPECIFIED", "" -> null
            else -> raw.removePrefix("PRODUCT_").removePrefix("GROK_").lowercase(Locale.US)
                .split('_').filter { it.isNotEmpty() }
                .joinToString(" ") { part -> part.replaceFirstChar { it.titlecase(Locale.US) } }
                .takeIf { it.isNotEmpty() }
        }
        else -> null
    }

    private fun parsePercentMatch(match: MatchResult): Float? {
        val lessThan = match.groupValues[1].isNotEmpty()
        val num = match.groupValues[2].replace(',', '.').toFloatOrNull() ?: return null
        val value = if (lessThan && num <= 1f) 0.5f else num
        return value.coerceIn(0f, 100f)
    }

    private fun stripResetPart(line: String): String {
        val prefix = resetPrefix.find(line)
        if (prefix != null) return ""
        val anywhere = resetWordAnywhere.find(line)
        return if (anywhere != null) line.substring(0, anywhere.range.first).trim() else line
    }

    private fun overallPercentFrom(lines: List<String>, segmentLines: Set<Int>): Float? {
        val candidateLines = lines.mapIndexedNotNull { idx, rawLine ->
            if (idx in segmentLines || footnoteCue.containsMatchIn(rawLine)) return@mapIndexedNotNull null
            val line = stripResetPart(rawLine)
            if (line.isEmpty()) null else idx to line
        }
        for ((_, line) in candidateLines) {
            qualifiedPercent.find(line)?.let { match ->
                parsePercentMatch(match)?.let { return it }
            }
        }
        for (i in candidateLines.indices) {
            val (idx, line) = candidateLines[i]
            val match = percentLine.matchEntire(line) ?: continue
            val next = lines.getOrNull(idx + 1).orEmpty()
            if (qualifiedWordOnly.containsMatchIn(next)) {
                parsePercentMatch(match)?.let { return it }
            }
        }
        for ((_, line) in candidateLines) {
            percentToken.find(line)?.let { match ->
                parsePercentMatch(match)?.let { return it }
            }
        }
        return null
    }

    private fun botPercentFrom(lines: List<String>): Float? {
        val text = lines.joinToString("\n")
        qualifiedPercent.find(text)?.let { match ->
            parsePercentMatch(match)?.let { return it }
        }
        lines.forEachIndexed { index, line ->
            val match = percentLine.matchEntire(line) ?: return@forEachIndexed
            val next = lines.getOrNull(index + 1).orEmpty()
            if (qualifiedWordOnly.containsMatchIn(next)) {
                parsePercentMatch(match)?.let { return it }
            }
        }
        for (rawLine in lines) {
            if (footnoteCue.containsMatchIn(rawLine)) continue
            val line = stripResetPart(rawLine)
            if (line.isEmpty()) continue
            percentToken.find(line)?.let { match ->
                parsePercentMatch(match)?.let { return it }
            }
        }
        return null
    }

    private fun segmentsFrom(lines: List<String>): Pair<List<UsageSegment>, Set<Int>> {
        val found = linkedMapOf<String, Float>()
        val consumed = mutableSetOf<Int>()

        lines.forEachIndexed { index, line ->
            val matches = knownSegmentInline.findAll(line).toList()
            if (matches.isNotEmpty()) {
                consumed.add(index)
                for (match in matches) {
                    val label = canonical(match.groupValues[1]) ?: continue
                    val lessThan = match.groupValues[2].isNotEmpty()
                    val rawNum = match.groupValues[3].replace(',', '.').toFloatOrNull() ?: continue
                    val pct = (if (lessThan && rawNum <= 1f) 0.5f else rawNum).coerceIn(0f, 100f)
                    if (label !in found) found[label] = pct
                }
            }
        }

        lines.forEachIndexed { index, line ->
            val label = canonical(line) ?: return@forEachIndexed
            if (label in found) return@forEachIndexed
            val next = lines.getOrNull(index + 1) ?: return@forEachIndexed
            val match = percentLine.matchEntire(next) ?: return@forEachIndexed
            val pct = parsePercentMatch(match) ?: return@forEachIndexed
            found[label] = pct
            consumed.add(index)
            consumed.add(index + 1)
        }

        // Fallback for localized unknown breakdown labels (e.g. "Otros 5%") after the main percent line.
        val firstPctIdx = lines.indexOfFirst { idxLine ->
            percentToken.containsMatchIn(idxLine) && !isResetLine(idxLine)
        }
        if (firstPctIdx >= 0) {
            for (i in (firstPctIdx + 1) until lines.size) {
                if (i in consumed) continue
                val line = lines[i]
                if (isResetLine(line) || footnoteCue.containsMatchIn(line) || limitCue.containsMatchIn(line)) continue
                val inline = Regex("^([^\\d%:·|]{2,26}?)\\s*[:\\-–—]?\\s*(<)?\\s*(\\d+(?:[.,]\\d+)?)\\s*%(?:\\s+.*)?$")
                    .matchEntire(line)
                if (inline != null) {
                    val rawLabel = inline.groupValues[1].trim()
                    if (isPlausibleLocalizedSegment(rawLabel)) {
                        val lessThan = inline.groupValues[2].isNotEmpty()
                        val rawNum = inline.groupValues[3].replace(',', '.').toFloatOrNull()
                        if (rawNum != null) {
                            val label = canonical(rawLabel) ?: rawLabel
                            val pct = (if (lessThan && rawNum <= 1f) 0.5f else rawNum).coerceIn(0f, 100f)
                            if (label !in found) found[label] = pct
                            consumed.add(i)
                        }
                    }
                    continue
                }
                if (isPlausibleLocalizedSegment(line) && (i + 1) !in consumed) {
                    val next = lines.getOrNull(i + 1) ?: continue
                    val nextMatch = percentLine.matchEntire(next) ?: continue
                    val pct = parsePercentMatch(nextMatch) ?: continue
                    val label = canonical(line) ?: line
                    if (label !in found) found[label] = pct
                    consumed.add(i)
                    consumed.add(i + 1)
                }
            }
        }

        return found.map { UsageSegment(it.key, it.value) } to consumed
    }

    private fun isPlausibleLocalizedSegment(text: String): Boolean {
        val clean = text.trim()
        if (clean.length !in 2..26) return false
        if (clean.any { it.isDigit() } || clean.contains('.') || clean.contains(',')) return false
        if (clean.split(Regex("\\s+")).size > 3) return false
        if (isResetLine(clean) || footnoteCue.containsMatchIn(clean) || limitCue.containsMatchIn(clean)) return false
        if (qualifiedWordOnly.containsMatchIn(clean)) return false
        if (clean.equals("usage", ignoreCase = true) || clean.equals("uso", ignoreCase = true) ||
            clean.equals("nutzung", ignoreCase = true) || clean.equals("utilisation", ignoreCase = true) ||
            clean.equals("upgrade", ignoreCase = true)
        ) return false
        return true
    }

    private fun isResetLine(line: String): Boolean {
        return resetPrefix.containsMatchIn(line) || resetWordAnywhere.containsMatchIn(line)
    }

    private fun resetRaw(lines: List<String>): String? {
        lines.forEachIndexed { index, line ->
            val match = resetPrefix.find(line) ?: resetWordAnywhere.find(line) ?: return@forEachIndexed
            val inline = match.groupValues[1]
                .replace(resetSuffix, "")
                .trim()
                .trimEnd('.', '·', '|')
            if (inline.isNotEmpty()) return inline
            val next = lines.getOrNull(index + 1)?.trim().orEmpty()
            if (next.isNotEmpty() && canonical(next) == null && !next.contains("%")) return next
        }
        return null
    }

    private fun numberOf(value: Any?): Float? = when (value) {
        is Number -> value.toFloat()
        is String -> value.toFloatOrNull()
        else -> null
    }

    private fun formatTimestamp(raw: Any?, zone: ZoneId): String? {
        val instant = when (raw) {
            is String -> runCatching { Instant.parse(raw) }.getOrNull()
            is Map<*, *> -> {
                val sec = when (val s = raw["seconds"]) {
                    is Number -> s.toLong()
                    is String -> s.toLongOrNull()
                    else -> null
                } ?: return null
                val nanos = when (val n = raw["nanos"]) {
                    is Number -> n.toLong()
                    is String -> n.toLongOrNull() ?: 0L
                    else -> 0L
                }
                if (sec <= 0L) return null
                Instant.ofEpochSecond(sec, nanos)
            }
            else -> null
        } ?: return null
        return instant.atZone(zone).format(resetJsonFormat)
    }
}

/** Tiny pure-Kotlin JSON parser so UsageParser runs in plain JVM unit tests without org.json. */
private object MiniJson {
    fun parseObject(raw: String): Map<String, Any?>? {
        val parser = Parser(raw)
        val value = runCatching { parser.readValue() }.getOrNull() ?: return null
        @Suppress("UNCHECKED_CAST")
        return value as? Map<String, Any?>
    }

    private class Parser(private val src: String) {
        private var pos = 0

        fun readValue(): Any? {
            skipWs()
            if (pos >= src.length) error("eof")
            return when (val c = src[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't', 'f' -> readBool()
                'n' -> readNull()
                '-', in '0'..'9' -> readNumber()
                else -> error("unexpected $c")
            }
        }

        private fun readObject(): Map<String, Any?> {
            pos++ // {
            val map = linkedMapOf<String, Any?>()
            skipWs()
            if (pos < src.length && src[pos] == '}') {
                pos++
                return map
            }
            while (pos < src.length) {
                skipWs()
                val key = readString()
                skipWs()
                if (pos >= src.length || src[pos] != ':') error("expected :")
                pos++
                map[key] = readValue()
                skipWs()
                if (pos < src.length && src[pos] == ',') {
                    pos++
                    continue
                }
                if (pos < src.length && src[pos] == '}') {
                    pos++
                    return map
                }
                error("expected , or }")
            }
            error("unterminated object")
        }

        private fun readArray(): List<Any?> {
            pos++ // [
            val list = mutableListOf<Any?>()
            skipWs()
            if (pos < src.length && src[pos] == ']') {
                pos++
                return list
            }
            while (pos < src.length) {
                list.add(readValue())
                skipWs()
                if (pos < src.length && src[pos] == ',') {
                    pos++
                    continue
                }
                if (pos < src.length && src[pos] == ']') {
                    pos++
                    return list
                }
                error("expected , or ]")
            }
            error("unterminated array")
        }

        private fun readString(): String {
            if (src[pos] != '"') error("expected \"")
            pos++
            val sb = StringBuilder()
            while (pos < src.length) {
                val c = src[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= src.length) error("eof escape")
                        when (val e = src[pos++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                val hex = src.substring(pos, pos + 4)
                                sb.append(hex.toInt(16).toChar())
                                pos += 4
                            }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
            error("unterminated string")
        }

        private fun readBool(): Boolean {
            return if (src.startsWith("true", pos)) {
                pos += 4
                true
            } else if (src.startsWith("false", pos)) {
                pos += 5
                false
            } else {
                error("bad bool")
            }
        }

        private fun readNull(): Any? {
            if (!src.startsWith("null", pos)) error("bad null")
            pos += 4
            return null
        }

        private fun readNumber(): Double {
            val start = pos
            if (src[pos] == '-') pos++
            while (pos < src.length && src[pos] in '0'..'9') pos++
            if (pos < src.length && src[pos] == '.') {
                pos++
                while (pos < src.length && src[pos] in '0'..'9') pos++
            }
            if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
                pos++
                if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
                while (pos < src.length && src[pos] in '0'..'9') pos++
            }
            return src.substring(start, pos).toDouble()
        }

        private fun skipWs() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }
    }
}
