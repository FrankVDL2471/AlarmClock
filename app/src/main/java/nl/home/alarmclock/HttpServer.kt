package nl.home.alarmclock

import android.media.AudioManager
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject

/**
 * REST API. Every endpoint works with GET and query parameters (easiest for Loxone Virtual Outputs);
 * POST with a JSON body or form fields works as well.
 */
class HttpServer(port: Int) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri.trimEnd('/').ifEmpty { "/" }
        return try {
            val p = params(session)
            Log.d(TAG, "${session.method} $path $p")
            if (path == "/") return html(HELP_PAGE)
            val body = Main.call { route(path, session.method, p) }
            json(Response.Status.OK, body)
        } catch (e: ApiError) {
            json(statusFor(e.code), JSONObject().put("ok", false).put("error", e.message))
        } catch (e: Exception) {
            Log.e(TAG, "Request failed: $path", e)
            json(Response.Status.INTERNAL_ERROR, JSONObject().put("ok", false).put("error", e.toString()))
        }
    }

    private fun route(path: String, method: Method, p: Map<String, String>): JSONObject = when (path) {
        "/api/status" -> Controller.status()

        "/api/alarms" ->
            if (method == Method.POST) ok().put("alarm", setAlarm(p).toJson())
            else ok().put("alarms", JSONArray(Controller.alarms().map { it.toJson() }))

        "/api/alarms/set" -> ok().put("alarm", setAlarm(p).toJson()).withWarning(p)

        "/api/alarm/once" -> ok().put("alarm", setOnce(p).toJson()).withWarning(p)

        "/api/alarm/once/cancel" -> ok().put("removed", Controller.delete(Controller.ONCE_ID))

        "/api/alarms/enable" -> {
            val id = p["id"] ?: "default"
            val a = Controller.alarm(id) ?: throw ApiError(404, "no alarm with id '$id'")
            val on = Parse.bool(p["on"] ?: p["enabled"]) ?: true
            ok().put("alarm", Controller.save(a.copy(enabled = on)).toJson())
        }

        "/api/alarms/delete" -> {
            val id = p["id"] ?: throw ApiError(400, "id required")
            if (!Controller.delete(id)) throw ApiError(404, "no alarm with id '$id'")
            ok().put("removed", true)
        }

        "/api/alarms/clear" -> {
            Controller.clear()
            ok()
        }

        "/api/play" -> {
            val sound = p["url"] ?: p["file"] ?: p["sound"] ?: throw ApiError(400, "file or url required")
            val volume = p["volume"]?.let { Parse.percent(it, "volume") }
            val (loop, times) = playCount(p)
            Controller.play(sound, volume, loop, times)
            ok().put("playing", sound).put("loop", loop).put("times", if (loop) -1 else times)
        }

        "/api/stop" -> ok().apply {
            val r = Controller.stop()
            put("stoppedAlarm", r.getBoolean("stoppedAlarm"))
            put("stoppedPlayback", r.getBoolean("stoppedPlayback"))
        }

        "/api/snooze" -> {
            val minutes = p["minutes"]?.let { Parse.number(it, "minutes") } ?: 9
            if (minutes !in 1..120) throw ApiError(400, "minutes must be 1..120")
            val a = Controller.snooze(minutes) ?: throw ApiError(409, "no alarm is ringing")
            ok().put("alarm", a.toJson())
        }

        "/api/files" -> ok()
            .put("folders", JSONArray(Controller.player.musicDirs().map { it.absolutePath }))
            .put("files", JSONArray(Controller.player.listFiles().map { it.name }))

        "/api/volume" -> {
            val level = p["level"] ?: p["volume"]
            if (level != null) {
                val stream = when (p["stream"]?.lowercase()) {
                    null, "alarm" -> AudioManager.STREAM_ALARM
                    "media", "music" -> AudioManager.STREAM_MUSIC
                    else -> throw ApiError(400, "stream must be alarm or media")
                }
                Controller.player.setVolume(stream, Parse.percent(level, "level"))
            }
            ok().put("alarm", Controller.player.volumePercent(AudioManager.STREAM_ALARM))
                .put("media", Controller.player.volumePercent(AudioManager.STREAM_MUSIC))
        }

        "/api/display" -> {
            val dim = Parse.bool(p["dim"])
            val brightness = p["brightness"]?.let {
                val n = Parse.number(it, "brightness")
                if (n < 0) -1 else n.coerceAtMost(100)
            }
            Controller.setDisplay(dim, brightness)
            ok().put("dim", Controller.dim).put("brightness", Controller.brightness)
        }

        else -> throw ApiError(404, "unknown endpoint: $path (see / for the list)")
    }

    // ---- alarm parsing ------------------------------------------------------------------------

    private fun setAlarm(p: Map<String, String>): Alarm {
        val id = p["id"]?.trim()?.ifEmpty { null } ?: "default"
        val existing = Controller.alarm(id)
        val (hour, minute) = Parse.time(p)
            ?: existing?.let { it.hour to it.minute }
            ?: throw ApiError(400, "time required (time=07:30, hour=7&minute=30, sec=… or min=…)")
        val days = p["days"]?.let { Parse.days(it) } ?: existing?.days ?: emptySet()
        val date = if (days.isEmpty()) {
            p["date"]?.let { Parse.date(it) } ?: Controller.nextDateFor(hour, minute)
        } else null
        return Controller.save(
            Alarm(
                id = id, hour = hour, minute = minute, days = days, date = date,
                sound = sound(p, existing?.sound),
                volume = p["volume"]?.let { Parse.percent(it, "volume") } ?: existing?.volume,
                label = p["label"] ?: existing?.label ?: "",
                enabled = Parse.bool(p["enabled"]) ?: true,
            )
        ).also { checkFuture(it) }
    }

    private fun setOnce(p: Map<String, String>): Alarm {
        val (hour, minute) = Parse.time(p)
            ?: throw ApiError(400, "time required (time=07:30, hour=7&minute=30, sec=… or min=…)")
        val date = p["date"]?.let { Parse.date(it) } ?: Controller.nextDateFor(hour, minute)
        return Controller.save(
            Alarm(
                id = Controller.ONCE_ID, hour = hour, minute = minute, date = date,
                sound = sound(p, null),
                volume = p["volume"]?.let { Parse.percent(it, "volume") },
                label = p["label"] ?: "",
            )
        ).also { checkFuture(it) }
    }

    /**
     * How often to play: loop=1/true/-1 → forever, loop=0/false or nothing → once,
     * loop=N (N ≥ 2) or repeat=N → N times. Returns (forever, times).
     */
    private fun playCount(p: Map<String, String>): Pair<Boolean, Int> {
        p["repeat"]?.let {
            val n = Parse.number(it, "repeat")
            if (n < 1) throw ApiError(400, "repeat must be 1 or more")
            return false to n
        }
        val v = p["loop"]?.trim() ?: return false to 1
        v.replace(',', '.').toDoubleOrNull()?.toInt()?.let { n ->
            return when {
                n < 0 || n == 1 -> true to 1
                n == 0 -> false to 1
                else -> false to n
            }
        }
        return (Parse.bool(v) ?: false) to 1
    }

    /** "default" or empty means the system alarm tone. */
    private fun sound(p: Map<String, String>, fallback: String?): String? {
        val s = p["sound"] ?: p["file"] ?: p["url"] ?: return fallback
        return s.trim().takeUnless { it.isEmpty() || it.equals("default", true) }
    }

    private fun checkFuture(a: Alarm) {
        if (a.oneShot && a.date != null && a.nextTrigger(java.time.LocalDateTime.now()) == null) {
            Controller.delete(a.id)
            throw ApiError(400, "${a.date} ${a.timeText} is in the past")
        }
    }

    private fun JSONObject.withWarning(p: Map<String, String>): JSONObject {
        val s = p["sound"] ?: p["file"] ?: return this
        if (s.contains("://") || s.equals("default", true) || s.isBlank()) return this
        if (Controller.player.findFile(s) == null) {
            put("warning", "file '$s' not found in ${Controller.player.musicDirs().first()}; the default alarm tone will be used")
        }
        return this
    }

    // ---- helpers ------------------------------------------------------------------------------

    private fun params(session: IHTTPSession): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        if (session.method == Method.POST || session.method == Method.PUT) {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val body = files["postData"]?.trim()
            if (body != null && body.startsWith("{")) {
                val o = JSONObject(body)
                for (key in o.keys()) {
                    val v = o.get(key)
                    result[key] = when (v) {
                        is JSONArray -> (0 until v.length()).joinToString(",") { v.getString(it) }
                        JSONObject.NULL -> ""
                        else -> v.toString()
                    }
                }
            }
        }
        session.parameters.forEach { (k, v) -> if (v.isNotEmpty()) result[k] = v.first() }
        return result
    }

    private fun ok() = JSONObject().put("ok", true)

    private fun statusFor(code: Int): Response.IStatus = when (code) {
        400 -> Response.Status.BAD_REQUEST
        404 -> Response.Status.NOT_FOUND
        409 -> Response.Status.CONFLICT
        else -> Response.Status.INTERNAL_ERROR
    }

    private fun json(status: Response.IStatus, body: JSONObject): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", body.toString(2)).apply {
            addHeader("Access-Control-Allow-Origin", "*")
            addHeader("Cache-Control", "no-store")
        }

    private fun html(body: String): Response =
        newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", body)

    companion object {
        private const val TAG = "HttpServer"

        private val HELP_PAGE = """
            <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
            <title>Alarm Clock API</title>
            <style>body{font-family:sans-serif;background:#111;color:#eee;padding:16px;max-width:900px}
            a{color:#8cf}code{background:#222;padding:2px 4px}li{margin:6px 0}</style></head><body>
            <h1>Alarm Clock API</h1>
            <ul>
            <li><a href="/api/status">/api/status</a></li>
            <li><a href="/api/alarms">/api/alarms</a></li>
            <li><code>/api/alarm/once?time=07:30[&amp;date=2026-11-25][&amp;sound=wake.mp3][&amp;volume=70][&amp;label=…]</code></li>
            <li><a href="/api/alarm/once/cancel">/api/alarm/once/cancel</a></li>
            <li><code>/api/alarms/set?id=work&amp;time=07:00&amp;days=mon-fri[&amp;sound=…][&amp;volume=…][&amp;label=…]</code></li>
            <li><code>/api/alarms/enable?id=work&amp;on=0|1</code></li>
            <li><code>/api/alarms/delete?id=work</code> &middot; <code>/api/alarms/clear</code></li>
            <li><code>/api/play?file=song.mp3</code> or <code>/api/play?url=http://…/song.mp3</code> <code>[&amp;volume=60][&amp;loop=1]</code> (loop=1: forever, loop=3: 3 times)</li>
            <li><a href="/api/stop">/api/stop</a> &middot; <code>/api/snooze?minutes=9</code></li>
            <li><a href="/api/files">/api/files</a></li>
            <li><code>/api/volume?level=50[&amp;stream=alarm|media]</code></li>
            <li><code>/api/display?dim=1&amp;brightness=5</code> (brightness -1 = system)</li>
            </ul>
            <p>Time can also be given as <code>hour=7&amp;minute=30</code>, <code>sec=27000</code> (seconds since midnight)
            or <code>min=450</code> (minutes since midnight).</p>
            </body></html>
        """.trimIndent()
    }
}
