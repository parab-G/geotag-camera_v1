package com.parabg.geotagcamera

import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import java.io.File

class SettingsActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("camera_settings", MODE_PRIVATE) }
    private lateinit var content: LinearLayout
    private val navy = Color.rgb(14, 22, 31)
    private val blue = 0xFF80B9FF.toInt()
    private val yellow = Color.rgb(255, 194, 71)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { setBackgroundColor(navy) }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(17), dp(14), dp(17), dp(24)) }
        scroll.addView(content)
        content.addView(TextView(this).apply { text = "Settings"; textSize = 24f; setTextColor(Color.WHITE); setTypeface(null, 1) })

        section("Camera & Capture")
        content.addView(info("Camera preview adapts to portrait and landscape. Photo capture is full quality. Video capture defaults to HD. GPS never blocks the shutter."))
        choice("Default capture mode", "default_capture_mode", listOf("Photo", "Video"), listOf("PHOTO", "VIDEO"))
        choice("Video quality", "video_quality", listOf("HD 720p", "Full HD 1080p"), listOf("HD", "FHD"))

        section("Location & Metadata")
        content.addView(info("Location acquisition starts when the app is opened and permission is available. It refreshes while the app is active. Only fresh fixes with known accuracy are attached to media. If location is unavailable, capture remains available and the media is marked accordingly."))
        check("Store GPS in photo EXIF when available", "exif_gps", true)
        check("Store capture timezone in media metadata", "store_timezone", true)
        check("Resolve street address when available online", "resolve_address", true)
        check("Store compass heading when sensor is available", "store_heading", true)

        section("Watermark Customisation")
        check("Show date and time", "watermark_date", true)
        check("Show GPS coordinates", "watermark_gps", true)
        check("Show GPS accuracy", "watermark_accuracy", true)
        check("Show address", "watermark_address", true)
        check("Show compass heading", "watermark_compass", true)
        check("Show project / site name", "watermark_project", true)
        check("Show caption", "watermark_caption", true)
        check("Show actual map thumbnail when available", "watermark_map", true)
        choice("Watermark position", "watermark_right", listOf("Bottom left", "Bottom right"), listOf("false", "true"), booleanValues = true)
        slider("Watermark transparency", "watermark_alpha", 80, 35, 95, "More transparent", "More opaque")
        slider("Watermark text size", "watermark_text_scale", 100, 75, 140, "Smaller", "Larger")

        section("Gallery & Projects")
        choice("Default gallery view", "gallery_default_view", listOf("Grid", "List", "Map"), listOf("GRID", "LIST", "MAP"))
        check("Show newest items first", "gallery_newest_first", true)
        content.addView(info("Projects are optional. Removing media from a project moves it to Unassigned. Deleting a project preserves its media. Recently Deleted allows restoration."))

        section("PDF Report Preferences")
        choice("Default page orientation", "pdf_orientation", listOf("Portrait", "Landscape"), listOf("PORTRAIT", "LANDSCAPE"))
        choice("Photographs per page", "pdf_photos_per_page", (1..10).map { it.toString() }, (1..10).map { it.toString() })
        check("Include map thumbnail in watermark when available", "pdf_include_map", true)
        val company = EditText(this).apply {
            hint = "Optional company name for PDF reports"
            setText(prefs.getString("company_name", ""))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setTextColor(Color.WHITE); setHintTextColor(0xFF9BAAB9.toInt())
        }
        content.addView(company, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(5) })
        content.addView(button("Save company name") {
            prefs.edit().putString("company_name", company.text.toString().trim()).apply()
            toast("Company name saved")
        })

        section("Storage & Privacy")
        val storageInfo = TextView(this).apply { setTextColor(0xFFB9C7D4.toInt()); textSize = 12f; setPadding(dp(4), dp(4), dp(4), dp(8)) }
        content.addView(storageInfo)
        fun updateStorage() {
            val used = directoryBytes(filesDir)
            storageInfo.text = "App metadata and settings: ${android.text.format.Formatter.formatShortFileSize(this, used)}\nOriginal photos and videos are stored in the phone's media library. The app does not automatically upload your media."
        }
        updateStorage()
        content.addView(button("Clear cached map tiles") {
            val cache = File(cacheDir, "maptiles")
            val removed = directoryBytes(cache)
            cache.deleteRecursively()
            toast("Cleared ${android.text.format.Formatter.formatShortFileSize(this, removed)} of cached map tiles")
            updateStorage()
        })

        section("Appearance & Accessibility")
        choice("App appearance", "appearance_mode", listOf("Dark", "Light", "System default"), listOf("DARK", "LIGHT", "SYSTEM"))
        choice("Text size", "accessibility_text_size", listOf("Small", "Standard", "Large"), listOf("SMALL", "NORMAL", "LARGE"))
        content.addView(info("The interface uses high-contrast text and touch-friendly controls. Adaptive layouts respond to the available app window size."))

        section("About")
        content.addView(TextView(this).apply {
            text = "GeoTag Worksite Camera\nVersion 1.1.0 (V1 Development Build)\n\nDeveloped & Maintained by\nGovinda Parab\n\n© 2026 Govinda Parab\n\nNo advertisements. No mandatory account. Media remains on this device unless you choose to share or export it."
            textSize = 14f; setTextColor(Color.WHITE); setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundColor(Color.rgb(29, 42, 56))
        })
        content.addView(info("Map data attribution: © OpenStreetMap contributors. Map thumbnails are only shown when actual tile data is available."))

        section("Reset")
        content.addView(button("Reset settings to default") {
            android.app.AlertDialog.Builder(this).setTitle("Reset settings?")
                .setMessage("Watermark, gallery, report and appearance preferences will return to their defaults. Your photographs, videos and projects will not be deleted.")
                .setNegativeButton("Cancel", null).setPositiveButton("Reset") { _, _ ->
                    prefs.edit().clear().apply()
                    recreate()
                }.show()
        })
        setContentView(scroll)
        applySystemBarInsets(scroll)
        applyAppearance()
    }

    private fun applyAppearance() {
        when (prefs.getString("appearance_mode", "DARK")) {
            "LIGHT" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            "SYSTEM" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        }
    }

    private fun section(title: String) {
        content.addView(TextView(this).apply {
            text = title; textSize = 16f; setTextColor(yellow); setTypeface(null, 1)
            setPadding(0, dp(20), 0, dp(5))
        })
    }

    private fun info(value: String) = TextView(this).apply {
        text = value; textSize = 12f; setTextColor(0xFFB9C7D4.toInt())
        setPadding(dp(4), dp(4), dp(4), dp(7))
    }

    private fun check(label: String, key: String, default: Boolean) {
        content.addView(CheckBox(this).apply {
            text = label; isChecked = prefs.getBoolean(key, default); setTextColor(Color.WHITE)
            buttonTintList = android.content.res.ColorStateList.valueOf(yellow)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key, checked).apply() }
        })
    }

    private fun choice(
        label: String, key: String, labels: List<String>, values: List<String>, booleanValues: Boolean = false
    ) {
        content.addView(TextView(this).apply { text = label; setTextColor(Color.WHITE); textSize = 12f; setPadding(dp(4), dp(7), 0, dp(2)) })
        val current = if (booleanValues) prefs.getBoolean(key, false).toString() else prefs.getString(key, values.first()) ?: values.first()
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val selectedIndex = values.indexOf(current).let { if (it < 0) 0 else it }
        spinner.setSelection(selectedIndex)
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (booleanValues) prefs.edit().putBoolean(key, values[position].toBoolean()).apply()
                else prefs.edit().putString(key, values[position]).apply()
                if (key == "appearance_mode") applyAppearance()
            }
        }
        content.addView(spinner, LinearLayout.LayoutParams(-1, dp(44)))
    }

    private fun slider(label: String, key: String, default: Int, min: Int, max: Int, low: String, high: String) {
        content.addView(TextView(this).apply { text = label; setTextColor(Color.WHITE); textSize = 12f; setPadding(dp(4), dp(8), 0, 0) })
        val value = TextView(this).apply { setTextColor(blue); gravity = Gravity.END; text = "${prefs.getInt(key, default)}%" }
        val seek = SeekBar(this).apply {
            this.max = max - min
            progress = (prefs.getInt(key, default).coerceIn(min, max) - min)
            progressTintList = android.content.res.ColorStateList.valueOf(yellow)
            thumbTintList = android.content.res.ColorStateList.valueOf(yellow)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val v = min + progress; value.text = "${v}%"
                    prefs.edit().putInt(key, v).apply()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        content.addView(value)
        content.addView(seek)
        content.addView(TextView(this).apply { text = "$low                                  $high"; textSize = 9f; setTextColor(0xFF9BAAB9.toInt()) })
    }

    private fun button(label: String, click: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 11f; setTextColor(Color.WHITE)
        setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(39, 64, 88)))
        setOnClickListener { click() }
    }

    private fun directoryBytes(dir: File): Long =
        if (!dir.exists()) 0L else if (dir.isFile) dir.length() else dir.listFiles()?.sumOf { directoryBytes(it) } ?: 0L

    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
