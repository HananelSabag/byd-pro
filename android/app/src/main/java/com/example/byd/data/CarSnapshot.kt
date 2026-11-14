package com.example.byd.data

import org.json.JSONObject
import kotlin.math.roundToInt

/** Today's count from the server: car reads (each wakes the car), commands, and KB exchanged with BYD. */
data class Usage(val carReads: Int, val commands: Int, val kb: Double)

data class Tires(val fl: Double?, val fr: Double?, val rl: Double?, val rr: Double?, val unit: String)

/** Each door's open state; null = the car didn't report it. */
data class Doors(val lf: Boolean?, val rf: Boolean?, val lr: Boolean?, val rr: Boolean?)

/** Each window's open state (any opening counts); null = not reported. */
data class Windows(val lf: Boolean?, val rf: Boolean?, val lr: Boolean?, val rr: Boolean?)

/** The car's smart-charging schedule. [end] is "HH:MM" or "full". */
data class ChargeSchedule(val enabled: Boolean, val start: String?, val end: String?)

/** What the car supports, from the server. `null` = unknown, so the control is shown anyway. */
data class Caps(
    val trunk: Boolean? = null,
    val locate: Boolean? = null,
    val flash: Boolean? = null,
    val closeWindows: Boolean? = null,
    val ventWindows: Boolean? = null,
    val seatDriver: Boolean? = null,
    val seatPassenger: Boolean? = null,
    val charge: Boolean? = null,
)

