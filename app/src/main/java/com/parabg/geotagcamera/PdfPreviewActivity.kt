package com.parabg.geotagcamera

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream

class PdfPreviewActivity : AppCompatActivity() {
    private lateinit var file: File
    private lateinit var preview: ImageView
    private lateinit var pageLabel: TextView
    private var descriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var pageIndex = 0
    private val savePicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) saveTo(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        file = File(intent.getStringExtra("pdfPath") ?: return finish())
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(12)); setBackgroundColor(Color.rgb(14, 22, 31)) }
        root.addView(TextView(this).apply { text = intent.getStringExtra("pdfTitle") ?: "PDF Preview"; textSize = 19f; setTextColor(Color.WHITE) })
        preview = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.WHITE) }
        root.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8); bottomMargin = dp(8) })
        pageLabel = TextView(this).apply { setTextColor(Color.WHITE); gravity = Gravity.CENTER; textSize = 12f }
        root.addView(pageLabel)
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        nav.addView(button("Previous") { showPage(pageIndex - 1) }, LinearLayout.LayoutParams(0, dp(46), 1f))
        nav.addView(button("Next") { showPage(pageIndex + 1) }, LinearLayout.LayoutParams(0, dp(46), 1f))
        root.addView(nav)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("Save PDF") { savePicker.launch(file.name) }, LinearLayout.LayoutParams(0, dp(50), 1f))
        actions.addView(button("Share PDF") { share() }, LinearLayout.LayoutParams(0, dp(50), 1f))
        root.addView(actions)
        setContentView(root); applySystemBarInsets(root); openRenderer()
    }

    private fun openRenderer() {
        try { descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY); renderer = PdfRenderer(descriptor!!); showPage(0) }
        catch (e: Exception) { Toast.makeText(this, "Unable to preview PDF: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    private fun showPage(index: Int) {
        val pdf = renderer ?: return
        if (index !in 0 until pdf.pageCount) return
        pageIndex = index
        val page = pdf.openPage(index)
        val w = (resources.displayMetrics.widthPixels - dp(32)).coerceAtLeast(400)
        val h = (w * page.height.toFloat() / page.width).toInt()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()
        preview.setImageBitmap(bitmap)
        pageLabel.text = "Page ${index + 1} of ${pdf.pageCount}"
    }

    private fun saveTo(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { out -> FileInputStream(file).use { it.copyTo(out) } }
                ?: error("Unable to open selected destination")
            Toast.makeText(this, "PDF saved", Toast.LENGTH_LONG).show()
        } catch (e: Exception) { Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    private fun share() {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply { type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            startActivity(Intent.createChooser(intent, "Share PDF report"))
        } catch (e: Exception) { Toast.makeText(this, "Share failed: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setTextColor(Color.WHITE)
        setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(24, 104, 220))); setOnClickListener { action() }
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    override fun onDestroy() { renderer?.close(); descriptor?.close(); super.onDestroy() }
}
