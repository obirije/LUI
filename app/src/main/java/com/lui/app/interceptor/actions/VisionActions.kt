package com.lui.app.interceptor.actions

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import com.lui.app.helper.LuiLogger
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Captures a photo from the device camera and returns it as base64.
 * Used by the cloud LLM for vision analysis.
 */
object VisionActions {

    private const val TAG = "Vision"
    private val cameraThread = HandlerThread("LuiCamera").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)

    // Stores the last captured/selected image for the LLM to analyze
    var lastCapturedImage: String? = null
    var lastCapturedBitmap: Bitmap? = null

    fun takePhoto(context: Context): ActionResult {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        // Find back camera
        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            val characteristics = cameraManager.getCameraCharacteristics(id)
            characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: cameraManager.cameraIdList.firstOrNull()
        ?: return ActionResult.Failure("No camera found.")

        val latch = CountDownLatch(1)
        var resultImage: String? = null
        var error: String? = null

        val imageReader = ImageReader.newInstance(1920, 1080, ImageFormat.JPEG, 2)
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                try {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    image.close()

                    // Resize for API efficiency
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    val scaled = Bitmap.createScaledBitmap(bitmap, 640, 480, true)
                    bitmap.recycle()

                    val outputStream = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
                    resultImage = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
                    lastCapturedBitmap = scaled
                    lastCapturedImage = resultImage

                    LuiLogger.i(TAG, "Photo captured: ${resultImage!!.length} chars base64")
                } catch (e: Exception) {
                    error = e.message
                    LuiLogger.e(TAG, "Photo processing failed", e)
                }
                latch.countDown()
            }
        }, cameraHandler)

        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    try {
                        val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            addTarget(imageReader.surface)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                            set(CaptureRequest.JPEG_QUALITY, 85.toByte())
                        }

                        camera.createCaptureSession(listOf(imageReader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    // Warmup: let AE/AF settle for 500ms with repeating request
                                    val previewRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                        addTarget(imageReader.surface)
                                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                    }
                                    session.setRepeatingRequest(previewRequest.build(), null, cameraHandler)
                                    cameraHandler.postDelayed({
                                        try {
                                            session.stopRepeating()
                                            session.capture(captureRequest.build(), null, cameraHandler)
                                        } catch (e: Exception) {
                                            error = e.message; latch.countDown(); camera.close()
                                        }
                                    }, 800) // 800ms warmup for AE/AF
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    error = "Camera session failed"
                                    latch.countDown()
                                    camera.close()
                                }
                            }, cameraHandler)
                    } catch (e: Exception) {
                        error = e.message
                        latch.countDown()
                        camera.close()
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (resultImage == null) { error = "Camera disconnected"; latch.countDown() }
                }

                override fun onError(camera: CameraDevice, errorCode: Int) {
                    camera.close()
                    error = "Camera error: $errorCode"
                    latch.countDown()
                }
            }, cameraHandler)
        } catch (e: SecurityException) {
            return ActionResult.Failure("Camera permission required.")
        } catch (e: Exception) {
            return ActionResult.Failure("Couldn't open camera: ${e.message}")
        }

        latch.await(10, TimeUnit.SECONDS)

        return if (resultImage != null) {
            ActionResult.Success("__PHOTO_CAPTURED__Photo captured and attached. Now describe what you see in the image to the user.")
        } else {
            ActionResult.Failure("Couldn't capture photo: ${error ?: "timeout"}")
        }
    }
}
