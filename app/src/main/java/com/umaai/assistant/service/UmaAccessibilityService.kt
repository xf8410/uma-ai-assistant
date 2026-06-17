package com.umaai.assistant.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.*

/**
 * 无障碍服务
 * 检测赛马娘游戏是否在前台运行
 * 游戏在前台时触发截图OCR
 */
class UmaAccessibilityService : AccessibilityService() {

    companion object {
        // 赛马娘包名列表
        val TARGET_PACKAGES = setOf(
            "jp.co.cygames.umamusume",           // 日服
            "com.komoe.umamusume",               // 繁中服
            "com.bilibili.umamusu",              // 简中服
            "jp.co.cygames.umamusume_pre"        // 预发布
        )
        var isGameForeground = false
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var screenshotJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 启动悬浮窗
        startService(Intent(this, FloatingWindowService::class.java))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val packageName = event.packageName?.toString() ?: return
                val wasForeground = isGameForeground
                isGameForeground = TARGET_PACKAGES.any { packageName.contains(it) }

                if (isGameForeground && !wasForeground) {
                    // 游戏进入前台，开始定时截图
                    startPeriodicScreenshot()
                    FloatingWindowService.showGameDetected(this)
                } else if (!isGameForeground && wasForeground) {
                    // 游戏退出前台
                    stopPeriodicScreenshot()
                    FloatingWindowService.hideAdvice(this)
                }
            }
        }
    }

    override fun onInterrupt() {}

    private fun startPeriodicScreenshot() {
        screenshotJob?.cancel()
        screenshotJob = scope.launch {
            while (isActive) {
                // 每3秒截图一次
                delay(3000)
                if (isGameForeground) {
                    ScreenshotService.triggerCapture(this@UmaAccessibilityService)
                }
            }
        }
    }

    private fun stopPeriodicScreenshot() {
        screenshotJob?.cancel()
        screenshotJob = null
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
