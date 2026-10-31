package com.parabg.geotagcamera

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val continueToApp = Runnable {
        if (!isFinishing) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(19, 40, 62)
        window.navigationBarColor = Color.rgb(19, 40, 62)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(26), dp(24), dp(26), dp(24))
            setBackgroundColor(Color.rgb(19, 40, 62))
        }
        root.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher_foreground)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(132), dp(132)))
        root.addView(TextView(this).apply {
            text = "GeoTag Worksite Camera"
            textSize = 23f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(17), 0, dp(6))
        })
        root.addView(TextView(this).apply {
            text = "Professional field documentation"
            textSize = 13f; setTextColor(0xFFB9C9D8.toInt()); gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "Developed & Maintained by Govinda Parab"
            textSize = 12f; setTextColor(0xFFE7B84F.toInt()); gravity = Gravity.CENTER
            setPadding(0, dp(34), 0, dp(5))
        })
        root.addView(TextView(this).apply {
            text = "© 2026 Govinda Parab"
            textSize = 11f; setTextColor(0xFF9EADBD.toInt()); gravity = Gravity.CENTER
        })
        setContentView(root)
        applySystemBarInsets(root)
        handler.postDelayed(continueToApp, 1100L)
    }

    override fun onDestroy() {
        handler.removeCallbacks(continueToApp)
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}