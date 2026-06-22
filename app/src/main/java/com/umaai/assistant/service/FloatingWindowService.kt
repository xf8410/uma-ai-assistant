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

class FloatingWindowService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: android.view.View
    private lateinit var tvRecommend: TextView

    private var localServer: LocalServer? = null
    private var dataReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()

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

        // 启动 HTTP 服务器
        localServer = LocalServer(this)
        localServer?.startServer()

        // 注册广播接收器
        dataReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val data = intent.getStringExtra("data")
                if (data != null) {
                    tvRecommend.text = data
                }
            }
        }
        registerReceiver(dataReceiver, IntentFilter("UPDATE_FLOATING"))

        tvRecommend.text = "等待数据..."
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        localServer?.stopServer()
        dataReceiver?.let { unregisterReceiver(it) }
        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
        super.onDestroy()
    }
}