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
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.umaai.assistant.MainActivity
import com.umaai.assistant.R
import com.umaai.assistant.ai.AiDecisionEngine

/**
 * 悬浮窗服务
 * 显示AI决策建议和当前状态
 */
class FloatingWindowService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var expandedView: View? = null
    private var collapsedView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var isMinimized = false

    // 拖动
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    private val aiEngine = AiDecisionEngine()

    // 接收AI建议广播
    private val adviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.umaai.AI_ADVICE") {
                val stats = AiStats(
                    speed = intent.getIntExtra("speed", 0),
                    stamina = intent.getIntExtra("stamina", 0),
                    power = intent.getIntExtra("power", 0),
                    guts = intent.getIntExtra("guts", 0),
                    wit = intent.getIntExtra("wit", 0),
                    vital = intent.getIntExtra("vital", 0),
                    motivation = intent.getIntExtra("motivation", 2),
                    turn = intent.getIntExtra("turn", 0),
                    skillPt = intent.getIntExtra("skillPt", 0),
                    isFat = intent.getBooleanExtra("isFat", false)
                )
                updateAdvice(stats)
            }
        }
    }

    data class AiStats(
        val speed: Int, val stamina: Int, val power: Int,
        val guts: Int, val wit: Int, val vital: Int,
        val motivation: Int, val turn: Int, val skillPt: Int,
        val isFat: Boolean
    )

    companion object {
        fun showGameDetected(context: Context) {
            context.sendBroadcast(Intent("com.umaai.SHOW_DETECTED"))
        }
        fun hideAdvice(context: Context) {
            context.sendBroadcast(Intent("com.umaai.HIDE_ADVICE"))
        }
    }

    private val uiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.umaai.SHOW_DETECTED" -> showDetected()
                "com.umaai.HIDE_ADVICE" -> hideAdvice()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(1, createNotification())

        registerReceiver(adviceReceiver, IntentFilter("com.umaai.AI_ADVICE"),
            Context.RECEIVER_NOT_EXPORTED)
        registerReceiver(uiReceiver, IntentFilter().apply {
            addAction("com.umaai.SHOW_DETECTED")
            addAction("com.umaai.HIDE_ADVICE")
        }, Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (floatingView == null) {
            createFloatingWindow()
        }
        return START_STICKY
    }

    private fun createFloatingWindow() {
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_window, null)

        expandedView = floatingView?.findViewById(R.id.layoutExpanded)
        collapsedView = floatingView?.findViewById(R.id.layoutCollapsed)

        // 关闭
        floatingView?.findViewById<ImageButton>(R.id.btnClose)?.setOnClickListener {
            stopSelf()
        }

        // 最小化
        floatingView?.findViewById<ImageButton>(R.id.btnMinimize)?.setOnClickListener {
            minimize()
        }

        // 展开
        collapsedView?.setOnClickListener {
            expand()
        }

        // 拖动
        expandedView?.setOnTouchListener { _, event ->
            handleTouch(event)
            false
        }
        collapsedView?.setOnTouchListener { _, event ->
            handleTouch(event)
            false
        }

        // 窗口参数
        params = WindowManager.LayoutParams(
            dpToPx(340), dpToPx(520),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50; y = 100
        }

        windowManager.addView(floatingView, params)
    }

    private fun handleTouch(event: MotionEvent): Boolean {
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
        return true
    }

    private fun minimize() {
        expandedView?.visibility = View.GONE
        collapsedView?.visibility = View.VISIBLE
        params?.width = dpToPx(50)
        params?.height = dpToPx(50)
        floatingView?.let { windowManager.updateViewLayout(it, params) }
        isMinimized = true
    }

    private fun expand() {
        expandedView?.visibility = View.VISIBLE
        collapsedView?.visibility = View.GONE
        params?.width = dpToPx(340)
        params?.height = dpToPx(520)
        floatingView?.let { windowManager.updateViewLayout(it, params) }
        isMinimized = false
    }

    private fun showDetected() {
        expand()
        floatingView?.findViewById<TextView>(R.id.tvStatus)?.text = "游戏已检测，截图识别中..."
    }

    private fun hideAdvice() {
        floatingView?.findViewById<TextView>(R.id.tvStatus)?.text = "等待游戏..."
        floatingView?.findViewById<TextView>(R.id.tvAdvice)?.text = ""
    }

    private fun updateAdvice(stats: AiStats) {
        if (!isMinimized) {
            val advice = aiEngine.getAdvice(stats)

            val statusText = buildString {
                appendLine("T${stats.turn}  ${stats.skillPt}pt")
                append("速${stats.speed} 耐${stats.stamina} 力${stats.power}")
                appendLine(" 根${stats.guts} 智${stats.wit}")
                appendLine("体力${stats.vital}  ${getMoodName(stats.motivation)}")
                if (stats.isFat) appendLine("【吃胖中！】")
            }

            floatingView?.findViewById<TextView>(R.id.tvStatus)?.text = statusText
            floatingView?.findViewById<TextView>(R.id.tvAdvice)?.text = advice
        }
    }

    private fun getMoodName(m: Int): String = when (m) {
        1 -> "绝不调"
        2 -> "不调"
        3 -> "普通"
        4 -> "好调"
        5 -> "绝好调"
        else -> "普通"
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(adviceReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(uiReceiver) } catch (_: Exception) {}
        if (floatingView != null) {
            windowManager.removeView(floatingView)
            floatingView = null
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    // ========== 通知 ==========

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "uma_float", "AI悬浮窗",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "uma_float")
            .setContentTitle("赛马娘AI助手")
            .setContentText("悬浮窗运行中")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pi).setOngoing(true).build()
    }
}
