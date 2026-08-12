package com.example.myapplication

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import java.util.*

class MainActivity : AppCompatActivity() {

    private var selectedImageUri: Uri? = null

    private val selectImageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                // Persist permission to access this URI across reboots
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                selectedImageUri = uri
                updateImageUI(true)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        setupSettingsUI()
    }

    private fun setupSettingsUI() {
        val prefs = getSharedPreferences("OverlayPrefs", Context.MODE_PRIVATE)
        
        val opacitySeekBar = findViewById<SeekBar>(R.id.opacitySeekBar)
        val fontSizeSeekBar = findViewById<SeekBar>(R.id.fontSizeSeekBar)
        val fontStyleGroup = findViewById<RadioGroup>(R.id.fontStyleGroup)
        val timezoneSpinner = findViewById<Spinner>(R.id.timezoneSpinner)
        val switch12h = findViewById<SwitchCompat>(R.id.switch12h)
        val colorSpinner = findViewById<Spinner>(R.id.colorSpinner)
        val btnSelectImage = findViewById<Button>(R.id.btnSelectImage)
        val btnClearImage = findViewById<Button>(R.id.btnClearImage)
        val imageOpacitySeekBar = findViewById<SeekBar>(R.id.imageOpacitySeekBar)
        val btnStart = findViewById<Button>(R.id.btnStartService)
        val drawerLayout = findViewById<DrawerLayout>(R.id.drawerLayout)
        val btnMenu = findViewById<ImageButton>(R.id.btnMenu)
        val maxSpeedValue = findViewById<TextView>(R.id.maxSpeedValue)
        val btnResetTrip = findViewById<Button>(R.id.btnResetTrip)

        btnMenu.setOnClickListener {
            val maxSpeed = prefs.getInt("all_time_max_speed", 0)
            maxSpeedValue.text = "$maxSpeed km/h"
            drawerLayout.openDrawer(GravityCompat.START)
        }

        btnResetTrip.setOnClickListener {
            prefs.edit().putInt("all_time_max_speed", 0).apply()
            maxSpeedValue.text = "0 km/h"
            Toast.makeText(this, "Max Speed Reset", Toast.LENGTH_SHORT).show()
        }

        // Setup Timezone Spinner
        val tzIds = TimeZone.getAvailableIDs()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, tzIds)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        timezoneSpinner.adapter = adapter

        // Setup Color Spinner
        val colors = arrayOf("Black", "Dark Gray", "Blue", "Red", "Green")
        val colorValues = arrayOf("#000000", "#333333", "#0000FF", "#FF0000", "#008000")
        val colorAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, colors)
        colorAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        colorSpinner.adapter = colorAdapter

        // Load current values
        opacitySeekBar.progress = (prefs.getFloat("opacity", 0.6f) * 100).toInt()
        fontSizeSeekBar.progress = prefs.getFloat("time_font_size", 24f).toInt()
        switch12h.isChecked = prefs.getBoolean("use_12h", true)
        imageOpacitySeekBar.progress = (prefs.getFloat("image_opacity", 1.0f) * 100).toInt()
        
        val currentTz = prefs.getString("timezone", TimeZone.getDefault().id)
        val tzIndex = tzIds.indexOf(currentTz)
        if (tzIndex >= 0) timezoneSpinner.setSelection(tzIndex)

        val currentColor = prefs.getString("bg_color_hex", "#000000")
        val colorIndex = colorValues.indexOf(currentColor)
        if (colorIndex >= 0) colorSpinner.setSelection(colorIndex)

        val savedUriString = prefs.getString("bg_image_uri", null)
        if (savedUriString != null) {
            selectedImageUri = Uri.parse(savedUriString)
            updateImageUI(true)
        }

        btnSelectImage.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            selectImageLauncher.launch(intent)
        }

        btnClearImage.setOnClickListener {
            selectedImageUri = null
            updateImageUI(false)
        }

        btnStart.setOnClickListener {
            val editor = prefs.edit()
            
            editor.putFloat("opacity", opacitySeekBar.progress / 100f)
            editor.putFloat("time_font_size", fontSizeSeekBar.progress.toFloat())
            editor.putBoolean("use_12h", switch12h.isChecked)
            editor.putString("bg_color_hex", colorValues[colorSpinner.selectedItemPosition])
            editor.putString("bg_image_uri", selectedImageUri?.toString())
            editor.putFloat("image_opacity", imageOpacitySeekBar.progress / 100f)
            
            val style = when (fontStyleGroup.checkedRadioButtonId) {
                R.id.radioMonospace -> "MONOSPACE"
                R.id.radioSerif -> "SERIF"
                else -> "DEFAULT"
            }
            editor.putString("font_style", style)
            editor.putString("timezone", timezoneSpinner.selectedItem.toString())
            
            editor.apply()

            val intent = Intent(this, OverlayService::class.java)
            intent.action = "UPDATE_SETTINGS"
            startForegroundService(intent)
            
            Toast.makeText(this, "Settings Applied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateImageUI(hasImage: Boolean) {
        findViewById<Button>(R.id.btnClearImage).visibility = if (hasImage) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.imageOpacityLabel).visibility = if (hasImage) View.VISIBLE else View.GONE
        findViewById<SeekBar>(R.id.imageOpacitySeekBar).visibility = if (hasImage) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.btnSelectImage).text = if (hasImage) "Change Image" else "Select Image from Gallery"
    }
}
