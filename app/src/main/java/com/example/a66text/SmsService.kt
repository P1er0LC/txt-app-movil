/*
  SmsService.kt
  Background Service to poll the PHP API for outgoing SMS and send them using the device.
*/
package com.example.a66text

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.telephony.SmsManager
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.*
import kotlin.concurrent.fixedRateTimer

/*
  Runs in the background to check the API for new messages to send and sends them as SMS.
*/
class SmsService : Service() {

    private val polling_interval_ms: Long = 10000000
    //private val polling_interval_ms: Long = 10000
    private var polling_timer: Timer? = null
    private val http_client = OkHttpClient()

    /*
      Called when the service is first created.
    */
    override fun onCreate() {
        super.onCreate()
    }

    /*
      Called when the service is started; starts foreground service and polling.
    */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        /* Start as a foreground service for reliability */
        start_foreground()

        /* Begin polling the API for messages */
        start_polling()

        return START_STICKY
    }

    /*
      Creates and starts the foreground notification for the service.
    */
    private fun start_foreground() {
        val channel_id = "66text_channel"
        val channel_name = "66text Service"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            /* Create notification channel for Android O and above */
            val channel = NotificationChannel(channel_id, channel_name, NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        /* Build and show the notification */
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channel_id)
                .setContentTitle("66text Running")
                .setContentText("Polling your server...")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("66text Running")
                .setContentText("Polling your server...")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .build()
        }

        startForeground(1, notification)
    }

    /*
      Starts a timer to periodically poll the API for new SMS messages.
    */
    private fun start_polling() {
        Log.d("66text", "Polling function started")

        /* Load API config from SharedPreferences */
        val prefs: SharedPreferences = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val siteUrl = prefs.getString("pref_site_url", "")!!.trimEnd('/')
        val apiKey = prefs.getString("pref_api_key", "")!!

        /* Build the polling URL */
        val url = "${siteUrl}api/sms?api_key=${apiKey}"

        /* Start polling the API at fixed intervals */
        polling_timer = fixedRateTimer(
            name = "sms_polling_timer",
            initialDelay = 0,
            period = polling_interval_ms
        ) {
            try {
                /* Send HTTP request to get new messages */
                Log.d("66text", "Sending HTTP request to $url") /* comment */
                val request = Request.Builder().url(url).build()
                val response = http_client.newCall(request).execute()
                Log.d("66text", "Received response: ${response.code}")

                /* Parse JSON and extract messages */
                val json_string = response.body?.string() ?: return@fixedRateTimer
                val json = JSONObject(json_string)
                val data_object = json.getJSONObject("data")
                val messages_array = JSONArray().put(data_object.getJSONObject("messages"))

                for (i in 0 until messages_array.length()) {
                    val message_object = messages_array.getJSONObject(i)
                    val phone_number = message_object.getString("phone_number")
                    val text = message_object.getString("text")

                    /* Send SMS using SmsManager */
                    send_sms(phone_number, text)
                }
            } catch (ex: Exception) {
                /* Log errors if any */
                Log.e("66text", "Polling failed: ${ex.message}")
            }
        }
    }

    /*
      Sends an SMS message to the specified phone number.
    */
    private fun send_sms(phone_number: String, text: String) {
        try {
            /* Send SMS using SmsManager */
            val sms = SmsManager.getDefault()
            sms.sendTextMessage(phone_number, null, text, null, null)
            Log.d("66text", "SMS sent to $phone_number")
        } catch (e: Exception) {
            /* Log errors if any */
            Log.e("66text", "Failed to send SMS: ${e.message}")
        }
    }

    /*
      Returns null as this service does not support binding.
    */
    override fun onBind(intent: Intent?): IBinder? = null

    /*
      Called when the service is destroyed; cancels the polling timer.
    */
    override fun onDestroy() {
        polling_timer?.cancel()
        super.onDestroy()
    }
}