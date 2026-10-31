package com.parabg.geotagcamera

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

class ReportActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val selected = linkedSetOf<String>()
    private lateinit var titleInput: EditText
    private lateinit var companyInput: EditText
    private lateinit var itemCount: TextView
    private lateinit var itemSeek: SeekBar
    private lateinit var orientationGroup: RadioGroup
    private lateinit var includeUnassigned: CheckBox
    private lateinit var photoGrid: LinearLayout
    private lateinit var countLabel: TextView
    private lateinit var records: MutableList<MediaRecord>
    private var passedIds: Set<String>? = null
    private val navy = Color.rgb(14, 22, 33)
    private val panel = Color.rgb(27, 43, 61)
    private val yellow = Color.rgb(255, 194, 71)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        records = AppStore.all(this).filter { it.type == "PHOTO" }.toMutableList()
        passedIds = intent.getStringArrayExtra("selectedMediaIds")?.toSet()
        if (passedIds != null) selected.addAll(records.filter { passedIds!!.contains(it.id) }.map { it.id })
        else selected.addAll(records.filter { it.project.isNotBlank() }.map { it.id })
        val rootScroll = ScrollView(this).apply { setBackgroundColor(navy); fillViewport = true }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(20))
        }
        rootScroll.addView(root)
        root.addView(TextView(this).apply {
            text = "Create PDF report"; textSize = 22f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "Choose photographs from multiple projects. Videos are excluded."
            textSize = 12f; setTextColor(0xFFB9C9D8.toInt()); setPadding(0, dp(4), 0, dp(10))
        })
        titleInput = EditText(this).apply {
            hint = "Report title"; setText("Site Progress Report"); setSingleLine(true)
            setTextColor(Color.WHITE); setHintTextColor(0xFF9BAAB9.toInt())
        }
        root.addView(titleInput, LinearLayout.LayoutParams(-1, dp(50)))
        companyInput = EditText(this).apply {
            hint = "Company name (optional)"
            setText(getSharedPreferences("camera_settings", MODE_PRIVATE).getString("company_name", ""))
            setSingleLine(true); setTextColor(Color.WHITE); setHintTextColor(0xFF9BAAB9.toInt())
        }
        root.addView(companyInput, LinearLayout.LayoutParams(-1, dp(50)))
        includeUnassigned = CheckBox(this).apply {
            text = "Include Unassigned photographs"; isChecked = passedIds != null &&
                records.any { it.project.isBlank() && selected.contains(it.id) }
            setTextColor(Color.WHITE)
            buttonTintList = android.content.res.ColorStateList.valueOf(yellow)
            setOnCheckedChangeListener { _, checked ->
                if (!checked) selected.removeAll(records.filter { it.project.isBlank() }.map { it.id }.toSet())
                else if (passedIds == null) selected.addAll(records.filter { it.project.isBlank() }.map { it.id })
                renderPhotoPicker()
            }
        }
        root.addView(includeUnassigned)
        val pickerHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        countLabel = TextView(this).apply { setTextColor(0xFFB9C9D8.toInt()); textSize = 12f }
        pickerHeader.addView(countLabel, LinearLayout.LayoutParams(0, dp(40), 1f))
        pickerHeader.addView(button("Select all") { selected.clear(); selected.addAll(eligibleRecords().map { it.id }); renderPhotoPicker() })
        pickerHeader.addView(button("Clear") { selected.clear(); renderPhotoPicker() })
        root.addView(pickerHeader)
        photoGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(photoGrid)

        root.addView(sectionLabel("Page layout"))
        orientationGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        orientationGroup.addView(RadioButton(this).apply { id = 1; text = "Portrait"; setTextColor(Color.WHITE) })
        orientationGroup.addView(RadioButton(this).apply { id = 2; text = "Landscape"; setTextColor(Color.WHITE) })
        orientationGroup.check(if (getSharedPreferences("camera_settings", MODE_PRIVATE).getString("pdf_orientation", "PORTRAIT") == "LANDSCAPE") 2 else 1)
        root.addView(orientationGroup)

        root.addView(sectionLabel("Photographs per page"))
        itemCount = TextView(this).apply { text = "2"; setTextColor(yellow); gravity = Gravity.CENTER; textSize = 15f }
        itemSeek = SeekBar(this).apply { max = 9; progress = (getSharedPreferences("camera_settings", MODE_PRIVATE).getInt("pdf_photos_per_page", 2) - 1).coerceIn(0, 9); progressTintList = android.content.res.ColorStateList.valueOf(yellow); thumbTintList = android.content.res.ColorStateList.valueOf(yellow) }
        itemSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { itemCount.text = (progress + 1).toString() }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        val seekRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        seekRow.addView(itemSeek, LinearLayout.LayoutParams(0, dp(48), 1f))
        seekRow.addView(itemCount, LinearLayout.LayoutParams(dp(35), dp(48)))
        root.addView(seekRow)
        root.addView(TextView(this).apply {
            text = "Each photograph receives its geotag watermark directly on the image. No separate metadata block is printed below it."
            textSize = 11f; setTextColor(0xFFB0C0CF.toInt()); setPadding(0, dp(5), 0, dp(12))
        })
        root.addView(button("Preview and create PDF") { generate() }, LinearLayout.LayoutParams(-1, dp(54)))
        setContentView(rootScroll); applySystemBarInsets(rootScroll)
        renderPhotoPicker()
    }

    private fun eligibleRecords(): List<MediaRecord> = records.filter {
        includeUnassigned.isChecked || it.project.isNotBlank()
    }.sortedByDescending { it.capturedAt }

    private fun renderPhotoPicker() {
        if (!::photoGrid.isInitialized) return
        photoGrid.removeAllViews()
        val eligible = eligibleRecords()
        countLabel.text = "${selected.size} selected • ${eligible.size} available"
        val grouped = eligible.groupBy { it.project.ifBlank { "Unassigned" } }
        grouped.forEach { (project, items) ->
            photoGrid.addView(sectionLabel(project + " • ${items.size}"))
            val columns = if (resources.configuration.smallestScreenWidthDp >= 600) 4 else 3
            items.chunked(columns).forEach { chunk ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                chunk.forEach { record ->
                    val chosen = selected.contains(record.id)
                    val tile = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(4))
                        setBackgroundColor(if (chosen) Color.rgb(45, 77, 104) else panel)
                    }
                    val image = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.DKGRAY) }
                    try { image.setImageURI(Uri.parse(record.uri)) } catch (_: Exception) { }
                    tile.addView(image, LinearLayout.LayoutParams(-1, dp(92)))
                    tile.addView(TextView(this).apply {
                        text = (if (chosen) "✓ " else "") + record.capturedAt.replace("T", " ").take(16)
                        setTextColor(if (chosen) yellow else Color.WHITE); textSize = 9f; maxLines = 1
                    })
                    tile.setOnClickListener {
                        if (!selected.add(record.id)) selected.remove(record.id)
                        renderPhotoPicker()
                    }
                    row.addView(tile, LinearLayout.LayoutParams(0, dp(122), 1f).apply { marginEnd = dp(5); bottomMargin = dp(5) })
                }
                repeat(columns - chunk.size) { row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
                photoGrid.addView(row)
            }
        }
        if (eligible.isEmpty()) photoGrid.addView(TextView(this).apply {
            text = "No photographs available. Capture photos or enable Unassigned photographs."
            setTextColor(Color.LTGRAY); setPadding(dp(10), dp(18), dp(10), dp(18))
        })
    }

    private fun generate() {
        val chosen = eligibleRecords().filter { selected.contains(it.id) }
        if (chosen.isEmpty()) { Toast.makeText(this, "Select at least one photograph", Toast.LENGTH_LONG).show(); return }
        val title = titleInput.text.toString().trim().ifBlank { "Site Progress Report" }
        val company = companyInput.text.toString().trim()
        getSharedPreferences("camera_settings", MODE_PRIVATE).edit().putString("company_name", company).apply()
        val perPage = itemSeek.progress + 1
        getSharedPreferences("camera_settings", MODE_PRIVATE).edit()
            .putInt("pdf_photos_per_page", perPage)
            .putString("pdf_orientation", if (landscape) "LANDSCAPE" else "PORTRAIT").apply()
        val landscape = orientationGroup.checkedRadioButtonId == 2
        val projectLabel = chosen.map { it.project }.filter { it.isNotBlank() }.distinct().joinToString(", ")
        Toast.makeText(this, "Generating PDF report…", Toast.LENGTH_SHORT).show()
        worker.execute {
            try {
                val file = PdfGenerator.create(this, chosen, title, perPage, landscape, true, true, projectLabel, company)
                runOnUiThread {
                    startActivity(Intent(this, PdfPreviewActivity::class.java)
                        .putExtra("pdfPath", file.absolutePath).putExtra("pdfTitle", title))
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "PDF generation failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun sectionLabel(value: String) = TextView(this).apply {
        text = value; textSize = 13f; setTextColor(yellow); typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(2), dp(10), 0, dp(4))
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 10f; minWidth = 0; setPadding(dp(7), 0, dp(7), 0)
        setTextColor(Color.WHITE)
        setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(39, 64, 88)))
        setOnClickListener { action() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
}