/** Last known state of the car. Every field is optional: the server only sends what it knows. */
data class CarSnapshot(
    val name: String = "ATTO 3",
    val soc: Int? = null,
    val rangeKm: Int? = null,
    val odometerKm: Int? = null,
    val cabinC: Int? = null,
    val outsideC: Int? = null,
    val locked: Boolean? = null,
    val doorsOpen: Boolean? = null,
    val doors: Doors? = null,
    val trunkOpen: Boolean? = null,
    val windowsOpen: Boolean? = null,
    val windows: Windows? = null,
    val charging: Boolean? = null,
    val plugged: Boolean? = null,
    val schedule: ChargeSchedule? = null,
    val seatDriver: Int? = null, // 0 off, 1 low, 2 high
    val seatPassenger: Int? = null,
    val chargeEtaMin: Int? = null,
    /** The A/C as last reported. Prefer [climateOn], which also knows when a run has ended. */
    val climateReported: Boolean? = null,
    val climateUntil: Long? = null, // epoch millis when the A/C started from this app runs out
    val climateSetC: Int? = null, // the A/C's set temperature, as the car reports it
    val recirc: Boolean? = null, // A/C air source: recirculating (true) or outside air
    val chargeKw: Double? = null, // charging power; 0 when not charging
    val efficiency: Double? = null,
    val tires: Tires? = null,
    val usage: Usage? = null, // what the server asked of the car today (server 3.0+)
    val updatedAt: Long = 0L, // epoch millis of the server's newest data
    val caps: Caps = Caps(),
) {
    val hasData: Boolean get() = updatedAt > 0L

    /** A/C state, off once the run we started is over: no car read needed to know that. */
    val climateOn: Boolean?
        get() = if (climateReported == true && climateUntil != null && System.currentTimeMillis() > climateUntil) false else climateReported

    /** Charging power worth showing ("10.4 kW"), only while charging. */
    val chargingKw: Double?
        get() = chargeKw?.takeIf { charging == true && it > 0.0 }

    companion object {
        /**
         * A/C fields the server always sends when it knows them. When it leaves one out it doesn't
         * know it (e.g. right after a restart), so the old value goes too: a stale "A/C on" must not
         * stay on screen.
         */
        private val MIRRORED = listOf("climate_on", "climate_until", "climate_set_c", "recirc", "charge_kw")

        /** Reads the state document stored by [AppPrefs] (same shape as a server response). */
        fun from(doc: JSONObject): CarSnapshot {
            val car = doc.optJSONObject("car") ?: JSONObject()
            val caps = doc.optJSONObject("caps") ?: JSONObject()
            val tires = car.optJSONObject("tires")
            val doors = car.optJSONObject("doors")
            val windows = car.optJSONObject("windows")
            val seats = car.optJSONObject("seat_heat")
            val schedule = car.optJSONObject("schedule")
            return CarSnapshot(
                name = doc.optString("name").ifBlank { "ATTO 3" },
                soc = car.int("soc"),
                rangeKm = car.int("range_km"),
                odometerKm = car.int("odometer_km"),
                cabinC = car.int("cabin_c"),
                outsideC = car.int("outside_c"),
                locked = car.bool("locked"),
                doorsOpen = car.bool("doors_open"),
                doors = doors?.let { Doors(it.bool("lf"), it.bool("rf"), it.bool("lr"), it.bool("rr")) },
                trunkOpen = car.bool("trunk_open"),
                windowsOpen = car.bool("windows_open"),
                windows = windows?.let { Windows(it.bool("lf"), it.bool("rf"), it.bool("lr"), it.bool("rr")) },
                charging = car.bool("charging"),
                plugged = car.bool("plugged"),
                schedule = schedule?.let {
                    ChargeSchedule(it.optBoolean("enabled"), it.str("start"), it.str("end"))
                },
                seatDriver = seats?.int("driver"),
                seatPassenger = seats?.int("passenger"),
                chargeEtaMin = car.int("charge_eta_min"),
                climateReported = car.bool("climate_on"),
                climateUntil = car.int("climate_until")?.let { it * 1000L },
                climateSetC = car.int("climate_set_c"),
                recirc = car.bool("recirc"),
                chargeKw = car.double("charge_kw"),
                efficiency = car.double("efficiency_kwh100"),
                tires = tires?.let {
                    Tires(it.double("fl"), it.double("fr"), it.double("rl"), it.double("rr"), it.optString("unit", "bar"))
                },
                updatedAt = (doc.optLong("updated", 0L)) * 1000L,
                usage = doc.optJSONObject("usage")?.let { Usage(it.optInt("car_reads"), it.optInt("commands"), it.optDouble("kb", 0.0)) },
                caps = Caps(
                    trunk = caps.bool("open_trunk"),
                    locate = caps.bool("find_car"),
                    flash = caps.bool("flash_lights"),
                    closeWindows = caps.bool("close_windows"),
                    ventWindows = caps.bool("open_windows"),
                    seatDriver = caps.bool("driver_seat_heat"),
                    seatPassenger = caps.bool("passenger_seat_heat"),
                    charge = caps.bool("start_charge"),
                ),
            )
        }

        /**
         * Folds a server response into the stored document. Fields the server left out keep their
         * last value. [mirror]: a status read, where a missing A/C field means "unknown". A command's
         * reply right after a server restart carries only that command's effect, so it never wipes.
         */
        fun merge(stored: JSONObject, response: JSONObject, mirror: Boolean = true): JSONObject {
            val merged = JSONObject(stored.toString())
            val car = merged.optJSONObject("car") ?: JSONObject().also { merged.put("car", it) }
            response.optJSONObject("car")?.let { incoming ->
                incoming.keys().forEach { key -> car.put(key, incoming.get(key)) }
                if (mirror) MIRRORED.forEach { key -> if (!incoming.has(key)) car.remove(key) }
            }
            response.optJSONObject("caps")?.let { merged.put("caps", it) }
            response.optJSONObject("usage")?.let { merged.put("usage", it) }
            response.optString("name").takeIf { it.isNotBlank() }?.let { merged.put("name", it) }
            if (response.has("updated") && !response.isNull("updated")) merged.put("updated", response.getLong("updated"))
            return merged
        }
    }
}

private fun JSONObject.double(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key).takeUnless { it.isNaN() } else null

private fun JSONObject.int(key: String): Int? = double(key)?.roundToInt()

private fun JSONObject.bool(key: String): Boolean? = if (has(key) && !isNull(key)) optBoolean(key) else null

private fun JSONObject.str(key: String): String? = if (has(key) && !isNull(key)) optString(key) else null
