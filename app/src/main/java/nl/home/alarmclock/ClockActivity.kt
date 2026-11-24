package nl.home.alarmclock

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.random.Random

class ClockActivity : Activity() {
    private lateinit var root: View
    private lateinit var block: View
    private lateinit var timeView: TextView
    private lateinit var dateView: TextView
    private lateinit var nextView: TextView
    private lateinit var ringOverlay: View
    private lateinit var ringTime: TextView
    private lateinit var ringLabel: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var runtimeAsked = false
    private var lastHeight = 0
    private val stateListener: () -> Unit = { applyState() }

    /** Runs right after every minute change: update the text and move the clock (burn-in protection). */
    private val tick = object : Runnable {
        override fun run() {
            updateClock()
            moveBlock(animate = true)
            handler.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000 + 50)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_clock)
        root = findViewById(R.id.root)
        block = findViewById(R.id.block)
        timeView = findViewById(R.id.time)
        dateView = findViewById(R.id.date)
        nextView = findViewById(R.id.next)
        ringOverlay = findViewById(R.id.ringOverlay)
        ringTime = findViewById(R.id.ringTime)
        ringLabel = findViewById(R.id.ringLabel)

        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        timeView.fontFeatureSettings = "tnum" // equal-width digits, so the block doesn't jump around
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> sizeText() }

        ringOverlay.setOnClickListener { Controller.stop() }
        findViewById<View>(R.id.stopButton).setOnClickListener { Controller.stop() }
        findViewById<View>(R.id.snoozeButton).setOnClickListener { Controller.snooze(9) }
        root.setOnLongClickListener {
            Toast.makeText(this, "API: ${Notifications.apiUrl()}", Toast.LENGTH_LONG).show()
            true
        }

        ClockService.start(this)
    }

    override fun onStart() {
        super.onStart()
        Controller.addListener(stateListener)
        applyState()
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 60_000 - System.currentTimeMillis() % 60_000 + 50)
        root.post { moveBlock(animate = false) }
    }

    override fun onStop() {
        Controller.removeListener(stateListener)
        handler.removeCallbacks(tick)
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ---- clock --------------------------------------------------------------------------------

    private fun sizeText() {
        if (root.height <= 0 || root.height == lastHeight) return
        lastHeight = root.height
        val h = root.height.toFloat()
        val w = root.width.toFloat()
        root.post { moveBlock(animate = false) }
        // about 40% of the screen height, but never wider than ~75% of the width
        val timeSize = minOf(h * 0.40f, w * 0.75f / 2.9f)
        timeView.setTextSize(TypedValue.COMPLEX_UNIT_PX, timeSize)
        dateView.setTextSize(TypedValue.COMPLEX_UNIT_PX, timeSize * 0.20f)
        nextView.setTextSize(TypedValue.COMPLEX_UNIT_PX, timeSize * 0.13f)
        ringTime.setTextSize(TypedValue.COMPLEX_UNIT_PX, timeSize * 0.8f)
    }

    private fun timeFormatter(): DateTimeFormatter =
        DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm")

    private fun updateClock() {
        val now = LocalDateTime.now()
        val text = now.format(timeFormatter())
        timeView.text = text
        ringTime.text = text
        val date = now.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()))
        dateView.text = date.replaceFirstChar { it.titlecase(Locale.getDefault()) }

        val next = Controller.nextAlarm(now)
        if (next == null) {
            nextView.visibility = View.GONE
        } else {
            val t = next.first
            // show the weekday only when the alarm is not today or tomorrow
            val day = if (t.toLocalDate().isAfter(LocalDate.now().plusDays(1))) {
                t.format(DateTimeFormatter.ofPattern("EEE ", Locale.getDefault()))
            } else ""
            nextView.text = "⏰ $day${t.format(timeFormatter())}"
            nextView.visibility = View.VISIBLE
        }
    }

    /** Puts the clock at a random spot on the screen. */
    private fun moveBlock(animate: Boolean) {
        if (ringOverlay.visibility == View.VISIBLE) return
        val place = {
            val maxX = (root.width - block.width).coerceAtLeast(0)
            val maxY = (root.height - block.height).coerceAtLeast(0)
            block.translationX = Random.nextInt(maxX + 1).toFloat()
            block.translationY = Random.nextInt(maxY + 1).toFloat()
        }
        if (!animate) {
            place()
            return
        }
        block.animate().alpha(0f).setDuration(500).withEndAction {
            place()
            block.animate().alpha(1f).setDuration(500).start()
        }.start()
    }

    /** Applies ringing state, dim mode and brightness. */
    private fun applyState() {
        updateClock()
        val ringing = Controller.ringing
        if (ringing != null) {
            ringOverlay.visibility = View.VISIBLE
            ringLabel.text = ringing.label.ifEmpty { "Alarm ${ringing.timeText}" }
        } else {
            ringOverlay.visibility = View.GONE
        }

        val color = if (Controller.dim && ringing == null) Color.rgb(90, 90, 90) else Color.WHITE
        timeView.setTextColor(color)
        dateView.setTextColor(color)

        val lp = window.attributes
        lp.screenBrightness = when {
            ringing != null -> 1f
            Controller.brightness < 0 -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            else -> (Controller.brightness / 100f).coerceIn(0.01f, 1f)
        }
        window.attributes = lp
    }

    // ---- first start --------------------------------------------------------------------------

    private fun requestSetup() {
        val wanted = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            wanted += Manifest.permission.POST_NOTIFICATIONS
            wanted += Manifest.permission.READ_MEDIA_AUDIO
        } else {
            wanted += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
        else requestSpecialAccess()
    }

    /** Settings screens that can't be asked with a dialog. Each one is asked only once. */
    private fun requestSpecialAccess() {
        Controller.player.musicDirs().forEach { it.mkdirs() }
        val prefs = getSharedPreferences("setup", MODE_PRIVATE)
        val pkg = Uri.parse("package:$packageName")

        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName) && !prefs.getBoolean("battery", false)) {
            prefs.edit().putBoolean("battery", true).apply()
            tryStart(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg))
            return
        }
        if (Build.VERSION.SDK_INT >= 29 && !Settings.canDrawOverlays(this) && !prefs.getBoolean("overlay", false)) {
            prefs.edit().putBoolean("overlay", true).apply()
            Toast.makeText(this, "Allow 'display over other apps' so the clock opens after a reboot", Toast.LENGTH_LONG).show()
            tryStart(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg))
            return
        }
        if (Build.VERSION.SDK_INT >= 34 && !prefs.getBoolean("fullscreen", false)) {
            val nm = getSystemService(NotificationManager::class.java)
            if (!nm.canUseFullScreenIntent()) {
                prefs.edit().putBoolean("fullscreen", true).apply()
                tryStart(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg))
            }
        }
    }

    private fun tryStart(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // settings screen not available on this device
        }
    }

    override fun onResume() {
        super.onResume()
        // Runs again after every permission dialog / settings screen, so the next item is asked then.
        if (!runtimeAsked) {
            runtimeAsked = true
            requestSetup()
        } else {
            requestSpecialAccess()
        }
    }
}
