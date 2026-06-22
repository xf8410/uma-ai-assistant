package com.umaai.assistant.service

import android.content.Context
import android.content.Intent
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Status
import java.io.IOException

/**
 * 本地 HTTP 服务器，监听 4693 端口
 * 接收 Frida 脚本发来的 POST 数据，并广播给悬浮窗
 */
class LocalServer(private val context: Context) : NanoHTTPD(PORT) {

    companion object {
        private const val TAG = "LocalServer"
        private const val PORT = 4693
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            if (session.method == Method.POST) {
                // 读取 POST 请求体（JSON 字符串）
                val body = session.queryParameterString ?: ""
                Log.i(TAG, "收到数据: $body")

                // 发送广播给悬浮窗服务
                val intent = Intent("UPDATE_FLOATING")
                intent.putExtra("data", body)
                context.sendBroadcast(intent)

                // ✅ 返回成功响应（加上 NanoHTTPD.）
                NanoHTTPD.newFixedLengthResponse(Status.OK, "application/json", """{"status":"ok"}""")
            } else {
                // ✅ 加上 NanoHTTPD.
                NanoHTTPD.newFixedLengthResponse(Status.BAD_REQUEST, "text/plain", "Bad Request")
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理失败: ${e.message}")
            // ✅ 加上 NanoHTTPD.
            NanoHTTPD.newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", "Server Error")
        }
    }

    /** 启动服务器 */
    fun startServer() {
        try {
            start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            Log.i(TAG, "HTTP 服务器已启动，端口: $PORT")
        } catch (e: IOException) {
            Log.e(TAG, "服务器启动失败: ${e.message}")
        }
    }

    /** 停止服务器 */
    fun stopServer() {
        stop()
        Log.i(TAG, "HTTP 服务器已停止")
    }
}