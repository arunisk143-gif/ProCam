package com.procam

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Camera2 engine. One still capture produces, from the same exposure:
 *   1. RAW_SENSOR  -> saved as .dng (lossless, unprocessed)
 *   2. YUV_420_888 -> converted to a bitmap and saved as PNG (lossless, no JPEG step)
 *   3. optional filtered PNG (filter applied to a copy; originals stay untouched)
 */
class CameraController(
    private val context: Context,
    private val textureView: TextureView,
    private val listener: Listener
) {
    interface Listener {
        fun onReady(supportsRaw: Boolean, iso: Range<Int>?, exposureNs: Range<Long>?, previewSize: Size)
        fun onStatus(message: String)
    }

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val worker = Executors.newSingleThreadExecutor()

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var characteristics: CameraCharacteristics? = null
    private var yuvReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var previewBuilder: CaptureRequest.Builder? = null

    @Volatile private var manual = false
    @Volatile private var iso = 100
    @Volatile private var exposureNs = 10_000_000L

    private var pending: Pending? = null
    @Volatile private var busy = false

    private class Pending(
        val wantRaw: Boolean,
        val filter: ProFilter,
        val intensity: Float
    ) {
        var yuv: Image? = null
        var raw: Image? = null
        var result: TotalCaptureResult? = null
        fun complete() = yuv != null && result != null && (!wantRaw || raw != null)
        fun close() {
            yuv?.close(); raw?.close()
            yuv = null; raw = null
        }
    }

    // ---------------------------------------------------------------- lifecycle

    fun start() {
        thread = HandlerThread("procam-camera").also { it.start() }
        handler = Handler(thread!!.looper)
        if (textureView.isAvailable) {
            openCamera()
        } else {
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = openCamera()
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }
    }

    fun stop() {
        try { session?.close() } catch (_: Exception) {}
        try { device?.close() } catch (_: Exception) {}
        yuvReader?.close(); rawReader?.close()
        session = null; device = null; yuvReader = null; rawReader = null
        pending?.close(); pending = null
        thread?.quitSafely(); thread = null; handler = null
    }

    // ---------------------------------------------------------------- setup

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        try {
            val id = manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: return listener.onStatus("No back camera found")

            val chars = manager.getCameraCharacteristics(id)
            characteristics = chars
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!

            val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            val rawSize = if (caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW))
                map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height }
            else null
            val yuvSize = map.getOutputSizes(ImageFormat.YUV_420_888)
                .maxByOrNull { it.width.toLong() * it.height }!!

            val aspect = yuvSize.width.toFloat() / yuvSize.height
            val previewSize = map.getOutputSizes(SurfaceTexture::class.java)
                .filter { it.width <= 1920 && kotlin.math.abs(it.width.toFloat() / it.height - aspect) < 0.02f }
                .maxByOrNull { it.width.toLong() * it.height }
                ?: map.getOutputSizes(SurfaceTexture::class.java).first()

            yuvReader = ImageReader.newInstance(yuvSize.width, yuvSize.height, ImageFormat.YUV_420_888, 2).also {
                it.setOnImageAvailableListener({ r ->
                    val img = r.acquireNextImage() ?: return@setOnImageAvailableListener
                    val p = pending
                    if (p == null) img.close() else { p.yuv = img; checkDone(p) }
                }, handler)
            }
            rawReader = rawSize?.let { size ->
                ImageReader.newInstance(size.width, size.height, ImageFormat.RAW_SENSOR, 2).also {
                    it.setOnImageAvailableListener({ r ->
                        val img = r.acquireNextImage() ?: return@setOnImageAvailableListener
                        val p = pending
                        if (p == null) img.close() else { p.raw = img; checkDone(p) }
                    }, handler)
                }
            }

            val st = textureView.surfaceTexture!!
            st.setDefaultBufferSize(previewSize.width, previewSize.height)
            previewSurface = Surface(st)

            listener.onReady(
                rawReader != null,
                chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
                chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
                previewSize
            )

            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) { device = camera; createSession() }
                override fun onDisconnected(camera: CameraDevice) { camera.close() }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close(); listener.onStatus("Camera error $error")
                }
            }, handler)
        } catch (e: Exception) {
            listener.onStatus("Open failed: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun createSession() {
        val cam = device ?: return
        val targets = mutableListOf(previewSurface!!, yuvReader!!.surface)
        rawReader?.let { targets.add(it.surface) }
        cam.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                startPreview()
            }
            override fun onConfigureFailed(s: CameraCaptureSession) {
                listener.onStatus("Camera session failed")
            }
        }, handler)
    }

    private fun startPreview() {
        val cam = device ?: return
        val s = session ?: return
        previewBuilder = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface!!)
            applyExposure(this)
        }
        s.setRepeatingRequest(previewBuilder!!.build(), null, handler)
        listener.onStatus(if (rawReader != null) "Ready - RAW + PNG" else "Ready - PNG (RAW not supported)")
    }

    private fun applyExposure(b: CaptureRequest.Builder) {
        b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        if (manual) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }
    }

    // ---------------------------------------------------------------- manual controls

    fun setManual(enabled: Boolean, isoValue: Int, exposure: Long) {
        manual = enabled; iso = isoValue; exposureNs = exposure
        refreshPreview()
    }

    private fun refreshPreview() {
        val b = previewBuilder ?: return
        val s = session ?: return
        applyExposure(b)
        try { s.setRepeatingRequest(b.build(), null, handler) } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- capture

    fun capture(filter: ProFilter, intensity: Float) {
        val cam = device ?: return
        val s = session ?: return
        if (busy) { listener.onStatus("Still saving the previous shot..."); return }
        busy = true
        val p = Pending(rawReader != null, filter, intensity)
        pending = p
        try {
            val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(yuvReader!!.surface)
                rawReader?.let { addTarget(it.surface) }
                applyExposure(this)
                set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
            }
            s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult
                ) { p.result = result; checkDone(p) }

                override fun onCaptureFailed(
                    session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure
                ) {
                    p.close(); pending = null; busy = false
                    listener.onStatus("Capture failed")
                }
            }, handler)
            listener.onStatus("Capturing...")
        } catch (e: Exception) {
            p.close(); pending = null; busy = false
            listener.onStatus("Capture error: ${e.message}")
        }
    }

    private fun checkDone(p: Pending) {
        if (!p.complete()) return
        pending = null
        worker.execute { process(p) }
    }

    // ---------------------------------------------------------------- processing / saving

    private fun process(p: Pending) {
        try {
            val chars = characteristics!!
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val base = "PROCAM_$stamp"
            val sensorRotation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

            // 1) RAW -> DNG (lossless, untouched sensor data)
            if (p.wantRaw && p.raw != null && p.result != null) {
                val dng = DngCreator(chars, p.result!!).setOrientation(exifOrientation(sensorRotation))
                save("$base.dng", "image/x-adobe-dng") { out -> dng.writeImage(out, p.raw!!) }
                dng.close()
            }

            // 2) YUV -> PNG original (lossless, no JPEG step anywhere)
            val upright = rotate(yuvToBitmap(p.yuv!!), sensorRotation)
            save("${base}_original.png", "image/png") { out ->
                upright.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            // 3) Optional filtered copy; originals above are never modified
            if (p.filter != ProFilter.NONE && p.intensity > 0f) {
                val filtered = FilterEngine.apply(upright, p.filter, p.intensity)
                save("${base}_${p.filter.label.lowercase(Locale.US)}.png", "image/png") { out ->
                    filtered.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                filtered.recycle()
            }
            upright.recycle()
            listener.onStatus("Saved to Pictures/ProCam: $base")
        } catch (e: Exception) {
            listener.onStatus("Save failed: ${e.message}")
        } finally {
            p.close()
            busy = false
        }
    }

    private fun save(name: String, mime: String, write: (OutputStream) -> Unit) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ProCam")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create $name")
        resolver.openOutputStream(uri)!!.use(write)
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }

    /** Full-range BT.601 YUV_420_888 -> ARGB, no lossy intermediate format. */
    private fun yuvToBitmap(image: Image): Bitmap {
        val w = image.width
        val h = image.height
        val yP = image.planes[0]
        val uP = image.planes[1]
        val vP = image.planes[2]
        val yBuf = yP.buffer
        val uBuf = uP.buffer
        val vBuf = vP.buffer
        val pixels = IntArray(w * h)
        for (row in 0 until h) {
            val yRow = row * yP.rowStride
            val uvRow = row shr 1
            val uRow = uvRow * uP.rowStride
            val vRow = uvRow * vP.rowStride
            val outRow = row * w
            for (col in 0 until w) {
                val y = yBuf.get(yRow + col * yP.pixelStride).toInt() and 0xFF
                val u = (uBuf.get(uRow + (col shr 1) * uP.pixelStride).toInt() and 0xFF) - 128
                val v = (vBuf.get(vRow + (col shr 1) * vP.pixelStride).toInt() and 0xFF) - 128
                val r = (y + ((1436 * v) shr 10)).coerceIn(0, 255)
                val g = (y - ((352 * u + 731 * v) shr 10)).coerceIn(0, 255)
                val b = (y + ((1814 * u) shr 10)).coerceIn(0, 255)
                pixels[outRow + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun rotate(src: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return src
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        src.recycle()
        return out
    }

    private fun exifOrientation(sensorRotation: Int) = when (sensorRotation) {
        90 -> 6   // ORIENTATION_ROTATE_90
        180 -> 3  // ORIENTATION_ROTATE_180
        270 -> 8  // ORIENTATION_ROTATE_270
        else -> 1 // ORIENTATION_NORMAL
    }
}
