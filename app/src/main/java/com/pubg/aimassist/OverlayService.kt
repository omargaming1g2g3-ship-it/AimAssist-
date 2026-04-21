package com.pubg.aimassist

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class OverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var imageReader: ImageReader
    private lateinit var virtualDisplay: VirtualDisplay
    private lateinit var mediaProjection: MediaProjection
    private lateinit var interpreter: Interpreter
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = true
    private var targetX = -1
    private var targetY = -1
    private val inputSize = 640

    // إعادة استخدام البافرات
    private var reusableBitmap: Bitmap? = null
    private var reusableByteBuffer: ByteBuffer? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createOverlay()
        loadModel()
        startScreenCapture()
        startDetectionLoop()
    }

    private fun createOverlay() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        overlayView = object : View(this) {
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                if (targetX != -1 && targetY != -1) {
                    val paint = Paint().apply {
                        color = Color.RED
                        style = Paint.Style.STROKE
                        strokeWidth = 5f
                    }
                    canvas.drawRect(targetX - 60f, targetY - 120f, targetX + 60f, targetY + 60f, paint)
                    val dotPaint = Paint().apply {
                        color = Color.RED
                        style = Paint.Style.FILL
                    }
                    canvas.drawCircle(targetX.toFloat(), targetY.toFloat(), 12f, dotPaint)
                }
            }
        }
        windowManager.addView(overlayView, params)
    }

    private fun loadModel() {
        val gpuDelegate = if (CompatibilityList().isDelegateSupportedOnThisDevice) {
            GpuDelegate().apply { initialize() }
        } else null
        val options = Interpreter.Options().apply {
            gpuDelegate?.let { addDelegate(it) }   // إصلاح: إضافة فقط إذا لم يكن null
            setNumThreads(4)
        }
        interpreter = Interpreter(loadModelFile(), options)
    }

    private fun loadModelFile(): MappedByteBuffer {
        val assetFileDescriptor = assets.openFd("yolov8n_float32.tflite")
        val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY,
            assetFileDescriptor.startOffset,
            assetFileDescriptor.declaredLength)
    }

    private fun startScreenCapture() {
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE)
                as android.media.projection.MediaProjectionManager
        val data = intent?.getParcelableExtra<Intent>("data")
        if (data == null) {
            stopSelf()
            return
        }
        mediaProjection = projectionManager.getMediaProjection(RESULT_OK, data)
        val width = resources.displayMetrics.widthPixels
        val height = resources.displayMetrics.heightPixels
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "ScreenCapture", width, height, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface, null, null
        )
    }

    private fun startDetectionLoop() {
        Thread {
            var frameCount = 0
            while (isRunning) {
                val image = imageReader.acquireLatestImage() ?: continue
                val buffer = image.planes[0].buffer
                val pixels = ByteArray(buffer.remaining())
                buffer.get(pixels)
                image.close()

                if (reusableBitmap == null || reusableBitmap?.width != imageReader.width) {
                    reusableBitmap?.recycle()
                    reusableBitmap = Bitmap.createBitmap(imageReader.width, imageReader.height, Bitmap.Config.ARGB_8888)
                }
                val bitmap = reusableBitmap!!
                bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))

                val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
                val inputBuffer = bitmapToByteBuffer(resized)

                val output = Array(1) { Array(84) { FloatArray(8400) } }
                interpreter.run(inputBuffer, output)

                var bestConf = 0f
                var bestBox = floatArrayOf()
                for (i in 0 until 8400) {
                    val conf = output[0][4][i]
                    if (conf > 0.5f && conf > bestConf) {
                        bestConf = conf
                        bestBox = floatArrayOf(output[0][0][i], output[0][1][i], output[0][2][i], output[0][3][i])
                    }
                }

                if (bestBox.isNotEmpty()) {
                    val x1 = bestBox[0] * overlayView.width / inputSize
                    val y1 = bestBox[1] * overlayView.height / inputSize
                    val x2 = bestBox[2] * overlayView.width / inputSize
                    val y2 = bestBox[3] * overlayView.height / inputSize
                    targetX = ((x1 + x2) / 2).toInt()
                    targetY = (y1 + (y2 - y1) * 0.3f).toInt()
                    AimAccessibilityService.setTarget(targetX, targetY)
                } else {
                    targetX = -1
                    targetY = -1
                    AimAccessibilityService.setTarget(-1, -1)
                }

                handler.post { overlayView.invalidate() }

                if (frameCount++ % 100 == 0) System.gc()
                Thread.sleep(33)
            }
        }.start()
    }

    private fun bitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val size = inputSize * inputSize * 3 * 4
        if (reusableByteBuffer == null || reusableByteBuffer?.capacity() != size) {
            reusableByteBuffer = ByteBuffer.allocateDirect(size)
            reusableByteBuffer?.order(ByteOrder.nativeOrder())
        }
        val buffer = reusableByteBuffer!!
        buffer.rewind()
        val pixels = IntArray(inputSize * inputSize)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        for (pixel in pixels) {
            buffer.putFloat((pixel shr 16 and 0xFF) / 255.0f)
            buffer.putFloat((pixel shr 8 and 0xFF) / 255.0f)
            buffer.putFloat((pixel and 0xFF) / 255.0f)
        }
        buffer.rewind()
        return buffer
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        virtualDisplay.release()
        mediaProjection.stop()
        interpreter.close()
        windowManager.removeView(overlayView)
        reusableBitmap?.recycle()
        reusableBitmap = null
        reusableByteBuffer = null
        stopSelf()   // إصلاح: ضمان إيقاف الخدمة
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "aim_assist_channel",
                "Aim Assist Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)

            val notification = Notification.Builder(this, "aim_assist_channel")
                .setContentTitle("Aim Assist")
                .setContentText("Auto Headshot Active")
                .setSmallIcon(android.R.drawable.sym_def_app_icon)   // إصلاح: أيقونة آمنة
                .build()
            startForeground(1, notification)
        }
        return START_STICKY
    }
}
