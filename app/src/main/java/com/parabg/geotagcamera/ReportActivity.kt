package com.parabg.geotagcamera

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

class ReportActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val selected = linkedSetOf<String>()
    private lateinit var titleInput: EditText
    private lateinit var projectSpinner: Spinner
    private lateinit var itemsLabel: TextView
    private lateinit var itemSeek: SeekBar
    private lateinit var itemCount: TextView
    private lateinit var orientationGroup: RadioGroup
    private lateinit var includeDetails: CheckBox
    private lateinit var includeMaps: CheckBox
    private lateinit var records: MutableList<MediaRecord>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        records = AppStore.all(this)
        selected.addAll(records.map { it.id })
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)); setBackgroundColor(Color.rgb(14, 22, 31)) }
        root.addView(TextView(this).apply { text = "Create PDF report"; textSize = 22f; setTextColor(Color.WHITE); setTypeface(null, 1) })
        titleInput = EditText(this).apply { hint = "Report title (optional)"; setSingleLine(true); setTextColor(Color.WHITE); setHintTextColor(0xFF9BAAB9.toInt()) }
        root.addView(titleInput, LinearLayout.LayoutParams(-1, dp(50)))
        itemsLabel = TextView(this).apply { text = "${selected.size} of ${records.size} media selected"; setTextColor(0xFFB8C8D7.toInt()); textSize = 12f; setPadding(0, dp(8), 0, dp(5)) }
        root.addView(itemsLabel)
        root.addView(button("Choose media") { chooseMedia() }, LinearLayout.LayoutParams(-1, dp(46)))
        val names = listOf("All selected media") + AppStore.projects(this)
        projectSpinner = Spinner(this).apply { adapter = ArrayAdapter(this@ReportActivity, android.R.layout.simple_spinner_dropdown_item, names) }
        root.addView(TextView(this).apply { text = "Project filter / label"; setTextColor(Color.WHITE); setPadding(0, dp(12), 0, 0) })
        root.addView(projectSpinner, LinearLayout.LayoutParams(-1, dp(48)))
        root.addView(TextView(this).apply { text = "Page orientation"; setTextColor(Color.WHITE); setPadding(0, dp(12), 0, 0) })
        orientationGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        orientationGroup.addView(RadioButton(this).apply { id = 1; text = "Portrait"; setTextColor(Color.WHITE) })
        orientationGroup.addView(RadioButton(this).apply { id = 2; text = "Landscape"; setTextColor(Color.WHITE) })
        orientationGroup.check(1); root.addView(orientationGroup)
        root.addView(TextView(this).apply { text = "Items per page (1–10)"; setTextColor(Color.WHITE); setPadding(0, dp(10), 0, 0) })
        itemCount = TextView(this).apply { text = "4"; setTextColor(0xFF80B9FF.toInt()); gravity = Gravity.CENTER }
        itemSeek = SeekBar(this).apply { max = 9; progress = 3 }
        itemSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { itemCount.text = (progress + 1).toString() }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        val seekRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        seekRow.addView(itemSeek, LinearLayout.LayoutParams(0, dp(48), 1f)); seekRow.addView(itemCount, LinearLayout.LayoutParams(dp(38), dp(48)))
        root.addView(seekRow)
        includeDetails = CheckBox(this).apply { text = "Include date, location, accuracy and captions"; isChecked = true; setTextColor(Color.WHITE) }
        includeMaps = CheckBox(this).apply { text = "Include location map thumbnails"; isChecked = true; setTextColor(Color.WHITE) }
        root.addView(includeDetails); root.addView(includeMaps)
        root.addView(TextView(this).apply { text = "A4 PDF • Page numbers • Photo and video preview frames • Original media unchanged"; textSize = 11f; setTextColor(0xFFB0C0CF.toInt()); setPadding(0, dp(8), 0, dp(12)) })
        root.addView(button("Preview and create PDF") { generate() }, LinearLayout.LayoutParams(-1, dp(54)))
        setContentView(root); applySystemBarInsets(root)
    }

    private fun chooseMedia() {
        val checked = records.map { selected.contains(it.id) }.toBooleanArray()
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Select photos and videos")
            .setMultiChoiceItems(records.map { "${it.type} • ${it.name}\n${it.capturedAt.replace("T", " ")} • ${it.project.ifBlank { "Unassigned" }}" }.toTypedArray(), checked) { _, which, value ->
                if (value) selected.add(records[which].id) else selected.remove(records[which].id)
                itemsLabel.text = "${selected.size} of ${records.size} media selected"
            }.setPositiveButton("Done") { _, _ -> itemsLabel.text = "${selected.size} of ${records.size} media selected" }
            .setNeutralButton("Select all") { _, _ -> selected.clear(); selected.addAll(records.map { it.id }); itemsLabel.text = "${selected.size} of ${records.size} media selected" }
            .setNegativeButton("Cancel", null).show()
    }

    private fun generate() {
        val project = if (projectSpinner.selectedItemPosition == 0) "" else projectSpinner.selectedItem.toString()
        val chosen = records.filter { selected.contains(it.id) }.filter { project.isBlank() || it.project == project }
        if (chosen.isEmpty()) { Toast.makeText(this, "Select at least one photo or video", Toast.LENGTH_LONG).show(); return }
        val title = titleInput.text.toString().trim().ifBlank { "Site Progress Report" }
        val perPage = itemSeek.progress + 1
        val landscape = orientationGroup.checkedRadioButtonId == 2
        val details = includeDetails.isChecked
        val maps = includeMaps.isChecked
        Toast.makeText(this, "Generating PDF report…", Toast.LENGTH_SHORT).show()
        worker.execute {
            try {
                val file = PdfGenerator.create(this, chosen, title, perPage, landscape, details, maps, project)
                runOnUiThread { startActivity(android.content.Intent(this, PdfPreviewActivity::class.java).putExtra("pdfPath", file.absolutePath).putExtra("pdfTitle", title)) }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "PDF generation failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 14f; setTextColor(Color.WHITE)
        setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(24, 104, 220))); setOnClickListener { action() }
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
}
