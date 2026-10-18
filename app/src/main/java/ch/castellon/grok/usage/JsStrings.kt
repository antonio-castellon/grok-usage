package ch.castellon.grok.usage

/** evaluateJavascript returns a JSON string. Decode it without an Android JSON library. */
internal fun decodeJsResult(raw: String?): String {
    if (raw.isNullOrBlank() || raw == "null") return ""
    if (raw.length < 2 || raw.first() != '"' || raw.last() != '"') return raw
    return buildString {
        var index = 1
        val end = raw.length - 1
        while (index < end) {
            val char = raw[index]
            if (char == '\\' && index + 1 < end) {
                index++
                when (val next = raw[index]) {
                    'n' -> append('\n')
                    'r' -> append('\r')
                    't' -> append('\t')
                    'b' -> append('\b')
                    'f' -> append('\u000C')
                    '\\', '"', '/' -> append(next)
                    'u' -> {
                        if (index + 4 < end) {
                            val hex = raw.substring(index + 1, index + 5)
                            val code = hex.toIntOrNull(16)
                            if (code != null) append(code.toChar())
                            index += 4
                        }
                    }
                    else -> append(next)
                }
            } else {
                append(char)
            }
            index++
        }
    }
}
