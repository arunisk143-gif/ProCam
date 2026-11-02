package com.procam

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.util.Range
import android.util.Size
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlin.math.pow
import kotlin.math.roundToInt

class MainActivity : ComponentActivity(), CameraController.Listener {

    private lateinit var textureView: TextureView
    private lateinit var camera: CameraController
    private lateinit var statusText: TextView
    private lateinit var manualSwitch: Switch
    private lateinit var manualPanel: View
    private lateinit var isoSeek: SeekBar
    private lateinit var shutterSeek: SeekBar
    private lateinit var intensitySeek: SeekBar
    private lateinit var filterRow: LinearLayout

    private var isoRange: Range<Int>? = null
    private var exposureRange: Range<Long>? = null
    private var filter = ProFilter.NONE
    private var intensity = 1f

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) camera.start() else {
                Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
                statusText.text = "Camera permission denied"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        manualSwitch = findViewById(R.id.manualSwitch)
        manualPanel = findViewById(R.id.manualPanel)
        isoSeek = findViewById(R.id.isoSeek)
        shutterSeek = findViewById(R.id.shutterSeek)
        intensitySeek = findViewById(R.id.intensitySeek)
        filterRow = findViewById(R.id.filterRow)

        textureView = TextureView(this)
        findViewById<FrameLayout>(R.id.previewHolder).addView(
            textureView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.TOP
            )
        )
        camera = CameraController(this, textureView, this)

        buildFilterChips()

        val manualListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { pushManual() }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        }
        isoSeek.setOnSeekBarChangeListener(manualListener)
        shutterSeek.setOnSeekBarChangeListener(manualListener)
        manualSwitch.setOnCheckedChangeListener { _, checked ->
            manualPanel.visibility = if (checked) View.VISIBLE else View.GONE
            pushManual()
        }

        intensitySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                intensity = p / 100f
                updatePreviewFilter()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        findViewById<Button>(R.id.shutterButton).setOnClickListener {
            camera.capture(filter, intensity)
        }
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) camera.start()
        else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    override fun onPause() {
        camera.stop()
        super.onPause()
    }

    // ---------------------------------------------------------------- filters

    private fun buildFilterChips() {
        ProFilter.values().forEach { f ->
            val chip = TextView(this).apply {
                text = f.label
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(36, 18, 36, 18)
                setBackgroundResource(R.drawable.chip_bg)
                isSelected = f == filter
                setOnClickListener {
                    filter = f
                    for (i in 0 until filterRow.childCount) filterRow.getChildAt(i).isSelected = false
                    isSelected = true
                    setTextColor(0xFF000000.toInt())
                    refreshChipColors()
                    updatePreviewFilter()
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 16; topMargin = 8 }
            filterRow.addView(chip, lp)
        }
        refreshChipColors()
    }

    private fun refreshChipColors() {
        for (i in 0 until filterRow.childCount) {
            val c = filterRow.getChildAt(i) as TextView
            c.setTextColor(if (c.isSelected) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        }
    }

    private fun updatePreviewFilter() {
        textureView.setLayerPaint(FilterEngine.previewPaint(filter, intensity))
    }

    // ---------------------------------------------------------------- manual exposure

    private fun currentIso(): Int {
        val r = isoRange ?: return 100
        return (r.lower + (r.upper - r.lower) * (isoSeek.progress / 100f)).roundToInt()
    }

    /** Logarithmic shutter slider from the sensor minimum up to 1/2 s. */
    private fun currentExposureNs(): Long {
        val r = exposureRange ?: return 10_000_000L
        val max = minOf(r.upper, 500_000_000L).toDouble()
        val min = r.lower.toDouble()
        return (min * (max / min).pow(shutterSeek.progress / 100.0)).toLong()
    }

    private fun pushManual() {
        val enabled = manualSwitch.isChecked
        val iso = currentIso()
        val exp = currentExposureNs()
        camera.setManual(enabled, iso, exp)
        if (enabled) {
            val shutter = if (exp >= 1_000_000_000L / 2) "1/2"
            else "1/${(1_000_000_000.0 / exp).roundToInt()}"
            statusText.text = "ISO $iso  |  $shutter s"
        }
    }

    // ---------------------------------------------------------------- CameraController.Listener

    override fun onReady(supportsRaw: Boolean, iso: Range<Int>?, exposureNs: Range<Long>?, previewSize: Size) {
        runOnUiThread {
            isoRange = iso
            exposureRange = exposureNs
            val width = resources.displayMetrics.widthPixels
            // Sensor is landscape, screen is portrait: swap width/height for the aspect ratio.
            val height = (width * previewSize.width.toFloat() / previewSize.height).roundToInt()
            textureView.layoutParams = FrameLayout.LayoutParams(width, height, Gravity.TOP)
            updatePreviewFilter()
        }
    }

    override fun onStatus(message: String) {
        runOnUiThread { statusText.text = message }
    }
}
