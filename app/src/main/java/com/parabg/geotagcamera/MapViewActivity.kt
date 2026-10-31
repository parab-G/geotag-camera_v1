package com.parabg.geotagcamera

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

class MapViewActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val all = AppStore.all(this).filter { it.latitude != null && it.longitude != null }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(12)); setBackgroundColor(Color.rgb(14, 22, 31)) }
        root.addView(TextView(this).apply { text = "Media locations"; textSize = 22f; setTextColor(Color.WHITE) })
        val map = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.rgb(42, 54, 67)) }
        root.addView(map, LinearLayout.LayoutParams(-1, dp(300)).apply { topMargin = dp(8); bottomMargin = dp(8) })
        val note = TextView(this).apply { text = "${all.size} geotagged items • Map tiles load when internet is available"; textSize = 11f; setTextColor(0xFFB9C7D4.toInt()) }
        root.addView(note)
        val scroll = ScrollView(this)
        val items = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        all.forEachIndexed { index, r ->
            items.addView(Button(this).apply {
                text = "${index + 1}. ${r.name}\n${AppStore.fmtLocation(r)}"
                isAllCaps = false; textSize = 11f; setTextColor(Color.WHITE)
                setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(39, 58, 78)))
                setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${r.latitude},${r.longitude}?q=${r.latitude},${r.longitude}"))) }
            }, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(4) })
        }
        scroll.addView(items)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(TextView(this).apply { text = "© OpenStreetMap contributors"; textSize = 10f; setTextColor(0xFFB9C7D4.toInt()); gravity = Gravity.CENTER })
        setContentView(root); applySystemBarInsets(root)
        if (all.isEmpty()) note.text = "No geotagged media yet. GPS-less media remains in the gallery."
        else worker.execute {
            val bitmap = AppStore.mapOverview(this, all)
            runOnUiThread { map.setImageBitmap(bitmap) }
        }
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
}
