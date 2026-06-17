package com.umaai.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.umaai.assistant.MainActivity
import com.umaai.assistant.R

/**
 * URA风格仪表盘悬浮窗服务
 * 
 * Phase A: 截图OCR → 自动识别五维 → 填入仪表盘 → 显示AI建议
 * Phase C: 手动输入 + GUI显示
 */
class FloatingService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var params: WindowManager.LayoutParams? = null

    // 当前游戏状态
    private var gameState = GameState()

    data class GameState(
        var speed: Int = 0, var stamina: Int = 0, var power: Int = 0,
        var guts: Int = 0, var wit: Int = 0, var vital: Int = 100,
        var motivation: Int = 3, var skillPt: Int = 120, var turn: Int = 1,
        var isFat: Boolean = false
    )

    // UI引用
    private var tvSpeed: TextView? = null
    private var tvStamina: TextView? = null
    private var tvPower: TextView? = null
    private var tvGuts: TextView? = null
    private var tvWit: TextView? = null
    private var tvVital: TextView? = null
    private var tvMotivation: TextView? = null
    private var tvTurn: TextView? = null
    private var tvTotal: TextView? = null
    private var tvAdvice: TextView? = null
    private var tvStatus: TextView? = null
    private var layoutExpanded: View? = null
    private var layoutCollapsed: View? = null
    private var isMinimized = false

    // 拖动
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    companion object {
        const val ACTION_UPDATE_STATS = "com.umaai.UPDATE_STATS"
        const val ACTION_OCR_RESULT = "com.umaai.OCR_RESULT"
        const val ACTION_TOGGLE = "com.umaai.TOGGLE"
    }

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_UPDATE_STATS -> {
                    // 从OCR结果更新状态
                    gameState.speed = intent.getIntExtra("speed", gameState.speed)
                    gameState.stamina = intent.getIntExtra("stamina", gameState.stamina)
                    gameState.power = intent.getIntExtra("power", gameState.power)
                    gameState.guts = intent.getIntExtra("guts", gameState.guts)
                    gameState.wit = intent.getIntExtra("wit", gameState.wit)
                    gameState.vital = intent.getIntExtra("vital", gameState.vital)
                    gameState.motivation = intent.getIntExtra("motivation", gameState.motivation)
                    gameState.skillPt = intent.getIntExtra("skillPt", gameState.skillPt)
                    gameState.isFat = intent.getBooleanExtra("isFat", false)
                    updateUI()
                }
                ACTION_OCR_RESULT -> {
                    // OCR结果：更新五维+回合数（参考PC工具的显示格式）
                    val turn = intent.getIntExtra("turn", 0)
                    if (turn > 0) gameState.turn = turn
                    gameState.speed = intent.getIntExtra("speed", gameState.speed)
                    gameState.stamina = intent.getIntExtra("stamina", gameState.stamina)
                    gameState.power = intent.getIntExtra("power", gameState.power)
                    gameState.guts = intent.getIntExtra("guts", gameState.guts)
                    gameState.wit = intent.getIntExtra("wit", gameState.wit)
                    gameState.vital = intent.getIntExtra("vital", gameState.vital)
                    gameState.skillPt = intent.getIntExtra("skillPt", gameState.skillPt)
                    
                    val confidence = intent.getFloatExtra("confidence", 0f)
                    tvStatus?.text = "OCR识别完成 (置信度:${(confidence * 100).toInt()}%) 截图已删除"
                    tvStatus?.setTextColor(0xFF44FF88.toInt())
                    updateUI()
                }
                "com.umaai.OCR_ERROR" -> {
                    val error = intent.getStringExtra("error") ?: "OCR失败"
                    tvStatus?.text = error
                    tvStatus?.setTextColor(0xFFFF4444.toInt())
                }
                ACTION_TOGGLE -> {
                    if (isMinimized) expand() else minimize()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(1, createNotification())

        val filter = IntentFilter().apply {
            addAction(ACTION_UPDATE_STATS)
            addAction(ACTION_OCR_RESULT)
            addAction(ACTION_TOGGLE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(updateReceiver, filter)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (floatingView == null) {
            createFloatingWindow()
        }
        return START_STICKY
    }

    private fun createFloatingWindow() {
        val inflater = LayoutInflater.from(this)
        floatingView = inflater.inflate(R.layout.floating_dashboard, null)

        // 绑定UI
        layoutExpanded = floatingView?.findViewById(R.id.layoutExpanded)
        layoutCollapsed = floatingView?.findViewById(R.id.layoutCollapsed)

        tvSpeed = floatingView?.findViewById(R.id.tvSpeed)
        tvStamina = floatingView?.findViewById(R.id.tvStamina)
        tvPower = floatingView?.findViewById(R.id.tvPower)
        tvGuts = floatingView?.findViewById(R.id.tvGuts)
        tvWit = floatingView?.findViewById(R.id.tvWit)
        tvVital = floatingView?.findViewById(R.id.tvVital)
        tvMotivation = floatingView?.findViewById(R.id.tvMotivation)
        tvTurn = floatingView?.findViewById(R.id.tvTurn)
        tvTotal = floatingView?.findViewById(R.id.tvTotal)
        tvAdvice = floatingView?.findViewById(R.id.tvAdvice)
        tvStatus = floatingView?.findViewById(R.id.tvStatus)

        // 训练卡片按钮
        val trainButtons = mapOf(
            R.id.btnSpeed to "speed",
            R.id.btnStamina to "stamina",
            R.id.btnPower to "power",
            R.id.btnGuts to "guts",
            R.id.btnWit to "wit",
        )
        for ((btnId, stat) in trainButtons) {
            floatingView?.findViewById<Button>(btnId)?.setOnClickListener {
                onTrain(stat)
            }
        }

        // 其他动作
        floatingView?.findViewById<Button>(R.id.btnRest)?.setOnClickListener { onAction("rest") }
        floatingView?.findViewById<Button>(R.id.btnOuting)?.setOnClickListener { onAction("outing") }
        floatingView?.findViewById<Button>(R.id.btnRace)?.setOnClickListener { onAction("race") }
        floatingView?.findViewById<Button>(R.id.btnClinic)?.setOnClickListener { onAction("clinic") }

        // 截图OCR按钮
        floatingView?.findViewById<ImageButton>(R.id.btnCamera)?.setOnClickListener {
            triggerScreenshotOCR()
        }

        // 数值+/-按钮
        setupAdjustButtons()

        // 关闭/最小化
        floatingView?.findViewById<ImageButton>(R.id.btnClose)?.setOnClickListener { stopSelf() }
        floatingView?.findViewById<ImageButton>(R.id.btnMinimize)?.setOnClickListener { minimize() }
        layoutCollapsed?.setOnClickListener { expand() }

        // 拖动
        setupDrag()

        // 窗口参数
        params = WindowManager.LayoutParams(
            dpToPx(400), dpToPx(580),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20; y = 100
        }

        windowManager.addView(floatingView, params)
        updateUI()
    }

    private fun setupAdjustButtons() {
        val adjustMap = mapOf(
            R.id.btnSpeedMinus to "speed" to -1, R.id.btnSpeedPlus to "speed" to 1,
            R.id.btnStaminaMinus to "stamina" to -1, R.id.btnStaminaPlus to "stamina" to 1,
            R.id.btnPowerMinus to "power" to -1, R.id.btnPowerPlus to "power" to 1,
            R.id.btnGutsMinus to "guts" to -1, R.id.btnGutsPlus to "guts" to 1,
            R.id.btnWitMinus to "wit" to -1, R.id.btnWitPlus to "wit" to 1,
        )
        for ((btnId, pair) in adjustMap) {
            val (stat, delta) = pair
            floatingView?.findViewById<Button>(btnId)?.setOnClickListener {
                when (stat) {
                    "speed" -> gameState.speed = (gameState.speed + delta).coerceAtLeast(0)
                    "stamina" -> gameState.stamina = (gameState.stamina + delta).coerceAtLeast(0)
                    "power" -> gameState.power = (gameState.power + delta).coerceAtLeast(0)
                    "guts" -> gameState.guts = (gameState.guts + delta).coerceAtLeast(0)
                    "wit" -> gameState.wit = (gameState.wit + delta).coerceAtLeast(0)
                }
                updateUI()
            }
        }
    }

    private fun onTrain(stat: String) {
        gameState.turn++
        when (stat) {
            "speed" -> if (!gameState.isFat) { gameState.speed += 15; gameState.vital -= 21 }
            "stamina" -> { gameState.stamina += 15; gameState.vital -= 21 }
            "power" -> { gameState.power += 15; gameState.vital -= 21 }
            "guts" -> { gameState.guts += 15; gameState.vital -= 21 }
            "wit" -> { gameState.wit += 12; gameState.vital -= 10 }
        }
        gameState.vital = gameState.vital.coerceIn(0, 100)
        updateUI()
    }

    private fun onAction(action: String) {
        gameState.turn++
        when (action) {
            "rest" -> gameState.vital = (gameState.vital + 50).coerceAtMost(100)
            "outing" -> {
                gameState.motivation = (gameState.motivation + 1).coerceAtMost(5)
                gameState.vital = (gameState.vital + 10).coerceAtMost(100)
            }
            "clinic" -> {
                gameState.isFat = false
                if (gameState.motivation == 1) gameState.motivation = 2
            }
            "race" -> {
                gameState.skillPt += 35
                // 比赛不能消除吃胖，只有保健室可以
            }
        }
        updateUI()
    }

    private fun triggerScreenshotOCR() {
        // 最小化悬浮窗避免遮挡
        minimize()
        tvStatus?.text = "正在截图识别..."

        // 2秒后触发截图
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            sendBroadcast(Intent(ScreenshotService.ACTION_CAPTURE))
            // 恢复展开
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                expand()
            }, 500)
        }, 1500)
    }

    private fun updateUI() {
        tvSpeed?.text = gameState.speed.toString()
        tvStamina?.text = gameState.stamina.toString()
        tvPower?.text = gameState.power.toString()
        tvGuts?.text = gameState.guts.toString()
        tvWit?.text = gameState.wit.toString()
        tvVital?.text = "${gameState.vital}/100"
        tvTurn?.text = "T${gameState.turn}/72"

        val moodNames = arrayOf("", "绝不调", "不调", "普通", "好调", "绝好调")
        val moodColors = intArrayOf(0, 0xFFFF4444.toInt(), 0xFFFF8844.toInt(), 0xFFAAAAAA.toInt(), 0xFF44FF88.toInt(), 0xFFFFDD44.toInt())
        tvMotivation?.text = moodNames[gameState.motivation.coerceIn(1, 5)]
        tvMotivation?.setTextColor(moodColors[gameState.motivation.coerceIn(1, 5)])

        val total = gameState.speed + gameState.stamina + gameState.power + gameState.guts + gameState.wit
        tvTotal?.text = "总: ${total} | Pt: ${gameState.skillPt}"

        // 体力颜色
        tvVital?.setTextColor(
            when {
                gameState.vital < 20 -> 0xFFFF4444.toInt()
                gameState.vital < 40 -> 0xFFFF8844.toInt()
                else -> 0xFFFFFF00.toInt()
            }
        )

        // AI建议
        tvAdvice?.text = generateAdvice()

        // 吃胖状态
        if (gameState.isFat) {
            tvStatus?.text = "【吃胖中】速度训练无效！去保健室！"
            tvStatus?.setTextColor(0xFFFF4444.toInt())
        } else {
            tvStatus?.text = ""
        }
    }

    private fun generateAdvice(): String {
        val adv = mutableListOf<String>()
        val avg = (gameState.speed + gameState.stamina + gameState.power + gameState.guts + gameState.wit) / 5.0

        if (gameState.isFat) adv.add("【紧急】去保健室消除吃胖！")
        if (gameState.vital < 20) adv.add("【紧急】体力危险！必须休息！")
        else if (gameState.vital < 30) adv.add("【警告】体力低，建议休息")
        if (gameState.motivation <= 1) adv.add("【紧急】绝不调！优先外出！")
        else if (gameState.motivation == 2) adv.add("【建议】不调，考虑外出")

        if (gameState.vital >= 30 && gameState.motivation >= 3 && !gameState.isFat) {
            val stats = listOf(
                "速度" to gameState.speed, "耐力" to gameState.stamina,
                "力量" to gameState.power, "根性" to gameState.guts, "智力" to gameState.wit
            )
            val minStat = stats.minByOrNull { it.second }
            if (minStat != null && minStat.second < avg * 0.7) {
                adv.add("⭐训练${minStat.first}（补弱）")
            } else {
                val maxStat = stats.maxByOrNull { it.second }
                if (maxStat != null) adv.add("⭐训练${maxStat.first}（优势）")
            }
        }

        return adv.joinToString("\n")
    }

    private fun setupDrag() {
        val touchListener = View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params?.x ?: 0
                    initialY = params?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    params?.x = initialX + (event.rawX - initialTouchX).toInt()
                    params?.y = initialY + (event.rawY - initialTouchY).toInt()
                    floatingView?.let { windowManager.updateViewLayout(it, params) }
                }
            }
            true
        }
        layoutExpanded?.setOnTouchListener(touchListener)
        layoutCollapsed?.setOnTouchListener(touchListener)
    }

    private fun minimize() {
        layoutExpanded?.visibility = View.GONE
        layoutCollapsed?.visibility = View.VISIBLE
        params?.width = dpToPx(50)
        params?.height = dpToPx(50)
        floatingView?.let { windowManager.updateViewLayout(it, params) }
        isMinimized = true
    }

    private fun expand() {
        layoutExpanded?.visibility = View.VISIBLE
        layoutCollapsed?.visibility = View.GONE
        params?.width = dpToPx(400)
        params?.height = dpToPx(580)
        floatingView?.let { windowManager.updateViewLayout(it, params) }
        isMinimized = false
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(updateReceiver) } catch (_: Exception) {}
        if (floatingView != null) {
            windowManager.removeView(floatingView)
            floatingView = null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("uma_dash", "UMA仪表盘", NotificationManager.IMPORTANCE_LOW)
            channel.setShowBadge(false)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "uma_dash")
            .setContentTitle("UmaAI仪表盘运行中")
            .setContentText("点击返回主界面")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
