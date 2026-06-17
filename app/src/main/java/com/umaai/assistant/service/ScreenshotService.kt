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
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * 屏幕截图 + OCR 识别服务
 * 
 * 流程：
 * 1. 截取游戏画面
 * 2. 临时保存到缓存文件（OCR用）
 * 3. ML Kit OCR识别五维+回合数
 * 4. 发送广播通知悬浮窗更新
 * 5. 立即删除截图文件（不存缓存）
 */
class ScreenshotService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var displayWidth = 1080
    private var displayHeight = 1920
    private var density = 320

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    companion object {
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        const val ACTION_CAPTURE = "com.umaai.CAPTURE"
        // 截图缓存目录（OCR后立即清理）
        const val SCREENSHOT_DIR = "uma_screenshots"

        fun start(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, ScreenshotService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }
            context.startService(intent)
        }

        /** 触发截图（从AccessibilityService调用） */
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(captureReceiver, IntentFilter(ACTION_CAPTURE),
                Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(captureReceiver, IntentFilter(ACTION_CAPTURE))
        }
        
        // 启动时清理旧截图
        clearScreenshotCache()
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
        if (mediaProjection == null) {
            sendOcrError("屏幕录制未授权")
            return
        }

        val scale = 0.5f
        val w = (displayWidth * scale).roundToInt()
        val h = (displayHeight * scale).roundToInt()

        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "uma_capture", w, h, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        // 延迟等待渲染
        Handler(Looper.getMainLooper()).postDelayed({
            processImage(w, h)
        }, 400)
    }

    private fun processImage(w: Int, h: Int) {
        val image = imageReader?.acquireLatestImage()
        if (image == null) {
            sendOcrError("截图失败")
            cleanup()
            return
        }

        try {
            val bitmap = imageToBitmap(image, w, h)
            if (bitmap != null) {
                // 临时保存到缓存（OCR需要文件路径）
                val tempFile = saveTempScreenshot(bitmap)
                if (tempFile != null) {
                    performOcr(tempFile, bitmap)
                    // OCR完成后立即删除
                    tempFile.delete()
                } else {
                    // 直接从bitmap OCR
                    performOcrFromBitmap(bitmap)
                }
            } else {
                sendOcrError("图像处理失败")
            }
        } finally {
            image.close()
            cleanup()
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

    /**
     * 临时保存截图到缓存目录（OCR后立即删除）
     */
    private fun saveTempScreenshot(bitmap: Bitmap): File? {
        return try {
            val dir = File(cacheDir, SCREENSHOT_DIR)
            dir.mkdirs()
            val file = File(dir, "temp_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
            file
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 从缓存文件OCR（然后删除文件）
     */
    private fun performOcr(tempFile: File, bitmap: Bitmap) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        
        recognizer.process(inputImage)
            .addOnSuccessListener { text ->
                // 立即删除临时文件
                tempFile.delete()
                clearScreenshotCache()
                
                val result = parseGameScreen(text)
                sendOcrResult(result)
            }
            .addOnFailureListener { e ->
                tempFile.delete()
                sendOcrError("OCR失败: ${e.message}")
            }
    }

    /**
     * 直接从Bitmap OCR（无文件）
     */
    private fun performOcrFromBitmap(bitmap: Bitmap) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        
        recognizer.process(inputImage)
            .addOnSuccessListener { text ->
                clearScreenshotCache()
                val result = parseGameScreen(text)
                sendOcrResult(result)
            }
            .addOnFailureListener { e ->
                sendOcrError("OCR失败: ${e.message}")
            }
    }

    /**
     * 解析赛马娘游戏画面
     * 参考PC端工具的显示格式，提取：
     * - 回合数（TURN/ターン）
     * - 五维属性
     * - 体力
     * - 技能点
     */
    data class OcrResult(
        val turn: Int = 0,
        val speed: Int = 0,
        val stamina: Int = 0,
        val power: Int = 0,
        val guts: Int = 0,
        val wit: Int = 0,
        val vital: Int = 0,
        val skillPt: Int = 0,
        val motivation: Int = 3,
        val isFat: Boolean = false,
        val rawConfidence: Float = 0f
    )

    private fun parseGameScreen(text: com.google.mlkit.vision.text.Text): OcrResult {
        val result = OcrResult()
        val allText = text.text
        
        // 1. 识别回合数（左上角通常显示 TURN 10 或 ターン 10）
        val turnPattern1 = Regex("TURN\\s*(\\d+)", RegexOption.IGNORE_CASE)
        val turnPattern2 = Regex("ターン\\s*(\\d+)")
        val turnPattern3 = Regex("回合\\s*(\\d+)")
        val turnPattern4 = Regex("(\\d+)\\s*/\\s*78")  // 10/78格式
        
        val turnMatch = turnPattern1.find(allText) 
            ?: turnPattern2.find(allText)
            ?: turnPattern3.find(allText)
            ?: turnPattern4.find(allText)
        
        val turn = turnMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
        
        // 2. 识别五维属性
        var speed = 0; var stamina = 0; var power = 0; var guts = 0; var wit = 0
        var vital = 0; var skillPt = 0
        var foundStats = 0
        
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val txt = line.text.trim()
                val nums = Regex("\\d+").findAll(txt).map { it.value.toIntOrNull() ?: 0 }.toList()
                
                // 速度
                if ((txt.contains("速度") || txt.contains("スピード") || txt.contains("Speed")) && nums.isNotEmpty()) {
                    if (nums[0] in 50..2100) { speed = nums[0]; foundStats++ }
                }
                // 耐力
                else if ((txt.contains("耐力") || txt.contains("スタミナ") || txt.contains("Stamina")) && nums.isNotEmpty()) {
                    if (nums[0] in 50..2100) { stamina = nums[0]; foundStats++ }
                }
                // 力量
                else if ((txt.contains("力量") || txt.contains("パワー") || txt.contains("Power")) && nums.isNotEmpty()) {
                    if (nums[0] in 50..2100) { power = nums[0]; foundStats++ }
                }
                // 根性
                else if ((txt.contains("根性") || txt.contains("Guts")) && nums.isNotEmpty()) {
                    if (nums[0] in 50..2100) { guts = nums[0]; foundStats++ }
                }
                // 智力
                else if ((txt.contains("智力") || txt.contains("賢さ") || txt.contains("Wiz") || txt.contains("Int")) && nums.isNotEmpty()) {
                    if (nums[0] in 50..2100) { wit = nums[0]; foundStats++ }
                }
                // 体力（0-100范围）
                else if ((txt.contains("体力") || txt.contains("やる気") || txt.contains("HP")) && nums.isNotEmpty()) {
                    if (nums[0] in 0..100) vital = nums[0]
                }
                // 技能点
                else if ((txt.contains("スキル") || txt.contains("技能") || txt.contains("Pt")) && nums.isNotEmpty()) {
                    if (nums[0] > 100) skillPt = nums[0]
                }
                // 吃胖检测
                else if (txt.contains("太った") || txt.contains("食べ過ぎ") || txt.contains("体重")) {
                    // isFat = true
                }
            }
        }
        
        // 3. 如果从标签没匹配够，尝试纯数字推断
        if (foundStats < 3) {
            val allNums = Regex("\\d+")
                .findAll(allText)
                .map { it.value.toIntOrNull() ?: 0 }
                .filter { it in 50..2100 }
                .take(5)
                .toList()
            
            if (allNums.size >= 5) {
                speed = allNums[0]; stamina = allNums[1]; power = allNums[2]
                guts = allNums[3]; wit = allNums[4]
            }
        }
        
        return OcrResult(
            turn = turn,
            speed = speed, stamina = stamina, power = power, guts = guts, wit = wit,
            vital = vital, skillPt = skillPt,
            rawConfidence = foundStats / 5f
        )
    }

    private fun sendOcrResult(result: OcrResult) {
        val intent = Intent("com.umaai.OCR_RESULT").apply {
            putExtra("turn", result.turn)
            putExtra("speed", result.speed)
            putExtra("stamina", result.stamina)
            putExtra("power", result.power)
            putExtra("guts", result.guts)
            putExtra("wit", result.wit)
            putExtra("vital", result.vital)
            putExtra("skillPt", result.skillPt)
            putExtra("motivation", result.motivation)
            putExtra("isFat", result.isFat)
            putExtra("confidence", result.rawConfidence)
        }
        sendBroadcast(intent)
    }

    private fun sendOcrError(message: String) {
        val intent = Intent("com.umaai.OCR_ERROR").apply {
            putExtra("error", message)
        }
        sendBroadcast(intent)
    }

    /**
     * 清理所有截图缓存（识别完立即调用）
     */
    private fun clearScreenshotCache() {
        try {
            val dir = File(cacheDir, SCREENSHOT_DIR)
            if (dir.exists()) {
                dir.listFiles()?.forEach { it.delete() }
            }
        } catch (_: Exception) {}
    }

    private fun cleanup() {
        virtualDisplay?.release()
        imageReader?.close()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(captureReceiver) } catch (_: Exception) {}
        clearScreenshotCache()
        cleanup()
        mediaProjection?.stop()
        recognizer.close()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("uma_capture", "截图OCR",
                NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "uma_capture")
            .setContentTitle("UmaAI - 截图OCR就绪")
            .setContentText("点击悬浮窗的相机按钮截图识别")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pi).setOngoing(true).build()
    }
}
