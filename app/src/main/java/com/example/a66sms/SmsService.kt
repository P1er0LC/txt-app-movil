package com.example.a66sms

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.telephony.SmsManager
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.*
import kotlin.concurrent.fixedRateTimer

class SmsService : Service() {

    private val polling_interval_ms: Long = 10000
    private var polling_timer: Timer? = null
    private val http_client = OkHttpClient()

    override fun onCreate() {
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        /* start in foreground and begin polling */
        start_foreground()
        start_polling()
        return START_STICKY
    }

    private fun start_foreground() {
        val channel_id = "66sms_channel"
        val channel_name = "66sms Service"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channel_id, channel_name, NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channel_id)
                .setContentTitle("66sms Running")
                .setContentText("Polling your server...")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("66sms Running")
                .setContentText("Polling your server...")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .build()
        }

        startForeground(1, notification)
    }

    private fun start_polling() {
        Log.d("66sms", "Polling function started")
        val url = "https://webhook.site/38961d29-c516-4dde-947e-11892351651a"

        polling_timer = fixedRateTimer(
            name = "sms_polling_timer",
            initialDelay = 0,
            period = polling_interval_ms
        ) {
            try {
                Log.d("66sms", "Sending HTTP request to $url") /* comment */
                val request = Request.Builder().url(url).build()
                val response = http_client.newCall(request).execute()
                Log.d("66sms", "Received response: ${response.code}")

                val json_string = response.body?.string() ?: return@fixedRateTimer
                val json = JSONObject(json_string)
                val messages = json.getJSONArray("messages")

                for (i in 0 until messages.length()) {
                    val obj = messages.getJSONObject(i)
                    val phone_number = obj.getString("phone_number")
                    val message_text = obj.getString("message_text")
                    send_sms(phone_number, message_text)
                }
            } catch (ex: Exception) {
                Log.e("66sms", "Polling failed: ${ex.message}")
            }
        }
    }

    private fun send_sms(phone_number: String, message_text: String) {
        try {
            val sms = SmsManager.getDefault()
            sms.sendTextMessage(phone_number, null, message_text, null, null)
            Log.d("66sms", "SMS sent to $phone_number")
        } catch (e: Exception) {
            Log.e("66sms", "Failed to send SMS: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        polling_timer?.cancel()
        super.onDestroy()
    }
}