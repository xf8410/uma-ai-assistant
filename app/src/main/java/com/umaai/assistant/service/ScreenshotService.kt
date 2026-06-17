package com.umaai.assistant.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.umaai.assistant.MainActivity
import com.umaai.assistant.R
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * 屏幕截图服务
 * 负责截取赛马娘游戏画面，并进行OCR识别
 */
class ScreenshotService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var displayWidth = 1080
    private var displayHeight = 1920
    private var density = 320

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val ACTION_CAPTURE = "com.umaai.CAPTURE"

        fun start(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, ScreenshotService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }
            context.startService(intent)
        }

        fun triggerCapture(context: Context) {
            context.sendBroadcast(Intent(ACTION_CAPTURE))
        }
    }

    private val captureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_CAPTURE) {
                captureAndAnalyze()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(2, createNotification())
        registerReceiver(captureReceiver, IntentFilter(ACTION_CAPTURE),
            Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: return START_NOT_STICKY
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
            ?: return START_NOT_STICKY

        setupProjection(resultCode, resultData)
        return START_STICKY
    }

    private fun setupProjection(resultCode: Int, data: Intent) {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)
        displayWidth = metrics.widthPixels
        displayHeight = metrics.heightPixels
        density = metrics.densityDpi

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
    }

    private fun captureAndAnalyze() {
        if (mediaProjection == null) return

        val scale = 0.5f
        val w = (displayWidth * scale).roundToInt()
        val h = (displayHeight * scale).roundToInt()

        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "uma_capture", w, h, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        Handler(Looper.getMainLooper()).postDelayed({
            processImage(w, h)
        }, 300)
    }

    private fun processImage(w: Int, h: Int) {
        val image = imageReader?.acquireLatestImage() ?: return

        try {
            val bitmap = imageToBitmap(image, w, h)
            if (bitmap != null) {
                performOcr(bitmap)
            }
        } finally {
            image.close()
            virtualDisplay?.release()
            imageReader?.close()
        }
    }

    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap? {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * width

        val bitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride, height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
    }

    private fun performOcr(bitmap: Bitmap) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        recognizer.process(inputImage)
            .addOnSuccessListener { text ->
                val stats = parseGameStats(text)
                if (stats.isValid()) {
                    // 通知悬浮窗更新建议
                    val intent = Intent("com.umaai.AI_ADVICE").apply {
                        putExtra("speed", stats.speed)
                        putExtra("stamina", stats.stamina)
                        putExtra("power", stats.power)
                        putExtra("guts", stats.guts)
                        putExtra("wit", stats.wit)
                        putExtra("vital", stats.vital)
                        putExtra("motivation", stats.motivation)
                        putExtra("turn", stats.turn)
                        putExtra("skillPt", stats.skillPt)
                        putExtra("isFat", stats.isFat)
                    }
                    sendBroadcast(intent)
                }
            }
            .addOnFailureListener { /* OCR失败静默 */ }
    }

    /**
     * 解析赛马娘游戏画面中的数值
     */
    private fun parseGameStats(text: Text): GameStats {
        val result = GameStats()
        val allText = text.text

        for (block in text.textBlocks) {
            for (line in block.lines) {
                val txt = line.text.trim()
                val num = Regex("\\d+").find(txt)?.value?.toIntOrNull()

                when {
                    // 速度
                    (txt.contains("スピード") || txt.contains("速度") || txt.contains("Speed")) && num != null -> {
                        if (num in 50..2100) result.speed = num
                    }
                    // 耐力
                    (txt.contains("スタミナ") || txt.contains("耐力") || txt.contains("Stamina")) && num != null -> {
                        if (num in 50..2100) result.stamina = num
                    }
                    // 力量
                    (txt.contains("パワー") || txt.contains("力量") || txt.contains("Power")) && num != null -> {
                        if (num in 50..2100) result.power = num
                    }
                    // 根性
                    (txt.contains("根性") || txt.contains("Guts")) && num != null -> {
                        if (num in 50..2100) result.guts = num
                    }
                    // 智力
                    (txt.contains("賢さ") || txt.contains("智力") || txt.contains("Wiz") || txt.contains("Int")) && num != null -> {
                        if (num in 50..2100) result.wit = num
                    }
                    // 体力（0-100范围）
                    (txt.contains("体力") || txt.contains("やる気") || txt.contains("HP")) && num != null -> {
                        if (num in 0..100) result.vital = num
                    }
                    // 技能点（通常较大）
                    (txt.contains("スキル") || txt.contains("技能") || txt.contains("Pt")) && num != null -> {
                        if (num > 100) result.skillPt = num
                    }
                    // 回合数
                    (txt.contains("TURN") || txt.contains("Turn") || txt.contains("ターン") || txt.contains("回合")) && num != null -> {
                        if (num in 1..79) result.turn = num
                    }
                    // 吃胖检测
                    txt.contains("太った") || txt.contains("食べ過ぎ") || txt.contains("体重") -> {
                        result.isFat = true
                    }
                }
            }
        }

        // 从所有数字推断（备用方案）
        if (!result.isValid()) {
            val allNumbers = Regex("\\d+").findAll(allText)
                .map { it.value.toIntOrNull() ?: 0 }
                .filter { it in 50..2100 }
                .toList()
            
            if (allNumbers.size >= 5) {
                result.speed = allNumbers.getOrElse(0) { 0 }
                result.stamina = allNumbers.getOrElse(1) { 0 }
                result.power = allNumbers.getOrElse(2) { 0 }
                result.guts = allNumbers.getOrElse(3) { 0 }
                result.wit = allNumbers.getOrElse(4) { 0 }
            }
        }

        return result
    }

    data class GameStats(
        var speed: Int = 0,
        var stamina: Int = 0,
        var power: Int = 0,
        var guts: Int = 0,
        var wit: Int = 0,
        var vital: Int = 0,
        var motivation: Int = 2,
        var turn: Int = 0,
        var skillPt: Int = 0,
        var isFat: Boolean = false
    ) {
        fun isValid(): Boolean {
            return speed > 0 && stamina > 0 && power > 0 && guts > 0 && wit > 0
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(captureReceiver) } catch (_: Exception) {}
        scope.cancel()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        recognizer.close()
    }

    // ========== 通知 ==========

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "uma_capture", "截图OCR",
                NotificationManager.IMPORTANCE_MIN
            ).apply { setShowBadge(false) }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "uma_capture")
            .setContentTitle("赛马娘AI - 截图服务")
            .setContentText("自动截图OCR识别中")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pi).setOngoing(true).build()
    }
}
