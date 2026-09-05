package com.jadegenesis.mobile.screen

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.view.WindowManager
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class ScreenCaptureService : Service() {

    companion object {
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "jade_screen_observer"
        private const val NOTIFICATION_ID = 4101

        // V0.1.2: do not capture the Android MediaProjection consent overlay.
        private const val CAPTURE_SETTLE_MS = 1_300L
        private const val CAPTURE_TIMEOUT_MS = 9_000L

        // Keep text readable while staying below the remote vision payload budget.
        private const val MAX_CAPTURE_WIDTH = 960
        private const val TARGET_JPEG_BYTES = 1_050_000

        fun startCapture(
            context: Context,
            resultCode: Int,
            resultData: Intent
        ) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var workerThread: HandlerThread? = null
    private val captured = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = android.app.Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Jade — observation d'écran")
            .setContentText("Capture unique autorisée — stabilisation de l'image en cours")
            .setOngoing(true)
            .build()

        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
            ?: Int.MIN_VALUE
        @Suppress("DEPRECATION")
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultCode == Int.MIN_VALUE || resultData == null) {
            finishCapture()
            return START_NOT_STICKY
        }

        return runCatching {
            startProjection(resultCode, resultData)
            START_NOT_STICKY
        }.getOrElse {
            finishCapture()
            START_NOT_STICKY
        }
    }

    private fun startProjection(resultCode: Int, resultData: Intent) {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as MediaProjectionManager
        val mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
            ?: error("MediaProjection indisponible.")
        projection = mediaProjection
        mediaProjection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    finishCapture()
                }
            },
            Handler(mainLooper)
        )

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val bounds = windowManager.maximumWindowMetrics.bounds
        val width = bounds.width().coerceAtLeast(1)
        val height = bounds.height().coerceAtLeast(1)
        val density = resources.displayMetrics.densityDpi

        val reader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            3
        )
        imageReader = reader

        val thread = HandlerThread("jade-screen-capture").apply { start() }
        workerThread = thread
        val handler = Handler(thread.looper)

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "JadeScreenObserver",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler
        )

        // Let the Android consent UI disappear, then discard any queued stale frame.
        handler.postDelayed({
            if (captured.get()) return@postDelayed
            runCatching { reader.acquireLatestImage()?.close() }

            reader.setOnImageAvailableListener({ source ->
                if (captured.get()) {
                    source.acquireLatestImage()?.close()
                    return@setOnImageAvailableListener
                }
                val image = source.acquireLatestImage()
                    ?: return@setOnImageAvailableListener
                if (!captured.compareAndSet(false, true)) {
                    image.close()
                    return@setOnImageAvailableListener
                }
                try {
                    saveImage(image, width, height)
                } finally {
                    image.close()
                    finishCapture()
                }
            }, handler)
        }, CAPTURE_SETTLE_MS)

        handler.postDelayed({
            if (!captured.get()) {
                finishCapture()
            }
        }, CAPTURE_TIMEOUT_MS)
    }

    private fun saveImage(image: Image, width: Int, height: Int) {
        val plane = image.planes.firstOrNull() ?: error("Plan image absent.")
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val paddedWidth = width + rowPadding / pixelStride

        val padded = Bitmap.createBitmap(
            paddedWidth,
            height,
            Bitmap.Config.ARGB_8888
        )
        padded.copyPixelsFromBuffer(buffer)
        val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
        if (cropped !== padded) padded.recycle()

        val scale = if (width > MAX_CAPTURE_WIDTH) {
            MAX_CAPTURE_WIDTH.toDouble() / width.toDouble()
        } else {
            1.0
        }
        val output = if (scale < 1.0) {
            Bitmap.createScaledBitmap(
                cropped,
                MAX_CAPTURE_WIDTH,
                (height * scale).roundToInt().coerceAtLeast(1),
                true
            )
        } else {
            cropped
        }

        val encoded = encodeBoundedJpeg(output)
        val dir = File(filesDir, "screen-observer").apply { mkdirs() }
        val temp = File(dir, "latest.tmp.jpg")
        val target = File(dir, "latest.jpg")
        FileOutputStream(temp).use { stream ->
            stream.write(encoded)
            stream.flush()
        }
        if (target.exists()) target.delete()
        check(temp.renameTo(target)) {
            "Impossible de finaliser la capture."
        }

        if (output !== cropped) output.recycle()
        cropped.recycle()
    }

    private fun encodeBoundedJpeg(bitmap: Bitmap): ByteArray {
        var working = bitmap
        var ownsWorking = false
        var last = ByteArray(0)

        try {
            val widths = listOf(
                working.width,
                minOf(working.width, 840),
                minOf(working.width, 720)
            ).distinct()

            widths.forEach { targetWidth ->
                if (working.width != targetWidth) {
                    if (ownsWorking) working.recycle()
                    val ratio = targetWidth.toDouble() / working.width.toDouble()
                    working = Bitmap.createScaledBitmap(
                        working,
                        targetWidth,
                        (working.height * ratio).roundToInt().coerceAtLeast(1),
                        true
                    )
                    ownsWorking = true
                }

                listOf(78, 70, 62, 54, 46).forEach { quality ->
                    val output = ByteArrayOutputStream()
                    check(
                        working.compress(
                            Bitmap.CompressFormat.JPEG,
                            quality,
                            output
                        )
                    ) { "Échec compression JPEG." }
                    val bytes = output.toByteArray()
                    last = bytes
                    if (bytes.size <= TARGET_JPEG_BYTES) {
                        return bytes
                    }
                }
            }
            return last
        } finally {
            if (ownsWorking) working.recycle()
        }
    }

    @Synchronized
    private fun finishCapture() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.setOnImageAvailableListener(null, null) }
        runCatching { imageReader?.close() }
        imageReader = null
        val currentProjection = projection
        projection = null
        runCatching { currentProjection?.stop() }
        runCatching { workerThread?.quitSafely() }
        workerThread = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Jade Screen Observer",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Notification visible pendant une capture d'écran autorisée."
                }
            )
        }
    }

    override fun onDestroy() {
        finishCapture()
        super.onDestroy()
    }
}
