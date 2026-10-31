package com.parabg.geotagcamera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var statusView: TextView
    private lateinit var captureButton: Button
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var fusedLocationClient: com.google.android.gms.location.FusedLocationProviderClient
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var flashEnabled = false
    private var isCapturing = false

    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.CAMERA] == true) {
            startCamera()
        } else {
            statusView.text = "Camera permission is required to take photos."
            captureButton.isEnabled = false
        }
        updateLocationPermissionStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        buildCameraScreen()
        checkPermissions()
    }

    private fun buildCameraScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(10))
            setBackgroundColor(Color.rgb(18, 18, 18))
        }
        header.addView(TextView(this).apply {
            text = "GEOTAG WORKSITE CAMERA"
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        statusView = TextView(this).apply {
            text = "Checking camera and GPS…"
            setTextColor(Color.LTGRAY)
            textSize = 12f
            setPadding(0, dp(5), 0, 0)
        }
        header.addView(statusView)
        root.addView(header)

        val previewFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        previewFrame.addView(previewView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        root.addView(previewFrame, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(14), dp(12), dp(18))
            setBackgroundColor(Color.rgb(18, 18, 18))
        }
        val topRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
        }
        topRow.addView(actionButton("Gallery") {
            startActivity(android.content.Intent(this, GalleryActivity::class.java))
        }, weightedButtonParams())
        topRow.addView(actionButton(if (flashEnabled) "Flash: ON" else "Flash: OFF") {
            flashEnabled = !flashEnabled
            imageCapture?.flashMode = if (flashEnabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
            (it as Button).text = if (flashEnabled) "Flash: ON" else "Flash: OFF"
        }, weightedButtonParams())
        topRow.addView(actionButton("Switch") {
            cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            startCamera()
        }, weightedButtonParams())
        controls.addView(topRow)

        captureButton = Button(this).apply {
            text = "●  TAKE PHOTO"
            textSize = 17f
            isAllCaps = false
            setTextColor(Color.BLACK)
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(255, 194, 71)))
            setOnClickListener { capturePhoto() }
        }
        val captureParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(58)
        ).apply { topMargin = dp(14) }
        controls.addView(captureButton, captureParams)

        val note = TextView(this).apply {
            text = "Photos are saved on this device. A project is not required."
            textSize = 11f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(9), 0, 0)
        }
        controls.addView(note)
        root.addView(controls)
        setContentView(root)
    }

    private fun actionButton(label: String, click: (android.view.View) -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(54, 54, 54)))
            setOnClickListener(click)
        }

    private fun weightedButtonParams() = LinearLayout.LayoutParams(0, dp(46), 1f).apply {
        marginEnd = dp(4)
    }

    private fun checkPermissions() {
        val needed = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 23) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startCamera()
            updateLocationPermissionStatus()
        } else {
            permissionRequest.launch(missing.toTypedArray())
        }
    }

    private fun updateLocationPermissionStatus() {
        val hasLocation = hasLocationPermission()
        if (!hasLocation) statusView.text = "GPS unavailable or permission denied — photos can still be taken."
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setFlashMode(if (flashEnabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)
                    .build()
                provider.unbindAll()
                provider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
                captureButton.isEnabled = true
                if (hasLocationPermission()) statusView.text = "Camera ready • GPS will be recorded when available."
                else statusView.text = "Camera ready • GPS unavailable; capture remains enabled."
            } catch (error: Exception) {
                statusView.text = "Unable to start camera: ${error.localizedMessage ?: "camera unavailable"}"
                captureButton.isEnabled = false
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun capturePhoto() {
        val capture = imageCapture ?: run {
            toast("Camera is not ready")
            return
        }
        if (isCapturing) return
        isCapturing = true
        captureButton.isEnabled = false
        statusView.text = "Capturing photo…"
        capture.targetRotation = previewView.display?.rotation ?: windowManager.defaultDisplay.rotation

        if (hasLocationPermission()) {
            try {
                fusedLocationClient.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    CancellationTokenSource().token
                ).addOnSuccessListener { location ->
                    takePicture(location)
                }.addOnFailureListener {
                    takePicture(null)
                }
            } catch (_: SecurityException) {
                takePicture(null)
            }
        } else {
            takePicture(null)
        }
    }

    private fun takePicture(location: android.location.Location?) {
        val capture = imageCapture ?: run {
            finishCaptureState()
            return
        }
        val tempFile = File(cacheDir, "capture_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(tempFile).build()
        capture.takePicture(options, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    try {
                        val now = Date()
                        val timeFormat = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply {
                            timeZone = TimeZone.getDefault()
                        }
                        val offsetFormat = SimpleDateFormat("XXX", Locale.US).apply {
                            timeZone = TimeZone.getDefault()
                        }
                        val exif = ExifInterface(tempFile.absolutePath)
                        exif.setAttribute(ExifInterface.TAG_DATETIME, timeFormat.format(now))
                        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, timeFormat.format(now))
                        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offsetFormat.format(now))
                        if (location != null) {
                            exif.setLatLong(location.latitude, location.longitude)
                            exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP,
                                SimpleDateFormat("yyyy:MM:dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                                    .format(now))
                        }
                        exif.saveAttributes()
                        saveToGallery(tempFile, now, location)
                    } catch (error: Exception) {
                        runOnUiThread { toast("Could not save photo: ${error.localizedMessage}") }
                        finishCaptureState()
                    } finally {
                        tempFile.delete()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    tempFile.delete()
                    runOnUiThread {
                        toast("Capture failed: ${exception.message}")
                        statusView.text = "Capture failed. Please try again."
                    }
                    finishCaptureState()
                }
            })
    }

    private fun saveToGallery(file: File, date: Date, location: android.location.Location?) {
        val name = "GTC_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(date)}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/GeoTag Worksite Camera")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Android could not create the photo record")
        try {
            contentResolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IllegalStateException("Could not open photo storage")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val ready = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                contentResolver.update(uri, ready, null, null)
            }
            writeMetadata(name, uri, date, location)
            runOnUiThread {
                statusView.text = if (location != null) {
                    "Saved • GPS ±${location.accuracy.toInt()} m • ${location.latitude}, ${location.longitude}"
                } else {
                    "Saved • GPS unavailable • You can still use this photo."
                }
                toast("Photo saved to Pictures / GeoTag Worksite Camera")
            }
        } catch (error: Exception) {
            contentResolver.delete(uri, null, null)
            throw error
        } finally {
            finishCaptureState()
        }
    }

    private fun writeMetadata(
        name: String,
        uri: android.net.Uri,
        date: Date,
        location: android.location.Location?
    ) {
        val metadataDir = File(filesDir, "metadata").apply { mkdirs() }
        val json = JSONObject().apply {
            put("displayName", name)
            put("uri", uri.toString())
            put("capturedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
                .apply { timeZone = TimeZone.getDefault() }.format(date))
            put("timeZone", java.time.ZoneId.systemDefault().id)
            put("latitude", location?.latitude ?: JSONObject.NULL)
            put("longitude", location?.longitude ?: JSONObject.NULL)
            put("accuracyMeters", location?.accuracy ?: JSONObject.NULL)
            put("caption", "")
            put("project", JSONObject.NULL)
            put("site", JSONObject.NULL)
        }
        File(metadataDir, "$name.json").writeText(json.toString(2))
    }

    private fun finishCaptureState() {
        runOnUiThread {
            isCapturing = false
            captureButton.isEnabled = imageCapture != null
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
