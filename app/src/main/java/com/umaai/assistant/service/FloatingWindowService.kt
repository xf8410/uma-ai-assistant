package com.umaai.assistant.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.TextView

/**
 * 悬浮窗服务
 * 负责显示悬浮窗，并接收 LocalServer 转发的游戏数据
 */
class FloatingWindowService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: android.view.View
    private lateinit var tvRecommend: TextView

    // 本地 HTTP 服务器（接收 Frida 数据）
    private var localServer: LocalServer? = null

    // 广播接收器（接收 LocalServer 发来的数据）
    private var dataReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()

        // 1. 创建悬浮窗
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_window, null)
        tvRecommend = floatingView.findViewById(R.id.tv_recommend)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 100
        params.y = 100

        windowManager.addView(floatingView, params)

        // 2. 启动本地 HTTP 服务器（监听 4693 端口，接收 Frida 数据）
        localServer = LocalServer(this)
        localServer?.startServer()

        // 3. 注册广播接收器，接收 LocalServer 转发来的数据
        dataReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val data = intent.getStringExtra("data")
                if (data != null) {
                    // 将收到的数据显示在悬浮窗上
                    tvRecommend.text = data
                }
            }
        }
        registerReceiver(dataReceiver, IntentFilter("UPDATE_FLOATING"))

        // 初始提示
        tvRecommend.text = "等待数据..."
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // 停止 HTTP 服务器
        localServer?.stopServer()

        // 注销广播接收器
        dataReceiver?.let { unregisterReceiver(it) }

        // 移除悬浮窗
        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }

        super.onDestroy()
    }
}