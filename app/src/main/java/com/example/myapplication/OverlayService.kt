package com.example.myapplication

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

class OverlayService : Service(), LocationListener {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private val handler = Handler(Looper.getMainLooper())
    
    private lateinit var timeFormat: SimpleDateFormat
    private lateinit var dateFormat: SimpleDateFormat
    
    private lateinit var timeText: TextView
    private lateinit var dateText: TextView
    private lateinit var speedText: TextView
    private lateinit var tempText: TextView
    private lateinit var weatherIcon: ImageView
    private lateinit var container: LinearLayout
    private lateinit var backgroundImageView: ImageView

    private var mediaPlayer: MediaPlayer? = null
    private var isWarningPlaying = false
    private val warningHandler = Handler(Looper.getMainLooper())

    private var isCollapsed = false
    private var lastIsNight: Boolean? = null

    private lateinit var locationManager: LocationManager
    private var currentSpeed: Int = 0

    private var lastWeatherUpdate = 0L

    private val updateTask = object : Runnable {
        override fun run() {
            val now = Date()
            timeText.text = timeFormat.format(now)
            dateText.text = dateFormat.format(now)
            
            checkSpeedWarning()
            checkDayNightMode()

            if (System.currentTimeMillis() - lastWeatherUpdate > 600000) { // Every 10 mins
                fetchWeather()
            }
            
            handler.postDelayed(this, 1000)
        }
    }

    private fun fetchWeather() {
        // Since we don't have an API key, I'll implement a mock weather system 
        // that changes based on the time of day to show off the cute icons.
        // In a real app, you'd use OpenWeatherMap or similar.
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val temp = 22 + (Math.random() * 5).toInt()
        
        tempText.text = "$temp°C"
        
        when {
            hour in 6..17 -> {
                weatherIcon.setImageResource(android.R.drawable.ic_menu_compass) // Represents Sun
                weatherIcon.setColorFilter(Color.YELLOW)
            }
            hour in 18..20 -> {
                weatherIcon.setImageResource(android.R.drawable.ic_menu_send) // Represents Sunset/Cloud
                weatherIcon.setColorFilter(Color.parseColor("#FFA500"))
            }
            else -> {
                weatherIcon.setImageResource(android.R.drawable.ic_menu_view) // Represents Moon
                weatherIcon.setColorFilter(Color.LTGRAY)
            }
        }
        lastWeatherUpdate = System.currentTimeMillis()
    }

    private fun checkSpeedWarning() {
        speedText.text = getString(R.string.speed_format, currentSpeed)

        val speedColor = when {
            currentSpeed > 120 -> Color.RED
            currentSpeed > 100 -> Color.YELLOW
            else -> Color.GREEN
        }
        speedText.setTextColor(speedColor)

        if (currentSpeed > 120) {
            startWarningSound()
        } else {
            stopWarningSound()
        }
    }

    override fun onLocationChanged(location: Location) {
        if (location.hasSpeed()) {
            currentSpeed = (location.speed * 3.6).toInt()
            
            // Track all-time max speed
            val prefs = getSharedPreferences("OverlayPrefs", Context.MODE_PRIVATE)
            val maxSpeed = prefs.getInt("all_time_max_speed", 0)
            if (currentSpeed > maxSpeed) {
                prefs.edit().putInt("all_time_max_speed", currentSpeed).apply()
            }
        }
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    private fun startWarningSound() {
        if (isWarningPlaying) return
        isWarningPlaying = true
        playWarningLoop()
    }

    private fun playWarningLoop() {
        if (!isWarningPlaying) return

        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer.create(this, R.raw.warning_sound)
            if (mediaPlayer == null) {
                isWarningPlaying = false
                return
            }
            
            mediaPlayer?.setOnCompletionListener {
                it.release()
                mediaPlayer = null
                if (isWarningPlaying) {
                    warningHandler.postDelayed({ playWarningLoop() }, 1500)
                }
            }
            mediaPlayer?.start()
        } catch (e: Exception) {
            isWarningPlaying = false
        }
    }

    private fun stopWarningSound() {
        if (!isWarningPlaying) return
        isWarningPlaying = false
        warningHandler.removeCallbacksAndMessages(null)
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (e: Exception) { }
        mediaPlayer = null
    }

    override fun onCreate() {
        super.onCreate()
        updateFormats()
        setupLocationUpdates()
        startForegroundService()
        showOverlay()
    }

