package nl.home.alarmclock

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Foreground service that keeps the process (and so the REST API and alarms) alive.
 * A watchdog checks every minute that the HTTP server still accepts connections and restarts it if not.
 */
class ClockService : Service() {
    private var server: HttpServer? = null
    private val wifiLocks = mutableListOf<WifiManager.WifiLock>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val handler = Handler(Looper.getMainLooper())
    private val checker = Executors.newSingleThreadExecutor()
    private var watchdogRuns = 0
    private var lastIp: String? = null

    private val watchdog = object : Runnable {
        override fun run() {
            watchdogRuns++
            checkServer()
            checkIp()
            if (watchdogRuns % 15 == 0) EventLog.log("Alive: ip=${Notifications.ipAddress()} wifi=${wifiInfo()}")
            handler.postDelayed(this, WATCHDOG_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        EventLog.log("Service created")
        goForeground()
        startServer("service start")
        acquireLocks()
        registerNetworkCallback()
        lastIp = Notifications.ipAddress()
        handler.postDelayed(watchdog, WATCHDOG_MS)
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

    // ---- HTTP server --------------------------------------------------------------------------

    private fun startServer(reason: String) {
        try {
            server?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping server", e)
        }
        server = null
        try {
            val s = HttpServer(PORT)
            s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = s
            serverStartedAt = System.currentTimeMillis()
            if (reason != "service start") serverRestarts++
            EventLog.log("HTTP server started ($reason) on ${Notifications.apiUrl()}")
        } catch (e: Exception) {
            EventLog.log("HTTP server could not start ($reason): $e")
            // try again soon, e.g. when the port was still in use
            handler.postDelayed({ if (server == null) startServer("retry") }, 5_000)
        }
    }

    /** Is the server thread alive and does the port really accept a connection? */
    private fun checkServer() {
        val s = server
        if (s == null || !s.isAlive) {
            startServer("watchdog: server not running")
            return
        }
        checker.execute {
            val ok = try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", PORT), 3_000) }
                true
            } catch (e: Exception) {
                false
            }
            if (!ok) handler.post {
                if (server === s) startServer("watchdog: port not reachable")
            }
        }
    }

    // ---- network ------------------------------------------------------------------------------

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                handler.postDelayed({
                    EventLog.log("Network available: ip=${Notifications.ipAddress()} wifi=${wifiInfo()}")
                    // (the callback also fires right after registering; no need to restart then)
                    if (System.currentTimeMillis() - serverStartedAt > 10_000) startServer("network available")
                    checkIp()
                }, 2_000)
            }

            override fun onLost(network: Network) {
                handler.post { EventLog.log("Network lost") }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        } catch (e: Exception) {
            EventLog.log("Could not watch network: $e")
        }
    }

    /** Logs IP changes and updates the notification text. */
    private fun checkIp() {
        val ip = Notifications.ipAddress()
        if (ip != lastIp) {
            EventLog.log("IP address changed: $lastIp -> $ip")
            lastIp = ip
            getSystemService(NotificationManager::class.java)
                ?.notify(Notifications.SERVICE_ID, Notifications.service(this))
        }
    }

    @Suppress("DEPRECATION")
    private fun wifiInfo(): String = try {
        val info = getSystemService(WifiManager::class.java)?.connectionInfo
        if (info == null || info.networkId == -1) "disconnected"
        else "rssi=${info.rssi}dBm link=${info.linkSpeed}Mbps"
    } catch (e: Exception) {
        "?"
    }

    // ---- locks --------------------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        val wm = getSystemService(WifiManager::class.java)
        if (wm != null) {
            // HIGH_PERF works on Android 9; on 10+ LOW_LATENCY is the strongest lock (while the clock is visible).
            val modes = mutableListOf(WifiManager.WIFI_MODE_FULL_HIGH_PERF)
            if (Build.VERSION.SDK_INT >= 29) modes += WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            for (mode in modes) {
                try {
                    wifiLocks += wm.createWifiLock(mode, "AlarmClock:wifi$mode").apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                } catch (e: Exception) {
                    EventLog.log("Wi-Fi lock $mode failed: $e")
                }
            }
        }
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AlarmClock:cpu")
            ?.apply { setReferenceCounted(false); acquire() }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_CRITICAL) EventLog.log("Low memory (level $level)")
    }

    override fun onDestroy() {
        EventLog.log("Service destroyed")
        handler.removeCallbacksAndMessages(null)
        networkCallback?.let {
            try {
                getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                // ignore
            }
        }
        server?.stop()
        server = null
        wifiLocks.forEach { if (it.isHeld) it.release() }
        wifiLocks.clear()
        wakeLock?.release()
        checker.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val PORT = 8080
        private const val TAG = "ClockService"
        private const val WATCHDOG_MS = 60_000L

        @Volatile var serverStartedAt = 0L
            private set
        @Volatile var serverRestarts = 0
            private set

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, ClockService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "Could not start service", e)
            }
        }
    }
}
