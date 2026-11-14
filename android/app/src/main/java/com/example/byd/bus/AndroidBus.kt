package com.example.byd.bus

import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit
import com.example.byd.data.ApiError
import com.example.byd.data.CarCommand
import org.json.JSONArray
import org.json.JSONObject

/** The bus's clocks on Android. */
object AndroidClock : BusClock {
    override fun wall(): Long = System.currentTimeMillis()
    override fun mono(): Long = SystemClock.elapsedRealtime()
}

/** Commands kept across the app's death, as one small JSON list in their own preferences file. */
class PrefsRecordStore(context: Context) : RecordStore {
    private val prefs = context.applicationContext.getSharedPreferences("bus", Context.MODE_PRIVATE)

    override fun load(): List<CommandRecord> {
        val text = prefs.getString(KEY, null) ?: return emptyList()
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let(::read) }
    }

    override fun save(records: List<CommandRecord>) {
        val array = JSONArray()
        records.forEach { array.put(write(it)) }
        prefs.edit { putString(KEY, array.toString()) }
    }

    private fun write(r: CommandRecord) = JSONObject().apply {
        put("id", r.id)
        put("cmd", r.command.name)
        put("params", JSONObject(r.params))
        put("src", r.source.name)
        put("stage", r.stage.name)
        r.outcome?.let { put("outcome", it.name) }
        r.error?.let { put("error", it.name) }
        put("created", r.createdAt)
        put("tapped", r.tappedAt)
        put("updated", r.updatedAt)
        r.finishedAt?.let { put("finished", it) }
        put("attempts", r.attempts)
        put("rid", r.rid)
        put("job", r.jobAccepted)
        put("late", r.late)
        put("joined", JSONArray(r.joined))
        put("joinedSrc", JSONArray(r.joinedSources.map { it.name }))
        r.expectKey?.let { put("expectKey", it) }
        r.expectValue?.let { put("expectValue", it) }
        put("feedback", r.feedback)
    }

    private fun read(o: JSONObject): CommandRecord? {
        val command = CarCommand.fromName(o.optString("cmd")) ?: return null
        val params = o.optJSONObject("params")?.let { p -> p.keys().asSequence().associateWith { p.optString(it) } }.orEmpty()
        return CommandRecord(
            id = o.optString("id").ifBlank { return null },
            command = command,
            params = params,
            source = Source.entries.firstOrNull { it.name == o.optString("src") } ?: Source.APP,
            stage = Stage.entries.firstOrNull { it.name == o.optString("stage") } ?: Stage.DONE,
            outcome = Outcome.entries.firstOrNull { it.name == o.optString("outcome") },
            error = ApiError.fromName(o.optString("error")),
            createdAt = o.optLong("created"),
            tappedAt = o.optLong("tapped"),
            updatedAt = o.optLong("updated"),
            finishedAt = if (o.has("finished")) o.optLong("finished") else null,
            attempts = o.optInt("attempts"),
            rid = o.optString("rid").ifBlank { o.optString("id") },
            jobAccepted = o.optBoolean("job"),
            late = o.optBoolean("late"),
            joined = o.optJSONArray("joined")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
            joinedSources = o.optJSONArray("joinedSrc")?.let { a ->
                (0 until a.length()).mapNotNull { i -> Source.entries.firstOrNull { it.name == a.optString(i) } }.toSet()
            }.orEmpty(),
            expectKey = o.optString("expectKey").ifBlank { null },
            expectValue = if (o.has("expectValue")) o.optBoolean("expectValue") else null,
            feedback = o.optBoolean("feedback"),
        ).let { if (it.stage == Stage.DONE && it.outcome == null) it.copy(outcome = Outcome.UNCONFIRMED) else it }
    }

    private companion object {
        const val KEY = "records"
    }
}
