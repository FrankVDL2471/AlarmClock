package nl.home.alarmclock

import android.content.Context
import android.util.Log
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Small persistent log of what happened (start, network changes, server restarts, alarms...).
 * Kept in a file so it survives the process being killed; read it with GET /api/log.
 */
object EventLog {
    private const val MAX_BYTES = 256 * 1024
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private var file: File? = null
    private var oldFile: File? = null

    fun init(context: Context) {
        file = File(context.filesDir, "events.log")
        oldFile = File(context.filesDir, "events.old.log")
    }

    @Synchronized
    fun log(message: String) {
        Log.i("EventLog", message)
        val f = file ?: return
        try {
            if (f.length() > MAX_BYTES) {
                oldFile?.delete()
                f.renameTo(oldFile!!)
            }
            f.appendText("${LocalDateTime.now().format(fmt)}  $message\n")
        } catch (e: Exception) {
            Log.w("EventLog", "Could not write log", e)
        }
    }

    /** The last [count] lines, oldest first. */
    @Synchronized
    fun lines(count: Int): List<String> {
        val all = listOfNotNull(oldFile, file)
            .filter { it.exists() }
            .flatMap { it.readLines() }
        return all.takeLast(count)
    }

    @Synchronized
    fun clear() {
        file?.delete()
        oldFile?.delete()
    }
}
