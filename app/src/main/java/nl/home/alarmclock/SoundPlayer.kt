package nl.home.alarmclock

import android.content.Context
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import kotlin.math.roundToInt

/** Plays MP3 files/URLs (ExoPlayer) or the system alarm tone. Main thread only. */
class SoundPlayer(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var exo: ExoPlayer? = null
    private var ringtone: Ringtone? = null

    var isPlaying = false
        private set
    var source: String? = null
        private set
    var onStateChanged: (() -> Unit)? = null

    /** Folders searched for local MP3 files. The first one is the one to copy files into. */
    fun musicDirs(): List<File> = listOfNotNull(
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "AlarmClock"),
        context.getExternalFilesDir(Environment.DIRECTORY_MUSIC),
    )

    fun listFiles(): List<File> = musicDirs()
        .flatMap { it.listFiles()?.toList() ?: emptyList() }
        .filter { it.isFile && it.extension.lowercase() in AUDIO_EXTENSIONS }
        .sortedBy { it.name.lowercase() }

    fun findFile(name: String): File? {
        val clean = name.trim().substringAfterLast('/')
        if (clean.isEmpty()) return null
        for (dir in musicDirs()) {
            val f = File(dir, clean)
            if (f.isFile) return f
            val mp3 = File(dir, "$clean.mp3")
            if (mp3.isFile) return mp3
        }
        // case-insensitive fallback
        return listFiles().firstOrNull {
            it.name.equals(clean, true) || it.nameWithoutExtension.equals(clean, true)
        }
    }

    /** A URL (http, https, content, file) or the name of a file in the music folder. */
    fun resolve(sound: String): Uri? {
        if (sound.contains("://")) return Uri.parse(sound.trim())
        return findFile(sound)?.let { Uri.fromFile(it) }
    }

    fun play(uri: Uri, alarm: Boolean, loop: Boolean, volume: Int?) {
        stop(notify = false)
        val stream = if (alarm) AudioManager.STREAM_ALARM else AudioManager.STREAM_MUSIC
        if (volume != null) setVolume(stream, volume)
        else if (alarm && audio.getStreamVolume(stream) == 0) setVolume(stream, 70)

        val player = ExoPlayer.Builder(context).build()
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(if (alarm) C.USAGE_ALARM else C.USAGE_MEDIA)
                .setContentType(if (alarm) C.AUDIO_CONTENT_TYPE_SONIFICATION else C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            false,
        )
        player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) handler.post { if (exo === player) stop() }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Playback failed for $uri", error)
                handler.post {
                    if (exo !== player) return@post
                    if (alarm) playDefaultAlarm(null) else stop()
                }
            }
        })
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.play()
        exo = player
        isPlaying = true
        source = uri.toString()
        onStateChanged?.invoke()
    }

    /** Ringing alarm: the given sound, or the system alarm tone when there is none or it can't be found. */
    fun playAlarm(sound: String?, volume: Int?) {
        val uri = sound?.let { resolve(it) }
        if (uri != null) play(uri, alarm = true, loop = true, volume = volume)
        else playDefaultAlarm(volume)
    }

    fun playDefaultAlarm(volume: Int?) {
        stop(notify = false)
        if (volume != null) setVolume(AudioManager.STREAM_ALARM, volume)
        else if (audio.getStreamVolume(AudioManager.STREAM_ALARM) == 0) setVolume(AudioManager.STREAM_ALARM, 70)
        val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val r = uri?.let { RingtoneManager.getRingtone(context, it) }
        if (r == null) {
            Log.e(TAG, "No alarm tone available")
            onStateChanged?.invoke()
            return
        }
        r.setAudioAttributes(
            android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        r.setLooping(true)
        r.play()
        ringtone = r
        isPlaying = true
        source = "default"
        onStateChanged?.invoke()
    }

    fun stop(notify: Boolean = true) {
        exo?.release()
        exo = null
        ringtone?.stop()
        ringtone = null
        val was = isPlaying
        isPlaying = false
        source = null
        if (notify && was) onStateChanged?.invoke()
    }

    fun volumePercent(stream: Int): Int {
        val max = audio.getStreamMaxVolume(stream)
        return if (max == 0) 0 else (audio.getStreamVolume(stream) * 100.0 / max).roundToInt()
    }

    fun setVolume(stream: Int, percent: Int) {
        try {
            val max = audio.getStreamMaxVolume(stream)
            audio.setStreamVolume(stream, (max * percent.coerceIn(0, 100) / 100.0).roundToInt(), 0)
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot change volume (Do Not Disturb?)", e)
        }
    }

    companion object {
        private const val TAG = "SoundPlayer"
        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "wav", "flac", "opus")
    }
}
