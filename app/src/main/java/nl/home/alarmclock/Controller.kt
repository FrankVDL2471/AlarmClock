package nl.home.alarmclock

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Process-wide state: alarms, ringing, playback and display settings.
 * Every method must be called on the main thread (the HTTP server uses [Main.call]).
 */
object Controller {
    private const val RING_TIMEOUT_MS = 15 * 60 * 1000L
    const val ONCE_ID = "once"
    const val SNOOZE_ID = "snooze"

    private lateinit var app: Context
    private lateinit var repo: AlarmRepository
    private lateinit var scheduler: AlarmScheduler
    lateinit var player: SoundPlayer
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<() -> Unit>()
    private val ringTimeout = Runnable { stop() }

    var ringing: Alarm? = null
        private set
    var dim = false
        private set
    /** Screen brightness 0..100, or -1 for the system setting. */
    var brightness = -1
        private set

    fun init(context: Context) {
        app = context.applicationContext
        repo = AlarmRepository(app)
        scheduler = AlarmScheduler(app)
        player = SoundPlayer(app)
        player.onStateChanged = { notifyChanged() }
        val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
        dim = prefs.getBoolean("dim", false)
        brightness = prefs.getInt("brightness", -1)
        reschedule()
    }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun notifyChanged() = listeners.toList().forEach { it() }

    // ---- alarms -------------------------------------------------------------------------------

    fun alarms(): List<Alarm> = repo.all()

    fun alarm(id: String): Alarm? = repo.get(id)

    /** Earliest upcoming trigger and every alarm that rings at that moment. */
    fun nextAlarm(now: LocalDateTime = LocalDateTime.now()): Pair<LocalDateTime, List<Alarm>>? {
        val triggers = repo.all().mapNotNull { a -> a.nextTrigger(now)?.let { it to a } }
        val first = triggers.minOfOrNull { it.first } ?: return null
        return first to triggers.filter { it.first == first }.map { it.second }
    }

    fun reschedule() {
        val now = LocalDateTime.now()
        repo.all().filter { it.isExpired(now) }.forEach { repo.remove(it.id) }
        val next = nextAlarm(now)
        scheduler.schedule(next?.first, next?.second?.map { it.id } ?: emptyList())
        notifyChanged()
    }

    fun save(alarm: Alarm): Alarm {
        repo.put(alarm)
        reschedule()
        return alarm
    }

    /** Next date on which [hour]:[minute] is still ahead: today, or tomorrow when it has passed. */
    fun nextDateFor(hour: Int, minute: Int): LocalDate {
        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        return if (today.atTime(hour, minute).isAfter(now)) today else today.plusDays(1)
    }

    fun delete(id: String): Boolean {
        val removed = repo.remove(id)
        reschedule()
        return removed
    }

    fun clear() {
        repo.clear()
        reschedule()
    }

    // ---- ringing ------------------------------------------------------------------------------

    /** Called by [AlarmReceiver] when the system alarm goes off. */
    fun fire(ids: List<String>) {
        val now = LocalDateTime.now()
        // The ids come from the PendingIntent; fall back to whatever was due in the last minute.
        val due = ids.mapNotNull { repo.get(it) }.ifEmpty {
            repo.all().filter { a ->
                a.nextTrigger(now.minusMinutes(1))?.let { !it.isAfter(now) } == true
            }
        }
        due.filter { it.oneShot }.forEach { repo.remove(it.id) }
        due.firstOrNull { it.enabled }?.let { startRinging(it) }
        reschedule()
    }

    fun startRinging(alarm: Alarm) {
        Log.i("Controller", "Alarm ${alarm.id} ringing")
        ringing = alarm
        ClockService.start(app)
        player.playAlarm(alarm.sound, alarm.volume)
        handler.removeCallbacks(ringTimeout)
        handler.postDelayed(ringTimeout, RING_TIMEOUT_MS)
        Notifications.showRinging(app, alarm)
        try {
            app.startActivity(
                Intent(app, ClockActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        } catch (e: Exception) {
            Log.w("Controller", "Could not bring clock to front", e)
        }
        notifyChanged()
    }

    /** Stops a ringing alarm and any playback. Returns what was stopped. */
    fun stop(): JSONObject {
        val wasRinging = ringing != null
        val wasPlaying = player.isPlaying
        ringing = null
        handler.removeCallbacks(ringTimeout)
        Notifications.cancelRinging(app)
        player.stop()
        notifyChanged()
        return JSONObject().put("stoppedAlarm", wasRinging).put("stoppedPlayback", wasPlaying)
    }

    fun snooze(minutes: Int): Alarm? {
        val r = ringing ?: return null
        stop()
        val t = LocalDateTime.now().plusMinutes(minutes.toLong()).truncatedTo(ChronoUnit.MINUTES)
        return save(
            Alarm(
                id = SNOOZE_ID, hour = t.hour, minute = t.minute, date = t.toLocalDate(),
                sound = r.sound, volume = r.volume, label = r.label.ifEmpty { "Snooze" },
            )
        )
    }

    // ---- playback -----------------------------------------------------------------------------

    fun play(sound: String, volume: Int?, loop: Boolean, times: Int = 1) {
        val uri = player.resolve(sound) ?: throw ApiError(404, "file not found: $sound")
        if (ringing != null) {
            ringing = null
            handler.removeCallbacks(ringTimeout)
            Notifications.cancelRinging(app)
        }
        player.play(uri, alarm = false, loop = loop, volume = volume, times = times)
    }

    // ---- display ------------------------------------------------------------------------------

    fun setDisplay(dim: Boolean?, brightness: Int?) {
        dim?.let { this.dim = it }
        brightness?.let { this.brightness = it }
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putBoolean("dim", this.dim).putInt("brightness", this.brightness).apply()
        notifyChanged()
    }

    // ---- status -------------------------------------------------------------------------------

    fun status(): JSONObject {
        val now = LocalDateTime.now()
        val next = nextAlarm(now)
        return JSONObject().apply {
            put("ok", true)
            put("time", now.truncatedTo(ChronoUnit.SECONDS).toString())
            put("api", Notifications.apiUrl())
            // 0/1 instead of booleans: easy to read with a Loxone Virtual HTTP Input
            put("ringing", if (ringing != null) 1 else 0)
            put("ringingAlarm", ringing?.toJson(now) ?: JSONObject.NULL)
            put("playing", if (player.isPlaying) 1 else 0)
            put("playingSource", player.source ?: JSONObject.NULL)
            if (next != null) {
                put("nextAlarm", next.first.toString())
                put("nextAlarmIds", JSONArray(next.second.map { it.id }))
                put("nextAlarmEpoch", next.first.atZone(ZoneId.systemDefault()).toEpochSecond())
                put("minutesToNextAlarm", ChronoUnit.MINUTES.between(now, next.first))
            } else {
                put("nextAlarm", JSONObject.NULL)
                put("minutesToNextAlarm", -1)
            }
            put("alarmCount", repo.all().size)
            put("volumeAlarm", player.volumePercent(AudioManager.STREAM_ALARM))
            put("volumeMedia", player.volumePercent(AudioManager.STREAM_MUSIC))
            put("dim", if (dim) 1 else 0)
            put("brightness", brightness)
        }
    }
}

/** Runs code on the main thread from another thread and waits for the result. */
object Main {
    private val handler = Handler(Looper.getMainLooper())

    fun <T> call(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = java.util.concurrent.CountDownLatch(1)
        var result: Result<T>? = null
        handler.post {
            result = runCatching(block)
            latch.countDown()
        }
        if (!latch.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw ApiError(500, "timeout")
        return result!!.getOrThrow()
    }
}
