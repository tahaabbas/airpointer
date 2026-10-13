package com.airpointer.remote

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlin.math.abs
import kotlin.math.max

/**
 * Full-screen camera that looks for the computer's pairing QR code (an airpointer://pair link) and
 * returns it as [EXTRA_LINK]. Plain Camera2 for the picture, ZXing to read the code.
 */
class ScanActivity : Activity() {

    private lateinit var preview: TextureView
    private lateinit var hint: TextView
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var worker: HandlerThread? = null
    private var previewSize = Size(1280, 720)
    @Volatile private var done = false

    private val decoder = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.ALSO_INVERTED to true, // light-on-dark codes, e.g. in a terminal
            ),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        preview = TextureView(this)
        hint = TextView(this).apply {
            text = "Point at the code on your computer"
            setTextColor(getColor(R.color.text))
            textSize = 16f
            gravity = Gravity.CENTER
            setBackgroundColor(0xCC0D1117.toInt())
            val p = (16 * density).toInt()
            setPadding(p, p, p, p)
        }
        val cancel = Button(this, null, 0, R.style.Btn).apply {
            text = "Cancel"
            setOnClickListener { finish() }
        }
        setContentView(FrameLayout(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(0xFF000000.toInt())
            addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(ViewfinderView(context), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(hint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
            addView(cancel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = (24 * density).toInt() })
        })
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            return
        }
        start()
    }

    override fun onPause() {
        super.onPause()
        stop()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA && grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            hint.text = "Allow the camera to scan the code"
        }
    }

    private fun start() {
        if (worker != null) return
        worker = HandlerThread("airpointer-scan").also { it.start() }
        if (preview.isAvailable) openCamera()
        else preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = openCamera()
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = fitPreview()
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    private fun stop() {
        runCatching { session?.close() }
        runCatching { camera?.close() }
        runCatching { reader?.close() }
        session = null; camera = null; reader = null
        worker?.quitSafely()
        worker = null
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val handler = Handler((worker ?: return).looper)
        val manager = getSystemService(CameraManager::class.java)
        val id = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList.firstOrNull()
        if (id == null) {
            hint.text = "No camera found"
            return
        }
        val sizes = manager.getCameraCharacteristics(id)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            .getOutputSizes(ImageFormat.YUV_420_888)
        // ~720p: plenty for a QR code on a monitor, cheap to decode.
        previewSize = sizes.minByOrNull { abs(it.width * it.height - 1280 * 720) } ?: Size(1280, 720)
        fitPreview()

        val r = ImageReader.newInstance(previewSize.width, previewSize.height, ImageFormat.YUV_420_888, 2)
        r.setOnImageAvailableListener({ decode(it) }, handler)
        reader = r

        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                camera = device
                val texture = preview.surfaceTexture ?: return
                texture.setDefaultBufferSize(previewSize.width, previewSize.height)
                val surfaces = listOf(Surface(texture), r.surface)
                @Suppress("DEPRECATION")
                device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            surfaces.forEach { addTarget(it) }
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        }.build()
                        runCatching { s.setRepeatingRequest(request, null, handler) }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        runOnUiThread { hint.text = "Couldn't start the camera." }
                    }
                }, handler)
            }

            override fun onDisconnected(device: CameraDevice) = device.close()
            override fun onError(device: CameraDevice, error: Int) {
                device.close()
                runOnUiThread { hint.text = "Couldn't open the camera (error $error)." }
            }
        }, handler)
    }

    /** Center-crop the (rotated, portrait) camera picture into the view instead of stretching it. */
    private fun fitPreview() {
        val w = preview.width.toFloat()
        val h = preview.height.toFloat()
        if (w == 0f || h == 0f) return
        val cw = previewSize.height.toFloat() // portrait: the sensor's width runs vertically
        val ch = previewSize.width.toFloat()
        val scale = max(w / cw, h / ch)
        preview.setTransform(Matrix().apply { setScale(cw * scale / w, ch * scale / h, w / 2f, h / 2f) })
    }

    /** Camera thread: reads the brightness (Y) plane; QR codes need nothing else. */
    private fun decode(r: ImageReader) {
        val image = r.acquireLatestImage() ?: return
        try {
            if (done) return
            val plane = image.planes[0]
            val stride = plane.rowStride
            val buffer = plane.buffer
            val data = ByteArray(stride * image.height)
            buffer.get(data, 0, minOf(buffer.remaining(), data.size))
            val source = PlanarYUVLuminanceSource(data, stride, image.height, 0, 0, image.width, image.height, false)
            val text = try {
                decoder.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
            } catch (e: NotFoundException) {
                return
            } finally {
                decoder.reset()
            }
            runOnUiThread { onCode(text) }
        } finally {
            image.close()
        }
    }

    private fun onCode(text: String) {
        if (done) return
        if (PairingLink.parse(text) == null) {
            hint.text = "Not an AirPointer code"
            return
        }
        done = true
        preview.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        setResult(RESULT_OK, Intent().putExtra(EXTRA_LINK, text))
        finish()
    }

    companion object {
        const val EXTRA_LINK = "link"
        private const val REQ_CAMERA = 1
    }
}
