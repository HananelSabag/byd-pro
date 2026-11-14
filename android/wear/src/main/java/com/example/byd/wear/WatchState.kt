package com.example.byd.wear

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Base64
import androidx.core.content.edit
import com.google.android.gms.wearable.DataMap
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** The car as the phone last published it (the phone's WearSync writes these keys). */
data class WatchState(
    val name: String = "ATTO 3",
    val soc: Int? = null,
    val rangeKm: Int? = null,
    val locked: Boolean? = null,
    val trunkOpen: Boolean? = null,
    val climateOn: Boolean? = null,
    val heating: Boolean? = null, // null while off, or when the mode isn't known
    val climateUntil: Long = 0L,
    val charging: Boolean? = null,
    val chargeKw: Double = 0.0,
    val pending: Set<String> = emptySet(), // commands the phone is sending right now (from anywhere)
    val answers: Map<String, Answer> = emptyMap(), // 2.7 phones: the car's last answer per command
    val records: Map<String, Record> = emptyMap(), // 2.8 phones: this watch's commands, by request id
    val updatedAt: Long = 0L,
    /** 2 = a 2.8 phone (ids and acks); 1 = a 2.7 phone. */
    val proto: Int = 1,
    /** When the phone published this (2.8 phones); 0 = unknown. */
    val published: Long = 0L,
) {
    data class Answer(val ok: Boolean, val error: String?, val at: Long)

    /** One command as the phone reports it, in the phone's words (its outcome table). */
    data class Record(
        val id: String,
        val command: String,
        val stage: String,
        val tone: String,
        val label: String,
        val message: String,
        val updated: Long,
        val terminal: Boolean,
    )

    val hasData: Boolean get() = updatedAt > 0L || soc != null

    /** A phone that may be gone (its process died mid-command) must not keep buttons spinning forever. */
    fun livePending(now: Long = System.currentTimeMillis()): Set<String> =
        if (published > 0 && now - published > Taps.FINAL_TIMEOUT_MS) emptySet() else pending

    /** Minutes left of an A/C run the phone started, or null. */
    fun minutesLeft(now: Long = System.currentTimeMillis()): Int? =
        climateUntil.takeIf { climateOn == true && it > now }?.let { ((it - now + 59_999) / 60_000).toInt() }

    companion object {
        fun from(map: DataMap) = WatchState(
            name = map.getString("name", "ATTO 3"),
            soc = map.getInt("soc", -1).takeIf { it >= 0 },
            rangeKm = map.getInt("range", -1).takeIf { it >= 0 },
            locked = map.flag("locked"),
            trunkOpen = map.flag("trunk"),
            climateOn = map.flag("climate"),
            heating = when (map.getString("mode", "")) {
                "heat" -> true
                "cool" -> false
                else -> null
            },
            climateUntil = map.getLong("until", 0L),
            charging = map.flag("charging"),
            chargeKw = map.getDouble("kw", 0.0),
            pending = map.getStringArray("pending")?.toSet().orEmpty(),
            answers = PhoneLink.COMMANDS.filter { map.containsKey("at_$it") }.associateWith {
                Answer(map.getBoolean("ok_$it", false), map.getString("err_$it", "").ifEmpty { null }, map.getLong("at_$it", 0L))
            },
            records = map.getDataMapArrayList("records").orEmpty().associate { r ->
                r.getString("id", "") to Record(
                    id = r.getString("id", ""),
                    command = r.getString("cmd", ""),
                    stage = r.getString("stage", ""),
                    tone = r.getString("tone", ""),
                    label = r.getString("label", ""),
                    message = r.getString("msg", ""),
                    updated = r.getLong("updated", 0L),
                    terminal = r.getBoolean("final", false),
                )
            },
            updatedAt = map.getLong("updated", 0L),
            proto = map.getInt("proto", 1),
            published = map.getLong("published", 0L),
        )

        private fun DataMap.flag(key: String): Boolean? = when (getInt(key, -1)) {
            1 -> true
            0 -> false
            else -> null
        }
    }
}

/** The last state, kept on the watch so the app and the tile draw at once, even offline. */
object StateCache {
    private fun prefs(context: Context) = context.getSharedPreferences("state", Context.MODE_PRIVATE)

    fun save(context: Context, map: DataMap) =
        prefs(context).edit { putString("map", Base64.encodeToString(map.toByteArray(), Base64.NO_WRAP)) }

    fun load(context: Context): WatchState = prefs(context).getString("map", null)
        ?.let { runCatching { WatchState.from(DataMap.fromByteArray(Base64.decode(it, Base64.NO_WRAP))) }.getOrNull() }
        ?: WatchState()
}

/**
 * One tap on the watch (app or tile), followed until the phone reports its final answer.
 * [legacy]: sent to a 2.7 phone, which knows no ids; its answer is matched the old way.
 */
data class Tap(
    val id: String,
    val command: String,
    val at: Long,
    val legacy: Boolean,
    val ackedAt: Long = 0L,
    val doneAt: Long = 0L,
    /** Final look: the phone's tone (OK / WARN / ERR) and words, or a [local] reason. */
    val tone: String = "",
    val label: String = "",
    val message: String = "",
    val local: String = "",
    /** Given up for lack of an ack, but the phone may still answer: its answer then wins. */
    val revivable: Boolean = false,
) {
    val done: Boolean get() = doneAt > 0L

    fun toJson(): String = JSONObject().apply {
        put("id", id); put("cmd", command); put("at", at); put("legacy", legacy)
        put("acked", ackedAt); put("done", doneAt); put("tone", tone); put("label", label)
        put("msg", message); put("local", local); put("revivable", revivable)
    }.toString()

    companion object {
        const val LOCAL_NO_PHONE = "no_phone" // the message couldn't leave the watch
        const val LOCAL_NO_ACK = "no_ack" // sent, but the phone never said it got it
        const val LOCAL_NO_ANSWER = "no_answer" // acked, then no final answer
        const val LOCAL_REFUSED = "refused"
        const val LOCAL_LEGACY_OK = "legacy_ok"
        const val LOCAL_LEGACY_FAIL = "legacy_fail"

        fun fromJson(text: String): Tap? = runCatching {
            val o = JSONObject(text)
            Tap(
                id = o.getString("id"), command = o.getString("cmd"), at = o.getLong("at"), legacy = o.optBoolean("legacy"),
                ackedAt = o.optLong("acked"), doneAt = o.optLong("done"), tone = o.optString("tone"),
                label = o.optString("label"), message = o.optString("msg"), local = o.optString("local"),
                revivable = o.optBoolean("revivable"),
            )
        }.getOrNull()
    }
}

/** A tap that just got its final answer: buzz [tone] once (unless [quiet]: found out long after). */
data class Settled(val tap: Tap, val tone: String, val quiet: Boolean)

/** The rules for following a tap; pure, so they can be checked without a watch. */
object TapRules {
    fun settle(taps: List<Tap>, state: WatchState, now: Long): Pair<List<Tap>, List<Settled>> {
        val settled = mutableListOf<Settled>()
        val next = taps.mapNotNull { tap ->
            when {
                tap.done && !tap.revivable -> tap.takeIf { now - tap.doneAt < Taps.KEEP_MS }
                tap.legacy -> legacy(tap, state, now, settled)
                else -> byId(tap, state, now, settled)
            }
        }
        return next to settled
    }

    private fun byId(tap: Tap, state: WatchState, now: Long, settled: MutableList<Settled>): Tap? {
        val record = state.records[tap.id]
        if (record != null) {
            // The phone has it: that's an ack too, and it overrules an earlier "phone not reachable".
            var t = if (tap.ackedAt == 0L) tap.copy(ackedAt = now) else tap
            if (t.done && t.revivable) t = t.copy(doneAt = 0L, revivable = false, local = "", tone = "", label = "", message = "")
            if (!record.terminal) return t
            if (t.done) return t
            val done = t.copy(doneAt = now, tone = record.tone, label = record.label, message = record.message)
            settled += Settled(done, record.tone, quiet = false)
            return done
        }
        return when {
            tap.done -> tap.takeIf { now - tap.doneAt < Taps.KEEP_MS }?.let {
                if (now - it.doneAt > Taps.REVIVE_MS) it.copy(revivable = false) else it
            }
            tap.ackedAt == 0L && now - tap.at > Taps.ACK_TIMEOUT_MS -> {
                val done = tap.copy(doneAt = now, tone = TONE_ERR, local = Tap.LOCAL_NO_ACK, revivable = true)
                settled += Settled(done, TONE_ERR, quiet = now - tap.at > Taps.ACK_TIMEOUT_MS + LATE_MS)
                done
            }
            tap.ackedAt > 0L && now - tap.ackedAt > Taps.FINAL_TIMEOUT_MS -> {
                val done = tap.copy(doneAt = now, tone = TONE_WARN, local = Tap.LOCAL_NO_ANSWER)
                settled += Settled(done, TONE_WARN, quiet = now - tap.ackedAt > Taps.FINAL_TIMEOUT_MS + LATE_MS)
                done
            }
            else -> tap
        }
    }

    /** A 2.7 phone: its last answer per command, from about the tap on, counts. */
    private fun legacy(tap: Tap, state: WatchState, now: Long, settled: MutableList<Settled>): Tap {
        val answer = state.answers[tap.command]
        return when {
            answer != null && answer.at >= tap.at - LEGACY_CLOCK_SLACK_MS && tap.command !in state.pending -> {
                val tone = if (answer.ok) TONE_OK else TONE_ERR
                val done = tap.copy(doneAt = now, tone = tone, local = if (answer.ok) Tap.LOCAL_LEGACY_OK else Tap.LOCAL_LEGACY_FAIL)
                settled += Settled(done, tone, quiet = false)
                done
            }
            now - tap.at > Taps.LEGACY_TIMEOUT_MS -> {
                val done = tap.copy(doneAt = now, tone = TONE_ERR, local = Tap.LOCAL_NO_ANSWER)
                settled += Settled(done, TONE_ERR, quiet = now - tap.at > Taps.LEGACY_TIMEOUT_MS + LATE_MS)
                done
            }
            else -> tap
        }
    }

    const val TONE_OK = "OK"
    const val TONE_WARN = "WARN"
    const val TONE_ERR = "ERR"

    /** A timeout noticed this long after it happened (the watch app wasn't running) settles without a buzz. */
    private const val LATE_MS = 60_000L
    private const val LEGACY_CLOCK_SLACK_MS = 5_000L
}

/**
 * Taps on the watch (app or tile) waiting for the car's answer. Kept on disk because the answer
 * may arrive after the app closed, or for a tile tap; whoever sees it first buzzes, once.
 */
object Taps {
    /** The phone acks a 2.8 tap within a second or two; none after this: "phone not reachable". */
    const val ACK_TIMEOUT_MS = 8_000L

    /** After an ack the phone always reports a final answer; this is only a safety net. */
    const val FINAL_TIMEOUT_MS = 4 * 60_000L

    /** A 2.7 phone sends no ack: its answer is waited for this long, as in 2.7. */
    const val LEGACY_TIMEOUT_MS = 45_000L

    /** A "phone not reachable" is overruled by a late answer from the phone this long. */
    const val REVIVE_MS = 3 * 60_000L
    const val KEEP_MS = 10 * 60_000L

    /** How long the last answer stays on the status line. */
    const val SHOW_MS = 6_000L

    private fun prefs(context: Context) = context.getSharedPreferences("taps2", Context.MODE_PRIVATE)

    @Synchronized
    fun all(context: Context): List<Tap> =
        prefs(context).all.values.mapNotNull { (it as? String)?.let(Tap::fromJson) }.sortedBy { it.at }

    @Synchronized
    fun start(context: Context, command: String, legacy: Boolean): Tap {
        val tap = Tap(UUID.randomUUID().toString(), command, System.currentTimeMillis(), legacy)
        save(context, tap)
        WatchLog.log(context, "tap ${tap.command} ${tap.id.take(8)}${if (legacy) " (2.7 phone)" else ""}")
        return tap
    }

    /** The message never left the watch: no phone in reach. */
    @Synchronized
    fun notSent(context: Context, id: String): Settled? {
        val tap = find(context, id) ?: return null
        val done = tap.copy(doneAt = System.currentTimeMillis(), tone = TapRules.TONE_ERR, local = Tap.LOCAL_NO_PHONE)
        save(context, done)
        WatchLog.log(context, "tap ${id.take(8)}: no phone in reach")
        return Settled(done, TapRules.TONE_ERR, quiet = false)
    }

    /** The phone's "got it". REFUSED: it won't run that command. */
    @Synchronized
    fun ack(context: Context, id: String, state: String): Settled? {
        val tap = find(context, id) ?: return null
        WatchLog.log(context, "ack ${id.take(8)} $state")
        if (state == PhoneLink.ACK_REFUSED) {
            if (tap.done && !tap.revivable) return null
            val done = tap.copy(ackedAt = System.currentTimeMillis(), doneAt = System.currentTimeMillis(), tone = TapRules.TONE_ERR, local = Tap.LOCAL_REFUSED, revivable = false)
            save(context, done)
            return Settled(done, TapRules.TONE_ERR, quiet = false)
        }
        var next = if (tap.ackedAt == 0L) tap.copy(ackedAt = System.currentTimeMillis()) else tap
        if (next.done && next.revivable) next = next.copy(doneAt = 0L, revivable = false, local = "", tone = "")
        save(context, next)
        return null
    }

    /** Applies the phone's newest state to every open tap; returns the ones that just got their answer. */
    @Synchronized
    fun settle(context: Context, state: WatchState = StateCache.load(context)): List<Settled> {
        val before = all(context)
        val (after, settled) = TapRules.settle(before, state, System.currentTimeMillis())
        val keep = after.associateBy { it.id }
        prefs(context).edit {
            before.filter { it.id !in keep }.forEach { remove(it.id) }
            after.forEach { if (it != before.firstOrNull { b -> b.id == it.id }) putString(it.id, it.toJson()) }
        }
        settled.forEach { WatchLog.log(context, "answer ${it.tap.id.take(8)} ${it.tap.command}: ${it.tone} ${it.tap.local.ifEmpty { it.tap.label }}") }
        return settled
    }

    /** Commands tapped here and not answered yet. */
    fun waiting(context: Context): Set<String> = all(context).filter { !it.done }.map { it.command }.toSet()

    /** The tap the status line is about: one still open, or the last answer for a few seconds. */
    fun latest(context: Context, now: Long = System.currentTimeMillis()): Tap? =
        all(context).lastOrNull()?.takeIf { !it.done || now - it.doneAt < SHOW_MS }

    private fun find(context: Context, id: String): Tap? = prefs(context).getString(id, null)?.let(Tap::fromJson)

    private fun save(context: Context, tap: Tap) = prefs(context).edit { putString(tap.id, tap.toJson()) }
}

/** Short, distinct buzzes: tap, done, not sure, didn't work. */
object Buzz {
    fun tap(context: Context) = play(context, VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))

    fun result(context: Context, ok: Boolean) = tone(context, if (ok) TapRules.TONE_OK else TapRules.TONE_ERR)

    /** The phone's outcome tone (OK / WARN / ERR), as in its outcome table. */
    fun tone(context: Context, tone: String) = play(
        context,
        when (tone) {
            TapRules.TONE_OK -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
            TapRules.TONE_WARN -> VibrationEffect.createWaveform(longArrayOf(0, 40, 120, 40), -1)
            else -> VibrationEffect.createWaveform(longArrayOf(0, 60, 80, 60, 80, 60), -1)
        },
    )

    fun settled(context: Context, list: List<Settled>) = list.filter { !it.quiet }.forEach { tone(context, it.tone) }

    private fun play(context: Context, effect: VibrationEffect) {
        context.getSystemService(Vibrator::class.java)?.takeIf { it.hasVibrator() }?.vibrate(effect)
    }
}

/** The watch's own debug log: a small rotating file the phone can ask for (Settings → Share debug log). */
object WatchLog {
    private const val MAX_BYTES = 48 * 1024L
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun log(context: Context, message: String) {
        try {
            val file = File(context.filesDir, "watch.log")
            if (file.length() > MAX_BYTES) {
                val old = File(context.filesDir, "watch.1.log")
                old.delete()
                file.renameTo(old)
            }
            file.appendText("${time.format(Date())} $message\n")
        } catch (e: Exception) {
            // Logging must never break a tap.
        }
    }

    @Synchronized
    fun text(context: Context): String =
        listOf("watch.1.log", "watch.log").map { File(context.filesDir, it) }.filter { it.exists() }.joinToString("") { it.readText() }
}
