package com.parabg.geotagcamera

import android.graphics.Color
import android.os.Bundle
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("camera_settings", MODE_PRIVATE) }
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(14, 22, 31)) }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(14), dp(18), dp(20)) }
        scroll.addView(content)
        content.addView(TextView(this).apply { text = "Settings"; textSize = 24f; setTextColor(Color.WHITE); setTypeface(null, 1) })
        content.addView(TextView(this).apply { text = "Watermark options"; textSize = 16f; setTextColor(0xFF80B9FF.toInt()); setPadding(0, dp(18), 0, dp(6)) })
        check("Show date and time", "watermark_date", true)
        check("Show GPS coordinates", "watermark_gps", true)
        check("Show GPS accuracy", "watermark_accuracy", true)
        check("Show project / site name", "watermark_project", true)
        check("Show photo caption", "watermark_caption", true)
        check("Show map thumbnail (when available)", "watermark_map", true)
        content.addView(TextView(this).apply { text = "Watermark position"; textSize = 16f; setTextColor(0xFF80B9FF.toInt()); setPadding(0, dp(18), 0, dp(4)) })
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        group.addView(RadioButton(this).apply { text = "Bottom left"; id = 1; setTextColor(Color.WHITE) })
        group.addView(RadioButton(this).apply { text = "Bottom right"; id = 2; setTextColor(Color.WHITE) })
        group.check(if (prefs.getBoolean("watermark_right", false)) 2 else 1)
        group.setOnCheckedChangeListener { _, id -> prefs.edit().putBoolean("watermark_right", id == 2).apply() }
        content.addView(group)
        content.addView(TextView(this).apply { text = "Camera and video"; textSize = 16f; setTextColor(0xFF80B9FF.toInt()); setPadding(0, dp(18), 0, dp(4)) })
        content.addView(TextView(this).apply {
            text = "Zoom uses the range exposed by your phone. Some multi-camera devices may switch optical lenses automatically; other devices use digital zoom. Video GPS is written to MP4 location metadata when available, with timestamp and accuracy stored in the app's local record. Video watermark burn-in is not included in this test build."
            textSize = 12f; setTextColor(0xFFB9C7D4.toInt()); setPadding(0, dp(6), 0, dp(8))
        })
        content.addView(TextView(this).apply { text = "About"; textSize = 16f; setTextColor(0xFF80B9FF.toInt()); setPadding(0, dp(18), 0, dp(6)) })
        content.addView(TextView(this).apply {
            text = "GeoTag Worksite Camera\nVersion 1.0.0 (V1 Test Build)\nProfessional Field Documentation\n\nDeveloped & Maintained by\nGovinda Parab\nApp Developer • GitHub: parab-G\n\n© 2026 Govinda Parab\n\nNo advertisements. No mandatory account. Media remains on this device unless you choose to share or export it."
            textSize = 14f; setTextColor(Color.WHITE); setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundColor(Color.rgb(29, 42, 56))
        })
        content.addView(TextView(this).apply {
            text = "Map thumbnails use OpenStreetMap data when online. Map attribution is retained. Offline, the app shows a coordinate/map placeholder and retains GPS values."
            textSize = 11f; setTextColor(0xFF9CABBA.toInt()); setPadding(0, dp(14), 0, 0)
        })
        setContentView(scroll); applySystemBarInsets(scroll)
    }

    private fun check(label: String, key: String, default: Boolean) {
        content.addView(CheckBox(this).apply {
            text = label; isChecked = prefs.getBoolean(key, default); setTextColor(Color.WHITE)
            buttonTintList = android.content.res.ColorStateList.valueOf(0xFF3B8BFF.toInt())
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key, checked).apply() }
        })
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
