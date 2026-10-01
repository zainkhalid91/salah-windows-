package salah.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import salah.core.DaySchedule
import salah.core.HijriDate
import salah.core.NextPrayer
import salah.core.Prayer
import salah.core.PrayerClockState
import salah.core.SalahConfig
import salah.core.SavedLocation
import salah.core.TimeFormatting
import salah.core.weekdayName
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Stable JSON schema for `--json`, identical to the macOS CLI. Optional fields are always present
 * and encoded as `null` rather than omitted. Keys are sorted.
 */
object JsonOutput {
    const val SCHEMA_VERSION = 1
    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    fun encode(e: JsonElement): String = pretty.encodeToString(JsonElement.serializer(), sorted(e)) + "\n"

    private fun sorted(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.entries.sortedBy { it.key }.associate { it.key to sorted(it.value) })
        is JsonArray -> JsonArray(e.map { sorted(it) })
        else -> e
    }

    fun location(l: SavedLocation) = buildJsonObject {
        put("name", l.name); put("latitude", l.latitude); put("longitude", l.longitude); put("timeZone", l.timeZone)
    }

    fun method(config: SalahConfig) = buildJsonObject {
        val c = config.calculation
        put("id", c.resolvedMethod(config.location).raw)
        put("name", c.methodName(config.location))
        put("automatic", c.method == null)
        put("madhab", c.madhab.raw)
        put("highLatitudeRule", c.highLatitudeRule?.raw ?: "auto")
        put("offsets", JsonObject(c.offsets.mapValues { JsonPrimitive(it.value) }))
    }

    fun hijri(d: LocalDate, adjustment: Int) = buildJsonObject {
        val h = HijriDate.of(d, adjustment)
        put("day", h.day); put("month", h.month); put("monthName", h.monthName); put("year", h.year)
        put("adjustment", adjustment); put("formatted", h.formatted)
    }

    fun day(s: DaySchedule, config: SalahConfig) = buildJsonObject {
        put("date", s.date.toString())
        put("weekday", s.date.weekdayName)
        put("hijri", hijri(s.date, config.display.hijriAdjustment))
        put("prayers", JsonArray(Prayer.entries.map { p ->
            buildJsonObject {
                put("prayer", p.raw)
                put("label", s.label(p, config.display.jumuahRelabel))
                put("isPrayer", p.isPrayer)
                put("time", s.time(p)?.let { JsonPrimitive(TimeFormatting.iso8601(it, s.zone)) } ?: JsonNull)
            }
        }))
        put("undefined", JsonArray(s.undefined.map { JsonPrimitive(it.raw) }))
    }

    fun next(n: NextPrayer?, now: Instant, zone: ZoneId, jumuahRelabel: Boolean): JsonElement = n?.let {
        buildJsonObject {
            put("prayer", it.prayer.raw)
            put("label", it.label(jumuahRelabel))
            put("time", TimeFormatting.iso8601(it.time, zone))
            put("isTomorrow", it.isTomorrow)
            put("secondsRemaining", Math.floor(it.secondsRemaining(now)).toLong())
        }
    } ?: JsonNull

    fun now(state: PrayerClockState, config: SalahConfig): JsonElement {
        val p = state.nowPrayer ?: return JsonNull
        val t = state.today.time(p) ?: return JsonNull
        val loc = config.location ?: return JsonNull
        return buildJsonObject {
            put("prayer", p.raw)
            put("label", state.today.label(p, config.display.jumuahRelabel))
            put("time", TimeFormatting.iso8601(t, loc.zone))
            put("secondsSince", (state.secondsSinceNow ?: 0.0).toLong())
        }
    }
}
