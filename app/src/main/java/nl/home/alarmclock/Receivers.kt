package nl.home.alarmclock

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        startedAt = System.currentTimeMillis()
        EventLog.init(this)
        EventLog.log("App started (version ${packageManager.getPackageInfo(packageName, 0).versionName})")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            EventLog.log("CRASH in ${thread.name}: $e")
            previous?.uncaughtException(thread, e)
        }
        Notifications.createChannels(this)
        Controller.init(this)
    }

    companion object {
        @Volatile var startedAt = 0L
            private set
    }
}

/** Receives the system alarm and the Stop/Snooze buttons of the notification. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_FIRE -> Controller.fire(intent.getStringArrayExtra(EXTRA_IDS)?.toList() ?: emptyList())
            ACTION_STOP -> Controller.stop()
            ACTION_SNOOZE -> Controller.snooze(9)
        }
    }

    companion object {
        const val ACTION_FIRE = "nl.home.alarmclock.FIRE"
        const val ACTION_STOP = "nl.home.alarmclock.STOP"
        const val ACTION_SNOOZE = "nl.home.alarmclock.SNOOZE"
        const val EXTRA_IDS = "ids"
    }
}

/** After boot, an app update or a clock change: restart the service and register the next alarm again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        EventLog.log("Received ${intent.action}")
        ClockService.start(context)
        Controller.reschedule()
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            // Works on Android 9, and on 10+ when "Display over other apps" is allowed.
            try {
                context.startActivity(
                    Intent(context, ClockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                Log.w("BootReceiver", "Could not open clock", e)
            }
        }
    }
}
