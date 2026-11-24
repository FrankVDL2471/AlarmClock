package nl.home.alarmclock

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import fi.iki.elonen.NanoHTTPD

/** Foreground service that keeps the process (and so the REST API and alarms) alive. */
class ClockService : Service() {
    private var server: HttpServer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        goForeground()
        server = HttpServer(PORT).also {
            try {
                it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                Log.i(TAG, "API listening on ${Notifications.apiUrl()}")
            } catch (e: Exception) {
                Log.e(TAG, "Could not start HTTP server on port $PORT", e)
            }
        }
        wifiLock = getSystemService(WifiManager::class.java)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AlarmClock:wifi")
            ?.apply { setReferenceCounted(false); acquire() }
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AlarmClock:cpu")
            ?.apply { setReferenceCounted(false); acquire() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        return START_STICKY
    }

    private fun goForeground() {
        val n = Notifications.service(this)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(Notifications.SERVICE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(Notifications.SERVICE_ID, n)
        }
    }

    override fun onDestroy() {
        server?.stop()
        wifiLock?.release()
        wakeLock?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val PORT = 8080
        private const val TAG = "ClockService"

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, ClockService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "Could not start service", e)
            }
        }
    }
}
