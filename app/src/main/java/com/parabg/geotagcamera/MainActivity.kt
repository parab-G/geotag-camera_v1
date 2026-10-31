package com.parabg.geotagcamera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Geocoder
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var statusView: TextView
    private lateinit var shutter: Button
    private lateinit var photoModeButton: Button
    private lateinit var videoModeButton: Button
    private lateinit var zoomSeek: SeekBar
    private lateinit var zoomLabel: TextView
    private lateinit var mapThumb: ImageView
    private lateinit var watermarkText: TextView
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    @Volatile private var latestLocation: android.location.Location? = null
    @Volatile private var bestLocation: android.location.Location? = null
    @Volatile private var lastHeadingDegrees: Float? = null
    private val sensorManager by lazy { getSystemService(SENSOR_SERVICE) as SensorManager }
    private val headingListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
            val rotation = FloatArray(9)
            val orientation = FloatArray(3)
            SensorManager.getRotationMatrixFromVector(rotation, event.values)
            SensorManager.getOrientation(rotation, orientation)
            lastHeadingDegrees = ((Math.toDegrees(orientation[0].toDouble()).toFloat() + 360f) % 360f)
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val incoming = result.lastLocation ?: return
            latestLocation = incoming
            val previous = bestLocation
            if (incoming.accuracy <= 50f &&
                (previous == null || previous.accuracy > incoming.accuracy ||
                    System.currentTimeMillis() - previous.time > 90_000L)) {
                bestLocation = incoming
            }
            val usable = usableLocation()
            runOnUiThread {
                if (::statusView.isInitialized && recording == null && !busy) {
                    statusView.text = usable?.let { "GPS ready • ±${it.accuracy.toInt()} m • refreshing location" }
                        ?: "Searching for a reliable GPS location…"
                }
                if (::watermarkText.isInitialized) {
                    watermarkText.text = usable?.let {
                        "${it.latitude}, ${it.longitude}\nAccuracy ±${it.accuracy.toInt()} m"
                    } ?: "Location unavailable • capturing remains available"
                }
            }
        }
    }
    private val worker = Executors.newSingleThreadExecutor()
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var selector = CameraSelector.DEFAULT_BACK_CAMERA
    private var flash = false
    private var videoMode = false
    private var recording: Recording? = null
    private var videoStartedAt = Date()
    private var videoLocation: android.location.Location? = null
    private var pendingVideoLocation: android.location.Location? = null
    private var busy = false

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.CAMERA] == true) {
            startCamera()
            if (hasLocation()) startLocationUpdates()
            else statusView.text = "Camera ready • Location unavailable; you can still capture."
        } else statusView.text = "Camera permission is required. You can still browse saved media."
    }
    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        beginVideoRecording(pendingVideoLocation, granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(16, 25, 35)
        window.navigationBarColor = Color.rgb(16, 25, 35)
        buildScreen()
        requestPermissionsIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        if (hasLocation()) startLocationUpdates()
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensorManager.registerListener(headingListener, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        stopLocationUpdates()
        sensorManager.unregisterListener(headingListener)
        super.onPause()
    }

    private fun buildScreen() {
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val root = LinearLayout(this).apply {
            orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val previewColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8))
            setBackgroundColor(Color.rgb(16, 25, 35))
        }
        header.addView(TextView(this).apply {
            text = "GeoTag Worksite Camera"; textSize = 18f; setTextColor(Color.WHITE); setTypeface(null, android.graphics.Typeface.BOLD)
        })
        statusView = TextView(this).apply {
            text = "Starting camera…"; textSize = 12f; setTextColor(Color.rgb(180, 205, 226)); setPadding(0, dp(3), 0, 0)
        }
        header.addView(statusView)
        previewColumn.addView(header)
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        frame.addView(previewView, FrameLayout.LayoutParams(-1, -1))
        val right = getSharedPreferences("camera_settings", MODE_PRIVATE).getBoolean("watermark_right", false)
        val badge = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), dp(6), dp(7), dp(6)); setBackgroundColor(0x88000000.toInt())
        }
        mapThumb = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.GONE }
        badge.addView(mapThumb, LinearLayout.LayoutParams(dp(58), dp(58)))
        watermarkText = TextView(this).apply {
            text = "GPS details appear after a location fix is available"
            textSize = 10f; setTextColor(Color.WHITE); setPadding(dp(7), 0, dp(4), 0); maxLines = 4
        }
        badge.addView(watermarkText, LinearLayout.LayoutParams(0, -2, 1f))
        frame.addView(badge, FrameLayout.LayoutParams(if (landscape) dp(285) else dp(305), -2,
            Gravity.BOTTOM or if (right) Gravity.END else Gravity.START).apply {
            setMargins(dp(10), dp(10), dp(10), dp(10))
        })
        previewColumn.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        val controls = buildControls()
        if (landscape) {
            root.addView(previewColumn, LinearLayout.LayoutParams(0, -1, 1f))
            root.addView(controls, LinearLayout.LayoutParams(dp(230), -1))
        } else {
            root.addView(previewColumn, LinearLayout.LayoutParams(-1, 0, 1f))
            root.addView(controls, LinearLayout.LayoutParams(-1, -2))
        }
        setContentView(root)
        setVideoMode(getSharedPreferences("camera_settings", MODE_PRIVATE).getString("default_capture_mode", "PHOTO") == "VIDEO")
        applySystemBarInsets(root)
    }

    private fun buildControls(): LinearLayout {
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(8)); setBackgroundColor(Color.rgb(16, 25, 35))
        }
        val nav = row()
        nav.addView(button("Gallery") { startActivity(android.content.Intent(this, GalleryActivity::class.java)) }, weight())
        nav.addView(button("Projects") { startActivity(android.content.Intent(this, ProjectsActivity::class.java)) }, weight())
        nav.addView(button("Reports") { startActivity(android.content.Intent(this, ReportActivity::class.java)) }, weight())
        nav.addView(button("Settings") { startActivity(android.content.Intent(this, SettingsActivity::class.java)) }, weight())
        controls.addView(nav)
        val modes = row()
        photoModeButton = button("● PHOTO") { setVideoMode(false) }
        videoModeButton = button("VIDEO") { setVideoMode(true) }
        modes.addView(photoModeButton, weight()); modes.addView(videoModeButton, weight())
        controls.addView(modes)
        val zoom = row()
        zoomLabel = TextView(this).apply { text = "1.0×"; setTextColor(Color.WHITE); gravity = Gravity.CENTER; textSize = 13f }
        zoom.addView(zoomLabel, LinearLayout.LayoutParams(dp(48), dp(38)))
        zoomSeek = SeekBar(this).apply {
            max = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val state = camera?.cameraInfo?.zoomState?.value ?: return
                    val ratio = state.minZoomRatio + (state.maxZoomRatio - state.minZoomRatio) * progress / 100f
                    camera?.cameraControl?.setZoomRatio(ratio)
                    zoomLabel.text = String.format(Locale.US, "%.1f×", ratio)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        zoom.addView(zoomSeek, LinearLayout.LayoutParams(0, dp(38), 1f)); controls.addView(zoom)
        val tools = row()
        tools.addView(button(if (flash) "Flash on" else "Flash off") {
            flash = !flash
            imageCapture?.flashMode = if (flash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
            camera?.cameraControl?.enableTorch(flash)
            (it as Button).text = if (flash) "Flash on" else "Flash off"
        }, weight())
        tools.addView(button("Switch camera") {
            selector = if (selector == CameraSelector.DEFAULT_BACK_CAMERA) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            startCamera()
        }, weight())
        controls.addView(tools)
        shutter = button("●  TAKE PHOTO") { if (videoMode) toggleVideo() else capturePhoto() }.apply {
            textSize = 17f; setTextColor(Color.BLACK)
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(255, 194, 71)))
        }
        controls.addView(shutter, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(4) })
        controls.addView(TextView(this).apply {
            text = "Local storage • No account • No advertisements"; textSize = 10f
            setTextColor(Color.LTGRAY); gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0)
        })
        return controls
    }

    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun weight() = LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(4); bottomMargin = dp(3) }
    private fun button(label: String, action: (View) -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 11f; setTextColor(Color.WHITE)
        setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(42, 58, 75))); setOnClickListener(action)
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun requestPermissionsIfNeeded() {
        val needed = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT < 29) needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val missing = needed.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startCamera() else permissionRequest.launch(missing.toTypedArray())
    }
    private fun hasLocation() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun isFresh(location: android.location.Location?): Boolean =
        location != null && System.currentTimeMillis() - location.time <= 120_000L

    private fun usableLocation(): android.location.Location? =
        bestLocation?.takeIf { isFresh(it) } ?: latestLocation?.takeIf { isFresh(it) && it.accuracy <= 100f }

    private fun startLocationUpdates() {
        if (!hasLocation()) return
        try {
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2500L)
                .setMinUpdateIntervalMillis(1200L)
                .setMinUpdateDistanceMeters(2f)
                .setWaitForAccurateLocation(false)
                .build()
            fused.lastLocation.addOnSuccessListener { last ->
                if (last != null && (latestLocation == null || last.accuracy < (latestLocation?.accuracy ?: Float.MAX_VALUE))) {
                    latestLocation = last
                    if (last.accuracy <= 50f) bestLocation = last
                }
            }
            fused.requestLocationUpdates(request, locationCallback, mainLooper)
        } catch (_: SecurityException) {
            if (::statusView.isInitialized) statusView.text = "Location unavailable • permission not granted"
        } catch (_: Exception) {
            if (::statusView.isInitialized) statusView.text = "Searching for location…"
        }
    }

    private fun stopLocationUpdates() {
        try { fused.removeLocationUpdates(locationCallback) } catch (_: Exception) { }
    }

    /** Capture uses the freshest already-acquired fix and never waits for GPS before taking the photo. */
    private fun currentLocation(done: (android.location.Location?) -> Unit) {
        if (!hasLocation()) { done(null); return }
        done(usableLocation())
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val p = future.get()
                val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
                val preview = Preview.Builder().setTargetRotation(rotation).build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setFlashMode(if (flash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)
                    .setTargetRotation(rotation).build()
                val desiredQuality = if (getSharedPreferences("camera_settings", MODE_PRIVATE).getString("video_quality", "HD") == "FHD") Quality.FHD else Quality.HD
                val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(desiredQuality)).build()
                videoCapture = VideoCapture.withOutput(recorder).also { it.targetRotation = rotation }
                p.unbindAll()
                camera = p.bindToLifecycle(this, selector, preview, imageCapture, videoCapture)
                val z = camera?.cameraInfo?.zoomState?.value
                if (z != null) {
                    val progress = ((1f - z.minZoomRatio) / (z.maxZoomRatio - z.minZoomRatio)).coerceIn(0f, 1f)
                    zoomSeek.progress = (progress * 100).toInt()
                    zoomLabel.text = "1.0×"
                    camera?.cameraControl?.setZoomRatio(1f.coerceIn(z.minZoomRatio, z.maxZoomRatio))
                }
                statusView.text = when {
                    !hasLocation() -> "Camera ready • Location unavailable; capture still works."
                    usableLocation() != null -> "GPS ready • ±${usableLocation()!!.accuracy.toInt()} m • refreshing"
                    else -> "Searching for a reliable GPS location… • capture remains available"
                }
                if (hasLocation()) startLocationUpdates()
            } catch (e: Exception) { statusView.text = "Camera error: ${e.localizedMessage ?: "unable to start"}" }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setVideoMode(enabled: Boolean) {
        videoMode = enabled
        photoModeButton.text = if (enabled) "PHOTO" else "● PHOTO"
        videoModeButton.text = if (enabled) "● VIDEO" else "VIDEO"
        shutter.text = if (enabled) "●  START VIDEO" else "●  TAKE PHOTO"
    }

    private fun capturePhoto() {
        val capture = imageCapture ?: return toast("Camera is not ready")
        if (busy || recording != null) return
        busy = true; shutter.isEnabled = false; statusView.text = "Capturing photo…"
        currentLocation { location ->
            val date = Date()
            val temp = File(cacheDir, "capture_${System.currentTimeMillis()}.jpg")
            capture.targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
            capture.takePicture(ImageCapture.OutputFileOptions.Builder(temp).build(), ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        try {
                            val exif = ExifInterface(temp.absolutePath)
                            val format = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getDefault() }
                            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, format.format(date))
                            exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, SimpleDateFormat("XXX", Locale.US).format(date))
                            if (location != null && getSharedPreferences("camera_settings", MODE_PRIVATE).getBoolean("exif_gps", true)) exif.setLatLong(location.latitude, location.longitude)
                            exif.saveAttributes()
                            val name = "GTC_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(date)}.jpg"
                            val values = ContentValues().apply {
                                put(MediaStore.Images.Media.DISPLAY_NAME, name); put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                                if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/GeoTag Worksite Camera")
                            }
                            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("Photo storage unavailable")
                            contentResolver.openOutputStream(uri)?.use { out -> temp.inputStream().use { it.copyTo(out) } } ?: error("Cannot write photo")
                            val record = makeRecord(name, uri.toString(), "PHOTO", date, location)
                            AppStore.save(this@MainActivity, record)
                            resolveAddress(record)
                            runOnUiThread { updateWatermark(record); startActivity(android.content.Intent(this@MainActivity, MediaDetailActivity::class.java).putExtra("mediaId", record.id)) }
                        } catch (e: Exception) { runOnUiThread { toast("Photo save failed: ${e.message}") } }
                        finally { temp.delete(); busy = false; runOnUiThread { shutter.isEnabled = true; statusView.text = "Camera ready • GPS recorded when available." } }
                    }
                    override fun onError(exception: ImageCaptureException) {
                        temp.delete(); busy = false
                        runOnUiThread { shutter.isEnabled = true; toast("Capture failed: ${exception.message}") }
                    }
                })
        }
    }

    private fun toggleVideo() {
        if (recording != null) { recording?.stop(); shutter.isEnabled = false; statusView.text = "Finalising video…"; return }
        if (busy) return
        if (videoCapture == null) return toast("Video camera is not ready")
        busy = true
        currentLocation { location ->
            pendingVideoLocation = location
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                beginVideoRecording(location, true)
            } else {
                audioPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun beginVideoRecording(location: android.location.Location?, withAudio: Boolean) {
        val capture = videoCapture ?: run { busy = false; return }
        videoLocation = location; videoStartedAt = Date()
        val temp = File(cacheDir, "video_${System.currentTimeMillis()}.mp4")
        try {
            var pending = capture.output.prepareRecording(this, FileOutputOptions.Builder(temp).build())
            if (withAudio && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) pending = pending.withAudioEnabled()
            capture.targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
            recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        shutter.text = "■  STOP VIDEO"; shutter.isEnabled = true
                        statusView.text = "Recording • Location will be embedded when available."
                    }
                    is VideoRecordEvent.Finalize -> {
                        recording = null
                        if (event.hasError()) {
                            temp.delete(); busy = false; shutter.isEnabled = true
                            toast("Video recording failed: ${event.error}")
                        } else {
                            worker.execute {
                                saveVideo(temp, videoStartedAt, videoLocation)
                                temp.delete()
                                runOnUiThread { busy = false; shutter.isEnabled = true; shutter.text = if (videoMode) "●  START VIDEO" else "●  TAKE PHOTO"; statusView.text = "Camera ready • GPS recorded when available." }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            temp.delete(); recording = null; busy = false; shutter.isEnabled = true
            toast("Could not start video: ${e.message}")
        }
    }

    private fun saveVideo(file: File, date: Date, location: android.location.Location?) {
        try {
            val geoFile = File(cacheDir, "geotag_${System.currentTimeMillis()}.mp4")
            val source = if (location != null && Mp4GeoTagger.addLocation(file, geoFile, location.latitude, location.longitude)) geoFile else file
            val name = "GTC_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(date)}.mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name); put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/GeoTag Worksite Camera")
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("Video storage unavailable")
            contentResolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } } ?: error("Cannot save video")
            geoFile.delete()
            val record = makeRecord(name, uri.toString(), "VIDEO", date, location)
            AppStore.save(this, record)
            resolveAddress(record)
            runOnUiThread { startActivity(android.content.Intent(this, MediaDetailActivity::class.java).putExtra("mediaId", record.id)) }
        } catch (e: Exception) { runOnUiThread { toast("Video save failed: ${e.message}") } }
    }

    private fun makeRecord(name: String, uri: String, type: String, date: Date, location: android.location.Location?) =
        MediaRecord(
            id = name, uri = uri, name = name, type = type,
            capturedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply { timeZone = TimeZone.getDefault() }.format(date),
            timeZone = TimeZone.getDefault().id, latitude = location?.latitude, longitude = location?.longitude,
            accuracy = location?.accuracy, heading = lastHeadingDegrees
        )

    private fun resolveAddress(record: MediaRecord) {
        if (record.latitude == null || record.longitude == null || !Geocoder.isPresent()) return
        worker.execute {
            try {
                @Suppress("DEPRECATION")
                val address = Geocoder(this, Locale.getDefault()).getFromLocation(record.latitude!!, record.longitude!!, 1)
                    ?.firstOrNull()?.getAddressLine(0).orEmpty()
                if (address.isNotBlank()) {
                    record.address = address
                    AppStore.save(this, record)
                }
            } catch (_: Exception) { }
        }
    }

    private fun updateWatermark(record: MediaRecord) {
        watermarkText.text = "${record.capturedAt.replace("T", " ")}\n${AppStore.fmtLocation(record)}\n${record.accuracy?.let { "Accuracy ±${it.toInt()} m" } ?: "GPS unavailable"}"
        if (record.latitude != null && record.longitude != null) Thread {
            val bitmap = AppStore.mapTile(this, record.latitude!!, record.longitude!!)
            runOnUiThread {
                if (bitmap != null) {
                    mapThumb.visibility = View.VISIBLE
                    mapThumb.setImageBitmap(bitmap)
                } else {
                    mapThumb.visibility = View.GONE
                    watermarkText.text = watermarkText.text.toString() + "\nMap unavailable offline"
                }
            }
        } else {
            mapThumb.visibility = View.GONE
            watermarkText.text = watermarkText.text.toString() + "\nLocation unavailable"
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    override fun onDestroy() { stopLocationUpdates(); recording?.stop(); worker.shutdown(); super.onDestroy() }
}
