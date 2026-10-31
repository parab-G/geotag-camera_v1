package com.parabg.geotagcamera

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class GalleryActivity : AppCompatActivity() {
    private lateinit var list: LinearLayout
    private lateinit var selectionBar: LinearLayout
    private lateinit var countLabel: TextView
    private lateinit var search: EditText
    private var section = "PHOTO"
    private var viewMode = "GRID"
    private var filter = "ALL"
    private var projectFilter = ""
    private var unassignedOnly = false
    private var newestFirst = true
    private val selected = linkedSetOf<String>()
    private val io = Executors.newSingleThreadExecutor()
    private val navy = Color.rgb(15, 25, 37)
    private val cardColor = Color.rgb(27, 43, 61)
    private val yellow = Color.rgb(255, 194, 71)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val galleryPrefs = getSharedPreferences("camera_settings", MODE_PRIVATE)
        viewMode = galleryPrefs.getString("gallery_default_view", "GRID") ?: "GRID"
        newestFirst = galleryPrefs.getBoolean("gallery_newest_first", true)
        projectFilter = intent.getStringExtra("projectFilter") ?: ""
        unassignedOnly = intent.getBooleanExtra("unassignedOnly", false)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundColor(navy)
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(this).apply {
            text = if (projectFilter.isBlank()) "Worksite Gallery" else projectFilter
            textSize = 21f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        }
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(actionButton("Trash") { showTrash() })
        root.addView(header)

        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(9), 0, dp(7)) }
        tabs.addView(actionButton("▧  Photos") { section = "PHOTO"; selected.clear(); render() }, LinearLayout.LayoutParams(0, dp(42), 1f))
        tabs.addView(actionButton("▶  Videos") { section = "VIDEO"; selected.clear(); render() }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(6) })
        root.addView(tabs)

        search = EditText(this).apply {
            hint = "Search name, caption, address or project"
            setSingleLine(true); textSize = 14f; setTextColor(Color.WHITE); setHintTextColor(0xFF9EADBD.toInt())
            setPadding(dp(12), 0, dp(12), 0)
            setBackgroundColor(cardColor)
        }
        root.addView(search, LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(7) })

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        listOf("GRID", "LIST", "MAP").forEach { mode ->
            controls.addView(actionButton(mode) { viewMode = mode; getSharedPreferences("camera_settings", MODE_PRIVATE).edit().putString("gallery_default_view", mode).apply(); render() }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(4) })
        }
        controls.addView(actionButton(if (newestFirst) "Newest ↓" else "Oldest ↑") { newestFirst = !newestFirst; getSharedPreferences("camera_settings", MODE_PRIVATE).edit().putBoolean("gallery_newest_first", newestFirst).apply(); render() })
        root.addView(controls)

        val filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        listOf("ALL", "PROJECTS", "UNASSIGNED").forEach { value ->
            filters.addView(actionButton(value) { filter = value; render() }, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(4) })
        }
        root.addView(filters)

        val scroll = ScrollView(this).apply { fillViewport = true }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        selectionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, dp(3)); visibility = View.GONE
        }
        countLabel = TextView(this).apply { setTextColor(Color.WHITE); textSize = 12f; maxLines = 1 }
        selectionBar.addView(countLabel, LinearLayout.LayoutParams(0, dp(44), 1f))
        selectionBar.addView(actionButton("Share") { shareSelected() })
        selectionBar.addView(actionButton("Save") { saveSelected() })
        selectionBar.addView(actionButton("PDF") { openPdfPicker() })
        selectionBar.addView(actionButton("Move") { moveSelected() })
        selectionBar.addView(actionButton("Delete") { deleteSelected() })
        root.addView(selectionBar)

        setContentView(root)
        applySystemBarInsets(root)
        search.addTextChangedListener(SimpleTextWatcher { render() })
        render()
    }

    override fun onResume() { super.onResume(); if (::list.isInitialized) render() }

    private fun visibleRecords(): List<MediaRecord> {
        val query = search.text?.toString()?.trim()?.lowercase() ?: ""
        val records = AppStore.all(this).filter { r ->
            val sectionOk = r.type == section
            val filterOk = when (filter) {
                "UNASSIGNED" -> r.project.isBlank()
                "PROJECTS" -> r.project.isNotBlank()
                else -> true
            }
            val projectOk = (!unassignedOnly || r.project.isBlank()) &&
                (projectFilter.isBlank() || r.project == projectFilter)
            val queryOk = query.isBlank() || listOf(r.name, r.caption, r.project, r.address, AppStore.fmtLocation(r))
                .any { it.lowercase().contains(query) }
            sectionOk && filterOk && projectOk && queryOk
        }
        return if (newestFirst) records.sortedByDescending { it.capturedAt } else records.sortedBy { it.capturedAt }
    }

    private fun render() {
        if (!::list.isInitialized) return
        list.removeAllViews()
        selectionBar.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
        countLabel.text = "${selected.size} selected"
        val records = visibleRecords()
        list.addView(TextView(this).apply {
            text = "${records.size} ${if (section == "PHOTO") "photographs" else "videos"}"
            setTextColor(0xFFB7C7D8.toInt()); textSize = 12f; setPadding(dp(3), dp(5), 0, dp(8))
        })
        if (viewMode == "MAP" && section == "PHOTO") {
            list.addView(actionButton("Open photo location map") {
                startActivity(Intent(this, MapViewActivity::class.java))
            }, LinearLayout.LayoutParams(-1, dp(54)))
            list.addView(TextView(this).apply {
                text = "Map pins are based on saved GPS coordinates. Items without coordinates remain in the gallery."
                setTextColor(0xFF9EADBD.toInt()); textSize = 12f; setPadding(dp(5), dp(10), dp(5), dp(10))
            })
            records.filter { it.latitude != null && it.longitude != null }.forEach { addListItem(it) }
            return
        }
        if (records.isEmpty()) {
            list.addView(TextView(this).apply {
                text = if (section == "PHOTO") "No photographs in this view yet." else "No videos in this view yet."
                setTextColor(Color.LTGRAY); setPadding(dp(12), dp(32), dp(12), dp(32))
            })
            return
        }
        if (viewMode == "GRID") {
            val columns = if (resources.configuration.smallestScreenWidthDp >= 600) 4 else
                if (resources.displayMetrics.widthPixels / resources.displayMetrics.density >= 430) 3 else 2
            records.chunked(columns).forEach { rowRecords ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                rowRecords.forEach { record -> row.addView(gridTile(record), LinearLayout.LayoutParams(0, dp(190), 1f).apply { marginEnd = dp(6); bottomMargin = dp(7) }) }
                repeat(columns - rowRecords.size) { row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
                list.addView(row)
            }
        } else {
            records.forEach { addListItem(it) }
        }
    }

    private fun gridTile(record: MediaRecord): View {
        val selectedNow = selected.contains(record.id)
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(5), dp(5), dp(5), dp(5))
            setBackgroundColor(if (selectedNow) Color.rgb(45, 77, 104) else cardColor)
        }
        val thumb = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.rgb(36, 49, 62)) }
        if (record.type == "PHOTO") {
            try { thumb.setImageURI(Uri.parse(record.uri)) } catch (_: Exception) { }
        } else {
            thumb.setImageResource(android.R.drawable.ic_media_play)
            thumb.scaleType = ImageView.ScaleType.CENTER
        }
        tile.addView(thumb, LinearLayout.LayoutParams(-1, 0, 1f))
        tile.addView(TextView(this).apply {
            text = record.capturedAt.replace("T", " ").take(16)
            setTextColor(Color.WHITE); textSize = 10f; maxLines = 1; setPadding(0, dp(4), 0, 0)
        })
        tile.addView(TextView(this).apply {
            text = record.project.ifBlank { "Unassigned" }
            setTextColor(if (selectedNow) yellow else 0xFF9EC9F3.toInt()); textSize = 10f; maxLines = 1
        })
        tile.setOnClickListener {
            if (selected.isNotEmpty()) toggleSelection(record) else openDetail(record)
        }
        tile.setOnLongClickListener { toggleSelection(record); true }
        tile.contentDescription = if (selectedNow) "Selected ${record.name}" else record.name
        return tile
    }

    private fun addListItem(record: MediaRecord) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), dp(7), dp(7), dp(7))
            setBackgroundColor(if (selected.contains(record.id)) Color.rgb(45, 77, 104) else cardColor)
        }
        val thumb = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.DKGRAY) }
        if (record.type == "PHOTO") try { thumb.setImageURI(Uri.parse(record.uri)) } catch (_: Exception) { }
        else thumb.setImageResource(android.R.drawable.ic_media_play)
        card.addView(thumb, LinearLayout.LayoutParams(dp(82), dp(82)))
        val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, dp(6), 0) }
        textCol.addView(TextView(this).apply { text = record.name; textSize = 12f; setTextColor(Color.WHITE); maxLines = 1 })
        textCol.addView(TextView(this).apply { text = record.capturedAt.replace("T", " "); textSize = 10f; setTextColor(0xFFB8C8D7.toInt()) })
        textCol.addView(TextView(this).apply {
            text = record.project.ifBlank { "Unassigned" } + " • " + if (record.latitude != null) "GPS" else "Location unavailable"
            textSize = 10f; setTextColor(0xFF9EC9F3.toInt()); maxLines = 2
        })
        card.addView(textCol, LinearLayout.LayoutParams(0, -2, 1f))
        card.setOnClickListener { if (selected.isNotEmpty()) toggleSelection(record) else openDetail(record) }
        card.setOnLongClickListener { toggleSelection(record); true }
        list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
    }

    private fun openDetail(record: MediaRecord) {
        startActivity(Intent(this, MediaDetailActivity::class.java).putExtra("mediaId", record.id))
    }

    private fun toggleSelection(record: MediaRecord) {
        if (!selected.add(record.id)) selected.remove(record.id)
        render()
    }

    private fun shareSelected() {
        val records = AppStore.all(this).filter { selected.contains(it.id) }
        if (records.isEmpty()) return
        Toast.makeText(this, "Preparing selected media…", Toast.LENGTH_SHORT).show()
        io.execute {
            try {
                val uris = ArrayList<Uri>()
                records.forEach { record ->
                    val uri = if (record.type == "PHOTO") AppStore.watermarkedPhoto(this, record)
                        else Uri.parse(record.uri)
                    if (uri != null) uris.add(uri)
                }
                runOnUiThread {
                    if (uris.isEmpty()) Toast.makeText(this, "No shareable media could be prepared", Toast.LENGTH_LONG).show()
                    else {
                        val photosOnly = records.all { it.type == "PHOTO" }
                        val intent = if (uris.size == 1) Intent(Intent.ACTION_SEND).apply {
                            type = if (photosOnly) "image/jpeg" else "video/mp4"
                            putExtra(Intent.EXTRA_STREAM, uris.first())
                        } else Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                            type = if (photosOnly) "image/*" else "*/*"
                            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                        }
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        startActivity(Intent.createChooser(intent, "Share selected geotagged media"))
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Share preparation failed: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun saveSelected() {
        val records = AppStore.all(this).filter { selected.contains(it.id) }
        if (records.isEmpty()) return
        io.execute {
            val saved = records.count { AppStore.copyOriginalToGallery(this, it) != null }
            runOnUiThread {
                Toast.makeText(this, "Saved $saved of ${records.size} original media files to phone Gallery", Toast.LENGTH_LONG).show()
                selected.clear(); render()
            }
        }
    }

    private fun openPdfPicker() {
        val ids = AppStore.all(this).filter { selected.contains(it.id) && it.type == "PHOTO" }.map { it.id }.toTypedArray()
        if (ids.isEmpty()) {
            Toast.makeText(this, "PDF reports support photographs only. Select photos in the Photos section.", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(Intent(this, ReportActivity::class.java).putExtra("selectedMediaIds", ids))
    }

    private fun moveSelected() {
        val records = AppStore.all(this).filter { selected.contains(it.id) }
        val choices = listOf("Unassigned") + AppStore.projects(this)
        if (records.isEmpty()) return
        android.app.AlertDialog.Builder(this).setTitle("Move ${records.size} items to")
            .setItems(choices.toTypedArray()) { _, which ->
                val target = if (which == 0) "" else choices[which]
                records.forEach { it.project = target; AppStore.save(this, it) }
                selected.clear(); render()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun deleteSelected() {
        val records = AppStore.all(this).filter { selected.contains(it.id) }
        if (records.isEmpty()) return
        android.app.AlertDialog.Builder(this).setTitle("Move to Recently Deleted?")
            .setMessage("${records.size} selected items will be removed from this app's gallery. You can restore them later. The original files remain on the phone until permanently deleted.")
            .setNegativeButton("Cancel", null).setPositiveButton("Move to Trash") { _, _ ->
                records.forEach { AppStore.moveToTrash(this, it) }
                selected.clear(); render()
            }.show()
    }

    private fun showTrash() {
        val trashed = AppStore.trashed(this)
        if (trashed.isEmpty()) {
            Toast.makeText(this, "Recently Deleted is empty", Toast.LENGTH_SHORT).show(); return
        }
        val names = trashed.map { "${it.name} • ${it.capturedAt.take(10)}" }.toTypedArray()
        android.app.AlertDialog.Builder(this).setTitle("Recently Deleted")
            .setItems(names) { _, index ->
                val record = trashed[index]
                android.app.AlertDialog.Builder(this).setTitle(record.name)
                    .setItems(arrayOf("Restore to Gallery", "Delete permanently")) { _, action ->
                        if (action == 0) AppStore.restoreFromTrash(this, record)
                        else android.app.AlertDialog.Builder(this).setTitle("Delete permanently?")
                            .setMessage("This will delete the original media file from the phone.")
                            .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ -> AppStore.permanentlyDeleteFromTrash(this, record) }.show()
                        render()
                    }.setNegativeButton("Close", null).show()
            }.setNegativeButton("Close", null).show()
    }

    private fun actionButton(label: String, click: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 9f; minWidth = 0; setPadding(dp(5), 0, dp(5), 0)
        setTextColor(Color.WHITE)
        setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(39, 64, 88)))
        setOnClickListener { click() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() { io.shutdown(); super.onDestroy() }
}

class SimpleTextWatcher(private val changed: () -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { changed() }
    override fun afterTextChanged(s: android.text.Editable?) {}
}
