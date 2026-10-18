package ch.castellon.grok.billing

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class UsageStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): UsageSnapshot? {
        val raw = prefs.getString(KEY, null) ?: return null
        return runCatching { decode(raw) }.getOrNull()
    }

    fun save(snapshot: UsageSnapshot) {
        prefs.edit().putString(KEY, encode(snapshot)).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY).remove(STARTED).remove(ACCOUNT).apply()
    }

    fun forgetSnapshot() {
        prefs.edit().remove(KEY).apply()
    }

    fun loadAccount(): String? = prefs.getString(ACCOUNT, null)?.takeIf { it.isNotBlank() }

    fun saveAccount(name: String) {
        prefs.edit().putString(ACCOUNT, name).apply()
    }

    fun signInStartedAt(): Long = prefs.getLong(STARTED, 0L)

    fun markSignInStarted(nowEpochMs: Long) {
        prefs.edit().putLong(STARTED, nowEpochMs).apply()
    }

    fun clearSignInStarted() {
        prefs.edit().remove(STARTED).apply()
    }

    fun loadNotify(): Boolean = prefs.getBoolean(NOTIFY, false)

    fun saveNotify(enabled: Boolean) {
        prefs.edit().putBoolean(NOTIFY, enabled).apply()
    }

    private fun encode(snapshot: UsageSnapshot): String {
        val segments = JSONArray()
        snapshot.segments.forEach { segment ->
            segments.put(
                JSONObject()
                    .put("label", segment.label)
                    .put("percent", segment.percent.toDouble()),
            )
        }
        return JSONObject()
            .put("overallPercent", snapshot.overallPercent?.toDouble() ?: JSONObject.NULL)
            .put("overallResetRaw", snapshot.overallResetRaw ?: JSONObject.NULL)
            .put("segments", segments)
            .put("botPercent", snapshot.botPercent?.toDouble() ?: JSONObject.NULL)
            .put("botResetRaw", snapshot.botResetRaw ?: JSONObject.NULL)
            .put("updatedAtEpochMs", snapshot.updatedAtEpochMs)
            .toString()
    }

    private fun decode(raw: String): UsageSnapshot {
        val json = JSONObject(raw)
        val segmentsJson = json.optJSONArray("segments") ?: JSONArray()
        val segments = buildList {
            for (index in 0 until segmentsJson.length()) {
                val item = segmentsJson.getJSONObject(index)
                add(UsageSegment(item.getString("label"), item.getDouble("percent").toFloat()))
            }
        }
        return UsageSnapshot(
            overallPercent = json.floatOrNull("overallPercent"),
            overallResetRaw = json.stringOrNull("overallResetRaw"),
            segments = segments,
            botPercent = json.floatOrNull("botPercent"),
            botResetRaw = json.stringOrNull("botResetRaw"),
            updatedAtEpochMs = json.optLong("updatedAtEpochMs"),
        )
    }

    private fun JSONObject.floatOrNull(name: String): Float? {
        if (!has(name) || isNull(name)) return null
        return getDouble(name).toFloat()
    }

    private fun JSONObject.stringOrNull(name: String): String? {
        if (!has(name) || isNull(name)) return null
        return getString(name)
    }

    private companion object {
        const val PREFS = "usage"
        const val KEY = "snapshot"
        const val STARTED = "signInStartedAt"
        const val ACCOUNT = "accountLabel"
        const val NOTIFY = "notifyEnabled"
    }
}
