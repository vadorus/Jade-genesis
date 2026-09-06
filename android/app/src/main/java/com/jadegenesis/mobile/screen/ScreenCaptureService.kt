package com.jadegenesis.mobile.screen

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import com.jadegenesis.mobile.MainActivity
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureService : Service() {

    companion object {
        const val MODE_IMMEDIATE = "immediate"
        const val MODE_ARMED = "armed"

        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val EXTRA_MODE = "capture_mode"
        private const val ACTION_CAPTURE_NOW = "com.jadegenesis.mobile.screen.CAPTURE_NOW"
        private const val ACTION_CANCEL = "com.jadegenesis.mobile.screen.CANCEL"
        private const val CHANNEL_ID = "jade_screen_observer"
        private const val NOTIFICATION_ID = 4101
        private const val READY_NOTIFICATION_ID = 4102

        private const val IMMEDIATE_SETTLE_MS = 1_300L
        private const val ARMED_SETTLE_MS = 850L
        private const val CAPTURE_TIMEOUT_MS = 10_000L
        private const val ARMED_SESSION_TIMEOUT_MS = 10 * 60 * 1_000L

        fun startCapture(
            context: Context,
            resultCode: Int,
            resultData: Intent,
            mode: String = MODE_IMMEDIATE
        ) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
                putExtra(EXTRA_MODE, mode)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var mode: String = MODE_IMMEDIATE
    private val captureStarted = AtomicBoolean(false)
    private val captured = AtomicBoolean(false)
    private val finishing = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()

        when (intent?.action) {
            ACTION_CAPTURE_NOW -> {
                if (projection != null && mode == MODE_ARMED) {
                    beginCapture(ARMED_SETTLE_MS)
                }
                return START_NOT_STICKY
            }
            ACTION_CANCEL -> {
                finishCapture(false)
                return START_NOT_STICKY
            }
        }

        if (projection != null) return START_NOT_STICKY

        mode = intent?.getStringExtra(EXTRA_MODE)
            ?.takeIf { it == MODE_ARMED || it == MODE_IMMEDIATE }
            ?: MODE_IMMEDIATE

        startForeground(
            NOTIFICATION_ID,
            if (mode == MODE_ARMED) armedNotification() else immediateNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
            ?: Int.MIN_VALUE
        @Suppress("DEPRECATION")
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultCode == Int.MIN_VALUE || resultData == null) {
            finishCapture(false)
            return START_NOT_STICKY
        }

        return runCatching {
            startProjection(resultCode, resultData)
            START_NOT_STICKY
        }.getOrElse {
            finishCapture(false)
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
                    finishCapture(false)
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
        workerHandler = handler

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

        if (mode == MODE_IMMEDIATE) {
            beginCapture(IMMEDIATE_SETTLE_MS)
        } else {
            handler.postDelayed({
                if (!captureStarted.get() && !finishing.get()) {
                    finishCapture(false)
                }
            }, ARMED_SESSION_TIMEOUT_MS)
        }
    }

    private fun beginCapture(settleMs: Long) {
        if (!captureStarted.compareAndSet(false, true)) return
        val reader = imageReader ?: return finishCapture(false)
        val handler = workerHandler ?: return finishCapture(false)

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(
            NOTIFICATION_ID,
            android.app.Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("Jade — capture en cours")
                .setContentText("Laisse le panneau se refermer, Jade capture ensuite l'écran.")
                .setOngoing(true)
                .build()
        )

        handler.postDelayed({
            if (captured.get() || finishing.get()) return@postDelayed
            runCatching { reader.acquireLatestImage()?.close() }
            reader.setOnImageAvailableListener({ source ->
                if (captured.get() || finishing.get()) {
                    source.acquireLatestImage()?.close()
                    return@setOnImageAvailableListener
                }
                val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                if (!captured.compareAndSet(false, true)) {
                    image.close()
                    return@setOnImageAvailableListener
                }
                try {
                    saveImage(image)
                    finishCapture(true)
                } catch (_: Throwable) {
                    finishCapture(false)
                } finally {
                    image.close()
                }
            }, handler)
        }, settleMs)

        handler.postDelayed({
            if (!captured.get() && !finishing.get()) finishCapture(false)
        }, settleMs + CAPTURE_TIMEOUT_MS)
    }

    private fun saveImage(image: Image) {
        val width = image.width
        val height = image.height
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
        try {
            ScreenObserverRepository(this).saveBitmap(
                bitmap = cropped,
                source = if (mode == MODE_ARMED) "pixel_screen_armed" else "pixel_screen",
                focusInstruction = ""
            )
        } finally {
            cropped.recycle()
        }
    }

    private fun immediateNotification(): android.app.Notification =
        android.app.Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Jade — observation d'écran")
            .setContentText("Capture unique autorisée — stabilisation de l'image en cours")
            .setOngoing(true)
            .build()

    private fun armedNotification(): android.app.Notification {
        val captureIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_CAPTURE_NOW
        }
        val cancelIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_CANCEL
        }
        val capturePending = PendingIntent.getService(
            this,
            41011,
            captureIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancelPending = PendingIntent.getService(
            this,
            41012,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return android.app.Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Jade est prête à observer")
            .setContentText("Ouvre l'application voulue, puis touche « Capturer maintenant ».")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_camera, "Capturer maintenant", capturePending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Annuler", cancelPending)
            .build()
    }

    private fun postReadyNotification() {
        val openCrop = Intent(this, FocusCropActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pending = PendingIntent.getActivity(
            this,
            41021,
            openCrop,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        getSystemService(NotificationManager::class.java).notify(
            READY_NOTIFICATION_ID,
            android.app.Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_crop)
                .setContentTitle("Jade — capture prête")
                .setContentText("Touchez pour choisir la zone et préciser ce que Jade doit regarder.")
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build()
        )
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Jade Screen Observer",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Observation d'écran visible, déclenchée uniquement par l'utilisateur."
                }
            )
        }
    }

    @Synchronized
    private fun finishCapture(success: Boolean) {
        if (!finishing.compareAndSet(false, true)) return
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
        workerHandler = null
        if (success && mode == MODE_ARMED) runCatching { postReadyNotification() }
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun onDestroy() {
        if (!finishing.get()) finishCapture(false)
        super.onDestroy()
    }
}
