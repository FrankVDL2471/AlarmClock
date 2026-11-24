package nl.home.alarmclock

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

/**
 * An alarm. When [days] is empty it is a one-time alarm that rings on [date] and then removes itself.
 */
data class Alarm(
    val id: String,
    val hour: Int,
    val minute: Int,
    val days: Set<DayOfWeek> = emptySet(),
    val date: LocalDate? = null,
    val sound: String? = null,
    val volume: Int? = null,
    val label: String = "",
    val enabled: Boolean = true,
) {
    val oneShot: Boolean get() = days.isEmpty()

    val timeText: String get() = String.format(Locale.ROOT, "%02d:%02d", hour, minute)

    /** First moment strictly after [after] at which this alarm rings, or null if it never will. */
    fun nextTrigger(after: LocalDateTime): LocalDateTime? {
        if (!enabled) return null
        val time = LocalTime.of(hour, minute)
        if (oneShot) {
            val t = date?.atTime(time) ?: return null
            return if (t.isAfter(after)) t else null
        }
        var d = after.toLocalDate()
        repeat(8) {
            val t = d.atTime(time)
            if (d.dayOfWeek in days && t.isAfter(after)) return t
            d = d.plusDays(1)
        }
        return null
    }

    /** A one-time alarm whose moment has passed. */
    fun isExpired(now: LocalDateTime): Boolean {
        if (!oneShot) return false
        val t = date?.atTime(hour, minute) ?: return true
        return !t.isAfter(now)
    }

    fun toJson(now: LocalDateTime = LocalDateTime.now()): JSONObject = JSONObject().apply {
        put("id", id)
        put("time", timeText)
        put("days", JSONArray(days.sorted().map { it.shortName() }))
        put("date", date?.toString() ?: JSONObject.NULL)
        put("sound", sound ?: JSONObject.NULL)
        put("volume", volume ?: JSONObject.NULL)
        put("label", label)
        put("enabled", enabled)
        put("oneTime", oneShot)
        put("next", nextTrigger(now)?.toString() ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject): Alarm {
            val (h, m) = o.getString("time").split(":").map { it.toInt() }
            val daysArr = o.optJSONArray("days") ?: JSONArray()
            val days = (0 until daysArr.length()).map { Parse.day(daysArr.getString(it)) }.toSet()
            return Alarm(
                id = o.getString("id"),
                hour = h,
                minute = m,
                days = days,
                date = o.str("date")?.let { LocalDate.parse(it) },
                sound = o.str("sound"),
                volume = if (o.isNull("volume")) null else o.getInt("volume"),
                label = o.optString("label", ""),
                enabled = o.optBoolean("enabled", true),
            )
        }
    }
}

fun DayOfWeek.shortName(): String = name.take(3).lowercase(Locale.ROOT)

fun JSONObject.str(key: String): String? = if (isNull(key)) null else getString(key)

class ApiError(val code: Int, message: String) : Exception(message)

/** Lenient parsing of the values Loxone (or a person in a browser) sends. */
object Parse {
    private val dayNames = mapOf(
        "mo" to DayOfWeek.MONDAY, "ma" to DayOfWeek.MONDAY,
        "tu" to DayOfWeek.TUESDAY, "di" to DayOfWeek.TUESDAY,
        "we" to DayOfWeek.WEDNESDAY, "wo" to DayOfWeek.WEDNESDAY,
        "th" to DayOfWeek.THURSDAY, "do" to DayOfWeek.THURSDAY,
        "fr" to DayOfWeek.FRIDAY, "vr" to DayOfWeek.FRIDAY,
        "sa" to DayOfWeek.SATURDAY, "za" to DayOfWeek.SATURDAY,
        "su" to DayOfWeek.SUNDAY, "zo" to DayOfWeek.SUNDAY,
    )
    private val weekdays = setOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
    )
    private val weekend = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

    fun day(token: String): DayOfWeek {
        val t = token.trim().lowercase(Locale.ROOT)
        t.toIntOrNull()?.let {
            if (it in 1..7) return DayOfWeek.of(it)
            throw ApiError(400, "day number must be 1 (Monday) .. 7 (Sunday): $token")
        }
        return dayNames[t.take(2)] ?: throw ApiError(400, "unknown day: $token")
    }

    /** "mon,tue", "1-5", "weekdays", "weekend", "daily", "once" ... */
    fun days(value: String): Set<DayOfWeek> {
        val result = mutableSetOf<DayOfWeek>()
        for (raw in value.split(',', ';', ' ', '+')) {
            val tok = raw.trim().lowercase(Locale.ROOT)
            when {
                tok.isEmpty() || tok == "once" || tok == "none" || tok == "0" -> {}
                tok == "daily" || tok == "all" || tok == "everyday" || tok == "dagelijks" ->
                    result += DayOfWeek.values()
                tok == "weekdays" || tok == "workdays" || tok == "werkdagen" -> result += weekdays
                tok == "weekend" -> result += weekend
                tok.contains('-') -> {
                    val (a, b) = tok.split('-', limit = 2).map { day(it) }
                    var d = a
                    while (true) {
                        result += d
                        if (d == b) break
                        d = d.plus(1)
                    }
                }
                else -> result += day(tok)
            }
        }
        return result
    }

    /**
     * Time from the request: time=07:30 | time=0730 | hour=7&minute=30 |
     * sec=27000 (seconds since midnight) | min=450 (minutes since midnight).
     */
    fun time(p: Map<String, String>): Pair<Int, Int>? {
        val result: Pair<Int, Int>? = p["time"]?.let { v ->
            val s = v.trim()
            Regex("""^(\d{1,2})[:.hH](\d{1,2})(?::\d{1,2})?$""").find(s)?.let {
                it.groupValues[1].toInt() to it.groupValues[2].toInt()
            } ?: Regex("""^\d{3,4}$""").find(s)?.let {
                val n = s.toInt(); n / 100 to n % 100
            } ?: throw ApiError(400, "time must look like 07:30: $v")
        } ?: p["hour"]?.let { h ->
            number(h, "hour") to (p["minute"]?.let { number(it, "minute") } ?: 0)
        } ?: p["sec"]?.let {
            val s = number(it, "sec") % 86400; s / 3600 to (s % 3600) / 60
        } ?: p["min"]?.let {
            val m = number(it, "min") % 1440; m / 60 to m % 60
        }
        if (result != null && (result.first !in 0..23 || result.second !in 0..59)) {
            throw ApiError(400, "invalid time ${result.first}:${result.second}")
        }
        return result
    }

    fun number(v: String, name: String): Int =
        v.trim().replace(',', '.').toDoubleOrNull()?.toInt() ?: throw ApiError(400, "$name must be a number: $v")

    fun bool(v: String?): Boolean? = when (v?.trim()?.lowercase(Locale.ROOT)) {
        null -> null
        "1", "true", "on", "yes", "ja", "aan" -> true
        "0", "false", "off", "no", "nee", "uit" -> false
        else -> throw ApiError(400, "expected 0/1 or true/false: $v")
    }

    fun date(v: String): LocalDate = try {
        LocalDate.parse(v.trim())
    } catch (e: Exception) {
        throw ApiError(400, "date must look like 2026-11-25: $v")
    }

    fun percent(v: String, name: String): Int {
        val n = number(v, name)
        if (n !in 0..100) throw ApiError(400, "$name must be 0..100")
        return n
    }
}
