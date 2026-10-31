package com.parabg.geotagcamera

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

class MediaDetailActivity : AppCompatActivity() {
    private lateinit var record: MediaRecord
    private lateinit var caption: EditText
    private lateinit var project: Spinner
    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("mediaId") ?: return finish()
        record = AppStore.find(this, id) ?: return finish()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(12)); setBackgroundColor(Color.rgb(14, 21, 29)) }
        root.addView(TextView(this).apply { text = record.name; textSize = 17f; setTextColor(Color.WHITE); maxLines = 1 })
        val preview = android.widget.FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        if (record.type == "VIDEO") {
            val video = VideoView(this); video.setVideoURI(Uri.parse(record.uri))
            video.setMediaController(MediaController(this).apply { setAnchorView(video) })
            preview.addView(video, android.widget.FrameLayout.LayoutParams(-1, -1))
            video.setOnPreparedListener { video.seekTo(1); video.pause() }
        } else {
            val image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setImageURI(Uri.parse(record.uri)) }
            preview.addView(image, android.widget.FrameLayout.LayoutParams(-1, -1))
        }
        val watermark = TextView(this).apply {
            text = "${record.capturedAt.replace("T", " ")}\n${AppStore.fmtLocation(record)}"
            textSize = 10f; setTextColor(Color.WHITE); setPadding(dp(7), dp(5), dp(7), dp(5)); setBackgroundColor(0x99000000.toInt())
        }
        val right = getSharedPreferences("camera_settings", MODE_PRIVATE).getBoolean("watermark_right", false)
        preview.addView(watermark, android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or if (right) Gravity.END else Gravity.START).apply { setMargins(dp(8), dp(8), dp(8), dp(8)) })
        root.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8); bottomMargin = dp(8) })
        val detailsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val mapThumb = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; visibility = if (record.latitude != null && record.longitude != null) android.view.View.VISIBLE else android.view.View.GONE }
        detailsRow.addView(mapThumb, LinearLayout.LayoutParams(dp(78), dp(78)))
        val metadata = TextView(this).apply {
            text = "${record.capturedAt.replace("T", " ")}\n${AppStore.fmtLocation(record)}\n${record.accuracy?.let { "Accuracy ±${it.toInt()} m" } ?: "Accuracy unavailable"}\nTimezone: ${record.timeZone}\nType: ${record.type}"
            textSize = 11f; setTextColor(0xFFD0DCE7.toInt()); setPadding(dp(8), 0, 0, 0)
        }
        detailsRow.addView(metadata, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(detailsRow, LinearLayout.LayoutParams(-1, dp(84)))
        caption = EditText(this).apply { hint = "Optional photo/video caption"; setText(record.caption); setTextColor(Color.WHITE); setHintTextColor(0xFF9BAABA.toInt()); setSingleLine(true) }
        root.addView(caption, LinearLayout.LayoutParams(-1, dp(48)))
        val projects = listOf("No project (Unassigned)") + AppStore.projects(this)
        project = Spinner(this).apply {
            adapter = ArrayAdapter(this@MediaDetailActivity, android.R.layout.simple_spinner_dropdown_item, projects)
            setSelection(projects.indexOf(record.project).let { if (it < 0) 0 else it })
        }
        root.addView(project, LinearLayout.LayoutParams(-1, dp(46)))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("Save") { saveMetadata() }, actionParams())
        actions.addView(button("Share") { share() }, actionParams())
        actions.addView(button("Map") { openMap() }, actionParams())
        if (record.type == "PHOTO") actions.addView(button("Watermark copy") { exportWatermark() }, actionParams())
        actions.addView(button("Delete") { delete() }, actionParams())
        root.addView(actions)
        setContentView(root); applySystemBarInsets(root)
        if (record.latitude != null && record.longitude != null) io.execute {
            val tile = AppStore.mapTile(this, record.latitude!!, record.longitude!!)
            runOnUiThread {
                if (tile != null) mapThumb.setImageBitmap(tile)
                else mapThumb.visibility = android.view.View.GONE
            }
        }
    }

    private fun saveMetadata() {
        record.caption = caption.text.toString()
        record.project = if (project.selectedItemPosition == 0) "" else project.selectedItem.toString()
        AppStore.save(this, record)
        Toast.makeText(this, "Media details saved", Toast.LENGTH_SHORT).show()
    }
    private fun share() {
        saveMetadata()
        if (record.type == "PHOTO") {
            Toast.makeText(this, "Creating a watermarked share copy…", Toast.LENGTH_SHORT).show()
            io.execute {
                val shareUri = AppStore.watermarkedPhoto(this, record)
                runOnUiThread {
                    if (shareUri == null) Toast.makeText(this, "Could not create watermarked copy", Toast.LENGTH_LONG).show()
                    else {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "image/jpeg"; putExtra(Intent.EXTRA_STREAM, shareUri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(send, "Share watermarked worksite photo"))
                    }
                }
            }
        } else {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"; putExtra(Intent.EXTRA_STREAM, Uri.parse(record.uri))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Share worksite video"))
        }
    }
    private fun openMap() {
        if (record.latitude == null || record.longitude == null) return Toast.makeText(this, "No GPS coordinates for this media", Toast.LENGTH_SHORT).show()
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${record.latitude},${record.longitude}?q=${record.latitude},${record.longitude}")))
    }
    private fun exportWatermark() {
        saveMetadata(); Toast.makeText(this, "Creating watermarked copy…", Toast.LENGTH_SHORT).show()
        io.execute {
            val uri = AppStore.watermarkedPhoto(this, record)
            runOnUiThread {
                if (uri != null) Toast.makeText(this, "Watermarked copy saved to Pictures / GeoTag Worksite Camera / Exports", Toast.LENGTH_LONG).show()
                else Toast.makeText(this, "Could not create the watermarked copy", Toast.LENGTH_LONG).show()
            }
        }
    }
    private fun delete() {
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Delete media?").setMessage("This deletes the saved media and its app metadata from this device.")
            .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ -> AppStore.delete(this, record); finish() }.show()
    }
    private fun button(label: String, click: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 9f; setTextColor(Color.WHITE)
        setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(42, 62, 82))); setOnClickListener { click() }
    }
    private fun actionParams() = LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(2) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    override fun onDestroy() { io.shutdown(); super.onDestroy() }
}