    @SuppressLint("MissingPermission")
    private fun setupLocationUpdates() {
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
        }
    }

    private fun updateFormats() {
        val prefs = getSharedPreferences("OverlayPrefs", Context.MODE_PRIVATE)
        val tzId = prefs.getString("timezone", TimeZone.getDefault().id)
        val timeZone = TimeZone.getTimeZone(tzId)
        val use12h = prefs.getBoolean("use_12h", true)

        val timePattern = if (use12h) "hh:mm a" else "HH:mm:ss"
        timeFormat = SimpleDateFormat(timePattern, Locale.getDefault()).apply {
            this.timeZone = timeZone
        }
        dateFormat = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).apply {
            this.timeZone = timeZone
        }
    }

    private fun showOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        floatingView = inflater.inflate(R.layout.overlay_layout, null)

        container = floatingView.findViewById(R.id.overlay_container)
        timeText = floatingView.findViewById(R.id.timeText)
        dateText = floatingView.findViewById(R.id.dateText)
        speedText = floatingView.findViewById(R.id.speedText)
        tempText = floatingView.findViewById(R.id.tempText)
        weatherIcon = floatingView.findViewById(R.id.weatherIcon)
        container = floatingView.findViewById(R.id.overlay_container)
        backgroundImageView = floatingView.findViewById(R.id.backgroundImageView)

        applyUserSettings()

        handler.post(updateTask)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 100
        params.y = 100

        floatingView.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var touchX = 0f
            private var touchY = 0f
            private var startTime = 0L

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        startTime = System.currentTimeMillis()
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val duration = System.currentTimeMillis() - startTime
                        val dist = abs(event.rawX - touchX) + abs(event.rawY - touchY)
                        if (duration < 200 && dist < 10) {
                            toggleCollapse()
                        }
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - touchX).toInt()
                        params.y = initialY + (event.rawY - touchY).toInt()
                        windowManager.updateViewLayout(floatingView, params)
                        return true
                    }
                }
                return false
            }
        })

        windowManager.addView(floatingView, params)
    }

    private fun toggleCollapse() {
        isCollapsed = !isCollapsed
        applyUserSettings()
    }

    private fun checkDayNightMode() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val isNight = hour < 6 || hour > 18
        
        if (isNight != lastIsNight) {
            lastIsNight = isNight
            applyUserSettings()
        }
    }

    private fun applyUserSettings() {
        val prefs = getSharedPreferences("OverlayPrefs", Context.MODE_PRIVATE) ?: return
        
        val opacity = prefs.getFloat("opacity", 0.6f)
        val timeSize = prefs.getFloat("time_font_size", 24f)
        val fontStyle = prefs.getString("font_style", "DEFAULT")
        val bgColorHex = prefs.getString("bg_color_hex", "#000000")
        val imageUriString = prefs.getString("bg_image_uri", null)
        val imageOpacity = prefs.getFloat("image_opacity", 1.0f)

        // Handle Background Image/GIF
        if (imageUriString != null) {
            try {
                val uri = Uri.parse(imageUriString)
                backgroundImageView.visibility = if (isCollapsed) View.GONE else View.VISIBLE
                backgroundImageView.alpha = imageOpacity
                
                // Set a fixed placeholder color while loading
                container.setBackgroundColor(Color.TRANSPARENT)

                Glide.with(this.applicationContext)
                    .load(uri)
                    .centerCrop()
                    .into(backgroundImageView)
            } catch (e: Exception) {
                backgroundImageView.visibility = View.GONE
            }
        } else {
            backgroundImageView.visibility = View.GONE
            val baseColor = try { Color.parseColor(bgColorHex) } catch (e: Exception) { Color.BLACK }
            val alphaValue = (opacity * 255).toInt()
            val background = GradientDrawable().apply {
                cornerRadius = dpToPx(12).toFloat()
                setColor(baseColor)
                alpha = alphaValue
            }
            container.background = background
        }

        timeText.apply {
            textSize = if (isCollapsed) 14f else timeSize
            typeface = getFontTypeface(fontStyle)
        }

        dateText.apply {
            typeface = getFontTypeface(fontStyle)
            visibility = if (isCollapsed) View.GONE else View.VISIBLE
        }

        speedText.apply {
            typeface = getFontTypeface(fontStyle)
            visibility = if (isCollapsed) View.GONE else View.VISIBLE
        }

        val p = if (isCollapsed) dpToPx(8) else dpToPx(12)
        container.setPadding(p, p, p, p)
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun getFontTypeface(style: String?): Typeface {
        return when (style) {
            "MONOSPACE" -> Typeface.MONOSPACE
            "SERIF" -> Typeface.SERIF
            "SANS_SERIF" -> Typeface.SANS_SERIF
            else -> Typeface.DEFAULT
        }
    }

    private fun startForegroundService() {
        val channelId = "overlay_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Floating Clock", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Floating Clock Active")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "UPDATE_SETTINGS") {
            updateFormats()
            applyUserSettings()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updateTask)
        stopWarningSound()
        if (this::locationManager.isInitialized) {
            locationManager.removeUpdates(this)
        }
        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
