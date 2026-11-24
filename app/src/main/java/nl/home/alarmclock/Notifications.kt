package nl.home.alarmclock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import java.net.Inet4Address
import java.net.NetworkInterface

object Notifications {
    const val SERVICE_ID = 1
    private const val RING_ID = 2
    private const val CH_SERVICE = "service"
    private const val CH_ALARM = "alarm"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Clock service", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALARM, "Ringing alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
            }
        )
    }

    private fun openClock(context: Context) = PendingIntent.getActivity(
        context, 0,
        Intent(context, ClockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun service(context: Context): Notification =
        Notification.Builder(context, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_clock)
            .setContentTitle("Alarm clock is running")
            .setContentText("API: ${apiUrl()}")
            .setOngoing(true)
            .setContentIntent(openClock(context))
            .build()

    fun showRinging(context: Context, alarm: Alarm) {
        val stop = PendingIntent.getBroadcast(
            context, 2,
            Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val snooze = PendingIntent.getBroadcast(
            context, 3,
            Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_SNOOZE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val icon = Icon.createWithResource(context, R.drawable.ic_stat_clock)
        val n = Notification.Builder(context, CH_ALARM)
            .setSmallIcon(R.drawable.ic_stat_clock)
            .setContentTitle("Alarm ${alarm.timeText}")
            .setContentText(alarm.label.ifEmpty { "Wake up" })
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setFullScreenIntent(openClock(context), true)
            .setContentIntent(openClock(context))
            .addAction(Notification.Action.Builder(icon, "Snooze", snooze).build())
            .addAction(Notification.Action.Builder(icon, "Stop", stop).build())
            .build()
        try {
            context.getSystemService(NotificationManager::class.java).notify(RING_ID, n)
        } catch (e: SecurityException) {
            // notification permission not granted; the activity is shown anyway
        }
    }

    fun cancelRinging(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(RING_ID)
    }

    fun ipAddress(): String = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    } catch (e: Exception) {
        null
    } ?: "?"

    fun apiUrl(): String = "http://${ipAddress()}:${ClockService.PORT}"
}
