package com.parabg.geotagcamera

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class ProjectsActivity : AppCompatActivity() {
    private lateinit var list: LinearLayout
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(12)); setBackgroundColor(Color.rgb(15, 23, 32)) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply { text = "Projects / Sites"; textSize = 22f; setTextColor(Color.WHITE) }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button("+ New project") { addProject() })
        root.addView(header)
        val scroll = ScrollView(this); list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(TextView(this).apply { text = "Projects are optional. Unassigned media is always kept."; textSize = 11f; setTextColor(0xFFB5C5D5.toInt()); gravity = Gravity.CENTER })
        setContentView(root); applySystemBarInsets(root); render()
    }
    private fun render() {
        list.removeAllViews()
        val all = AppStore.all(this); val names = AppStore.projects(this)
        list.addView(button("All photos & videos • ${all.size}") { startActivity(Intent(this, GalleryActivity::class.java)) }, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(8) })
        list.addView(button("Unassigned • ${all.count { it.project.isBlank() }}") {
            startActivity(Intent(this, GalleryActivity::class.java).putExtra("unassignedOnly", true))
        }, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(6) })
        names.forEach { name ->
            val count = all.count { it.project == name }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(button("$name • $count items") { startActivity(Intent(this, GalleryActivity::class.java).putExtra("projectFilter", name)) }, LinearLayout.LayoutParams(0, dp(52), 1f))
            row.addView(button("⋮") { projectMenu(name) }, LinearLayout.LayoutParams(dp(52), dp(52)))
            list.addView(row, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(7) })
        }
    }
    private fun addProject() {
        val input = EditText(this).apply { hint = "Project / site name" }
        AlertDialog.Builder(this).setTitle("Create project").setView(input).setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotBlank()) { AppStore.saveProjects(this, AppStore.projects(this).apply { add(value) }); render() }
            }.show()
    }
    private fun projectMenu(name: String) {
        AlertDialog.Builder(this).setTitle(name).setItems(arrayOf("Open media", "Delete project and unassign its media")) { _, which ->
            if (which == 0) startActivity(Intent(this, GalleryActivity::class.java).putExtra("projectFilter", name))
            else {
                AppStore.all(this).filter { it.project == name }.forEach { it.project = ""; AppStore.save(this, it) }
                AppStore.saveProjects(this, AppStore.projects(this).filterNot { it == name }); render()
            }
        }.show()
    }
    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 12f; setTextColor(Color.WHITE)
        setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(38, 62, 85))); setOnClickListener { action() }
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
