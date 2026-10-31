package com.parabg.geotagcamera

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.File

class PhotoDetailActivity : AppCompatActivity() {
    private lateinit var photoUri: Uri
    private lateinit var displayName: String
    private lateinit var captionInput: EditText
    private var latitude: Double? = null
    private var longitude: Double? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        photoUri = Uri.parse(intent.getStringExtra("photoUri") ?: return finish())
        displayName = intent.getStringExtra("displayName") ?: "Photo"
        val metadata = readMetadata()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(16))
            setBackgroundColor(Color.rgb(16, 16, 16))
        }
        root.addView(TextView(this).apply {
            text = displayName
            textSize = 15f
            setTextColor(Color.WHITE)
        })
        val photo = ImageView(this).apply {
            setImageURI(photoUri)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }
        root.addView(photo, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { topMargin = dp(8); bottomMargin = dp(8) })

        latitude = metadata?.optDouble("latitude")?.takeUnless { it.isNaN() }
        longitude = metadata?.optDouble("longitude")?.takeUnless { it.isNaN() }
        val locationLine = if (latitude != null && longitude != null) {
            "GPS: $latitude, $longitude\nAccuracy: ±${metadata?.optDouble("accuracyMeters") ?: 0.0} m"
        } else {
            "GPS: Not available for this photo"
        }
        val capturedAt = metadata?.optString("capturedAt", "Date/time not recorded") ?: "Date/time not recorded"
        root.addView(TextView(this).apply {
            text = "$capturedAt\n$locationLine"
            textSize = 12f
            setTextColor(0xFFCCCCCC.toInt())
            setPadding(0, dp(4), 0, dp(8))
        })

        captionInput = EditText(this).apply {
            hint = "Optional photo caption"
            setText(metadata?.optString("caption", "") ?: "")
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF999999.toInt())
            setSingleLine(true)
        }
        root.addView(captionInput)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        buttons.addView(button("Save caption") { saveCaption() }, params())
        buttons.addView(button("Share") { sharePhoto() }, params())
        buttons.addView(button("Map") { openMap() }, params())
        root.addView(buttons)
        setContentView(root)
    }

    private fun readMetadata(): JSONObject? = try {
        val file = File(File(filesDir, "metadata"), "$displayName.json")
        if (file.exists()) JSONObject(file.readText()) else null
    } catch (_: Exception) { null }

    private fun saveCaption() {
        try {
            val file = File(File(filesDir, "metadata"), "$displayName.json")
            val json = if (file.exists()) JSONObject(file.readText()) else JSONObject()
            json.put("displayName", displayName)
            json.put("uri", photoUri.toString())
            json.put("caption", captionInput.text.toString())
            file.parentFile?.mkdirs()
            file.writeText(json.toString(2))
            Toast.makeText(this, "Caption saved", Toast.LENGTH_SHORT).show()
        } catch (error: Exception) {
            Toast.makeText(this, "Could not save caption: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun sharePhoto() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, photoUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share worksite photo"))
    }

    private fun openMap() {
        if (latitude == null || longitude == null) {
            Toast.makeText(this, "No GPS coordinates are available", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude")
        startActivity(Intent(Intent.ACTION_VIEW, uri))
    }

    private fun button(label: String, click: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 12f
        setTextColor(Color.WHITE)
        setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF383838.toInt()))
        setOnClickListener { click() }
    }

    private fun params() = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
        marginEnd = dp(4)
        topMargin = dp(8)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
