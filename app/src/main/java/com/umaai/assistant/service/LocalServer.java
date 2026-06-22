package com.umaai.assistant.service;   // 和 FloatingWindowService 同一个包

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.nanohttpd.protocols.http.IHTTPSession;
import org.nanohttpd.protocols.http.NanoHTTPD;
import org.nanohttpd.protocols.http.response.Response;
import org.nanohttpd.protocols.http.response.Status;

import java.io.IOException;

/**
 * 本地 HTTP 服务器，监听 4693 端口
 * 用于接收 Frida 脚本通过 POST 发送的游戏数据
 */
public class LocalServer extends NanoHTTPD {
    private static final String TAG = "LocalServer";
    private static final int PORT = 4693;          // 固定端口，与 Frida 脚本约定一致
    private Context context;

    public LocalServer(Context context) {
        super(PORT);
        this.context = context;
    }

    @Override
    public Response serve(IHTTPSession session) {
        // 只处理 POST 请求
        if (session.getMethod() == Method.POST) {
            try {
                // 读取 POST 请求体（JSON 字符串）
                StringBuilder sb = new StringBuilder();
                session.parseBody(sb);
                String body = sb.toString();
                Log.i(TAG, "收到数据: " + body);

                // 发送广播给悬浮窗服务，让悬浮窗显示数据
                Intent intent = new Intent("UPDATE_FLOATING");
                intent.putExtra("data", body);
                context.sendBroadcast(intent);

                // 返回成功响应
                return newFixedLengthResponse(Status.OK, "application/json", "{\"status\":\"ok\"}");
            } catch (Exception e) {
                Log.e(TAG, "处理 POST 数据失败: " + e.getMessage());
            }
        }
        // 非 POST 请求返回 400
        return newFixedLengthResponse(Status.BAD_REQUEST, "text/plain", "Bad Request");
    }

    /**
     * 启动服务器
     */
    public void startServer() {
        try {
            start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            Log.i(TAG, "HTTP 服务器已启动，端口: " + PORT);
        } catch (IOException e) {
            Log.e(TAG, "服务器启动失败: " + e.getMessage());
        }
    }

    /**
     * 停止服务器
     */
    public void stopServer() {
        stop();
        Log.i(TAG, "HTTP 服务器已停止");
    }
}