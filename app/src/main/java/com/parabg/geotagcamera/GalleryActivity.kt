package com.parabg.geotagcamera

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class GalleryActivity : AppCompatActivity() {
    private lateinit var list: LinearLayout
    private lateinit var search: EditText
    private var filter = "ALL"
    private var projectFilter = ""
    private var unassignedOnly = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectFilter = intent.getStringExtra("projectFilter") ?: ""
        unassignedOnly = intent.getBooleanExtra("unassignedOnly", false)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(12)); setBackgroundColor(Color.rgb(15, 23, 32)) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply { text = if (projectFilter.isBlank()) "Gallery" else projectFilter; textSize = 22f; setTextColor(Color.WHITE); setTypeface(null, 1) }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(actionButton("Map") { startActivity(Intent(this, MapViewActivity::class.java)) })
        root.addView(header)
        search = EditText(this).apply { hint = "Search caption, file, location or project"; setSingleLine(true); setTextColor(Color.WHITE); setHintTextColor(0xFF9CAAB8.toInt()) }
        root.addView(search, LinearLayout.LayoutParams(-1, dp(48)))
        val filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("ALL", "PHOTOS", "VIDEOS", "UNASSIGNED").forEach { label ->
            filters.addView(actionButton(label) { filter = label; render() }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(3) })
        }
        root.addView(filters)
        val scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(TextView(this).apply { text = "Stored on this device • Original media remains unchanged"; textSize = 10f; setTextColor(0xFF9CAAB8.toInt()); gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) })
        setContentView(root); applySystemBarInsets(root)
        search.addTextChangedListener(SimpleTextWatcher { render() })
        render()
    }

    override fun onResume() { super.onResume(); if (::list.isInitialized) render() }

    private fun render() {
        if (!::list.isInitialized) return
        list.removeAllViews()
        val q = search.text?.toString()?.trim()?.lowercase() ?: ""
        val records = AppStore.all(this).filter { r ->
            val typeOk = when (filter) { "PHOTOS" -> r.type == "PHOTO"; "VIDEOS" -> r.type == "VIDEO"; "UNASSIGNED" -> r.project.isBlank(); else -> true }
            val projectOk = (!unassignedOnly || r.project.isBlank()) && (projectFilter.isBlank() || r.project == projectFilter)
            typeOk && projectOk && (q.isBlank() || listOf(r.name, r.caption, r.project, AppStore.fmtLocation(r)).any { it.lowercase().contains(q) })
        }
        list.addView(TextView(this).apply { text = "${records.size} items"; setTextColor(0xFFCAD5E0.toInt()); textSize = 12f; setPadding(4, dp(8), 0, dp(5)) })
        if (records.isEmpty()) list.addView(TextView(this).apply { text = "No matching photos or videos yet."; setTextColor(Color.LTGRAY); setPadding(dp(12), dp(28), dp(12), dp(28)) })
        records.forEach { record ->
            val card = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)); setBackgroundColor(Color.rgb(29, 42, 56)) }
            val thumb = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.DKGRAY) }
            card.addView(thumb, LinearLayout.LayoutParams(dp(88), dp(88)))
            if (record.type == "PHOTO") try { thumb.setImageURI(android.net.Uri.parse(record.uri)) } catch (_: Exception) { }
            else thumb.setImageResource(android.R.drawable.ic_media_play)
            val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, dp(5), 0) }
            textCol.addView(TextView(this).apply { text = record.name; textSize = 13f; setTextColor(Color.WHITE); maxLines = 1 })
            textCol.addView(TextView(this).apply { text = "${record.type} • ${record.capturedAt.replace("T", " ")}"; textSize = 11f; setTextColor(0xFFB8C8D7.toInt()); maxLines = 2 })
            textCol.addView(TextView(this).apply { text = record.project.ifBlank { "Unassigned" }; textSize = 11f; setTextColor(0xFF79B7FF.toInt()) })
            card.addView(textCol, LinearLayout.LayoutParams(0, -2, 1f))
            card.setOnClickListener { startActivity(Intent(this, MediaDetailActivity::class.java).putExtra("mediaId", record.id)) }
            list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
        }
    }

    private fun actionButton(label: String, click: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 10f; setTextColor(Color.WHITE)
        setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(39, 64, 88)))
        setOnClickListener { click() }
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

class SimpleTextWatcher(private val changed: () -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { changed() }
    override fun afterTextChanged(s: android.text.Editable?) {}
}
