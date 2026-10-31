package com.parabg.geotagcamera

import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

data class PhotoItem(val uri: Uri, val name: String)

class GalleryActivity : AppCompatActivity() {
    private val photos = mutableListOf<PhotoItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF101010.toInt())
        }
        root.addView(TextView(this).apply {
            text = "ALL PHOTOS"
            textSize = 18f
            setTextColor(-1)
            setPadding(dp(16), dp(18), dp(16), dp(14))
        })
        val grid = GridView(this).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(116)
            verticalSpacing = dp(3)
            horizontalSpacing = dp(3)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        root.addView(grid, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)
        loadPhotos()
        grid.adapter = PhotoAdapter()
        grid.setOnItemClickListener { _, _, position, _ ->
            val item = photos[position]
            startActivity(Intent(this, PhotoDetailActivity::class.java).apply {
                putExtra("photoUri", item.uri.toString())
                putExtra("displayName", item.name)
            })
        }
        if (photos.isEmpty()) {
            root.addView(TextView(this).apply {
                text = "No photos yet. Return to Camera and take your first photo."
                setTextColor(0xFFBBBBBB.toInt())
                setPadding(dp(18), dp(16), dp(18), dp(24))
            })
        }
    }

    private fun loadPhotos() {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME
        )
        val selection: String?
        val selectionArgs: Array<String>?
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
            selectionArgs = arrayOf(Environment.DIRECTORY_PICTURES + "/GeoTag Worksite Camera/%")
        } else {
            selection = "${MediaStore.Images.Media.DATA} LIKE ?"
            selectionArgs = arrayOf("%/Pictures/GeoTag Worksite Camera/%")
        }
        val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        val cursor: Cursor? = contentResolver.query(collection, projection, selection, selectionArgs, sort)
        cursor?.use {
            val idIndex = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameIndex = it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            while (it.moveToNext()) {
                val id = it.getLong(idIndex)
                val name = it.getString(nameIndex) ?: "Photo"
                photos.add(PhotoItem(Uri.withAppendedPath(collection, id.toString()), name))
            }
        }
    }

    private inner class PhotoAdapter : BaseAdapter() {
        override fun getCount() = photos.size
        override fun getItem(position: Int) = photos[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val image = (convertView as? ImageView) ?: ImageView(this@GalleryActivity).apply {
                layoutParams = ViewGroup.LayoutParams(dp(116), dp(116))
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xFF292929.toInt())
            }
            image.setImageURI(photos[position].uri)
            return image
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
