package com.umaai.assistant

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.umaai.assistant.service.FloatingService
import com.umaai.assistant.service.FloatingWindowService
import com.umaai.assistant.service.ScreenshotService

/**
 * 赛马娘AI助手 - 主界面
 * 负责权限请求和服务启动
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val REQUEST_OVERLAY = 1001
    }

    private val screenshotLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ScreenshotService.start(this, result.resultCode, result.data!!)
            Toast.makeText(this, "屏幕录制已授权，可以开始截图OCR", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 悬浮窗按钮
        findViewById<MaterialButton>(R.id.btnOverlay).setOnClickListener {
            checkOverlayPermission()
        }

        // 截图授权按钮
        findViewById<MaterialButton>(R.id.btnScreenshot).setOnClickListener {
            requestScreenshotPermission()
        }

        // 无障碍服务按钮
        findViewById<MaterialButton>(R.id.btnAccessibility).setOnClickListener {
            requestAccessibilityService()
        }

        // 启动悬浮窗（URA仪表盘）
        findViewById<MaterialButton>(R.id.btnStart).setOnClickListener {
            startFloatingWindow()
        }

        // 停止
        findViewById<MaterialButton>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, FloatingService::class.java))
            stopService(Intent(this, FloatingWindowService::class.java))
            stopService(Intent(this, ScreenshotService::class.java))
            Toast.makeText(this, "已停止", Toast.LENGTH_SHORT).show()
        }

        checkStatus()
    }

    private fun checkStatus() {
        val hasOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        val hasAccessibility = isAccessibilityServiceEnabled()

        val statusText = buildString {
            appendLine("权限状态：")
            appendLine("  悬浮窗: ${if (hasOverlay) "已授权" else "未授权"}")
            appendLine("  无障碍: ${if (hasAccessibility) "已授权" else "未授权"}")
            appendLine("  截图: 需要时授权")
            appendLine()
            appendLine("使用步骤：")
            appendLine("1. 开启无障碍服务")
            appendLine("2. 开启悬浮窗权限")
            appendLine("3. 授权屏幕录制（OCR用）")
            appendLine("4. 启动助手")
            appendLine()
            appendLine("然后打开赛马娘游戏即可自动检测")
        }
        findViewById<com.google.android.material.textview.MaterialTextView>(R.id.tvStatus).text = statusText
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityService.FEEDBACK_GENERIC)
        return enabledServices.any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("需要悬浮窗权限")
                .setMessage("AI助手需要显示在游戏画面上方")
                .setPositiveButton("去设置") { _, _ ->
                    startActivityForResult(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")),
                        REQUEST_OVERLAY
                    )
                }
                .setNegativeButton("取消", null)
                .show()
        } else {
            Toast.makeText(this, "悬浮窗权限已开启", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestScreenshotPermission() {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenshotLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun requestAccessibilityService() {
        AlertDialog.Builder(this)
            .setTitle("开启无障碍服务")
            .setMessage("请在设置中找到「赛马娘AI助手」，开启无障碍服务。\n\n这是检测游戏是否在前台运行所必需的。")
            .setPositiveButton("去设置") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startFloatingWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先开启悬浮窗权限", Toast.LENGTH_SHORT).show()
            return
        }
        // 启动URA仪表盘悬浮窗（含手动输入+截图OCR功能）
        startService(Intent(this, FloatingService::class.java))
        Toast.makeText(this, "AI仪表盘已启动", Toast.LENGTH_LONG).show()
        moveTaskToBack(true)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OVERLAY) {
            checkStatus()
        }
    }

    override fun onResume() {
        super.onResume()
        checkStatus()
    }
}
