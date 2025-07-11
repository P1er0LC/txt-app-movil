/*
  SmsService.kt
  Background Service to poll the PHP API for outgoing SMS and send them using the device.
*/
package com.example.a66text

import android.app.*
import android.app.Service.MODE_PRIVATE
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.telephony.SmsManager
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.FormBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.*
import kotlin.concurrent.fixedRateTimer

/*
  Runs in the background to check the API for new messages to send and sends them as SMS.
*/
class SmsService : Service() {

    //    private val polling_interval_ms: Long = 10000000
    private val polling_interval_ms: Long = 10000
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
            val channel =
                NotificationChannel(channel_id, channel_name, NotificationManager.IMPORTANCE_LOW)
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
        val site_url = prefs.getString("pref_site_url", "")!!
        val api_key = prefs.getString("pref_api_key", "")!!
        val device_id = prefs.getString("pref_device_id", "")!!

        /* Build the polling URL */
        val url = "${site_url}api/sms/get_pending/${device_id}"

        /* Start polling the API at fixed intervals */
        polling_timer = fixedRateTimer(
            name = "sms_polling_timer",
            initialDelay = 0,
            period = polling_interval_ms
        ) {
            try {
                /* Send HTTP request to get new messages */
                Log.d("66text", "Sending HTTP request to $url") /* comment */
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $api_key")
                    .build()
                val response = http_client.newCall(request).execute()
                Log.d("66text", "Received response: ${response.code}")

                /* Parse JSON and extract messages */
                val json_string = response.body?.string() ?: return@fixedRateTimer
                val json = JSONObject(json_string)
                val data_object = json.getJSONObject("data")

                val phone_number = data_object.getString("phone_number")
                val content = data_object.getString("content")
                val sms_id = data_object.getString("id") /* get sms_id from response */
                val sim_subscription_id = data_object.optInt("sim_subscription_id", -1)

                /* Send SMS using SmsManager and custom subscription id */
                send_sms(phone_number, content, sim_subscription_id, sms_id)
            } catch (ex: Exception) {
                /* Log errors if any */
                Log.e("66text", "Polling failed: ${ex.message}")
            }
        }
    }

    /*
      Sends an SMS message to the specified phone number, optionally using the specified SIM subscription ID.
    */
    private fun send_sms(
        phone_number: String,
        content: String,
        sim_subscription_id: Int,
        sms_id: String
    ) {
        try {
            val sms_manager =
                if (sim_subscription_id != -1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    SmsManager.getSmsManagerForSubscriptionId(sim_subscription_id)
                } else {
                    SmsManager.getDefault()
                }
            sms_manager.sendTextMessage(phone_number, null, content, null, null)
            Log.d("66text", "SMS sent to $phone_number using SIM $sim_subscription_id")
            update_sms_status(sms_id, "sent", null) /* update status to sent */
        } catch (e: Exception) {
            Log.e("66text", "Failed to send SMS: ${e.message}")
            update_sms_status(sms_id, "failed", e.message)
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

    /*
      Updates the status of the SMS message on the server.
    */
    private fun update_sms_status(sms_id: String, status: String, error: String?) {
        /* Load API config from SharedPreferences */
        val prefs: SharedPreferences = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val site_url = prefs.getString("pref_site_url", "")!!
        val api_key = prefs.getString("pref_api_key", "")!!
        val url = "${site_url}api/sms/update_status"

        /* Prepare form body */
        val formBuilder = FormBody.Builder()
            .add("sms_id", sms_id)
            .add("status", status)
        if (error != null) {
            formBuilder.add("error", error)
        }
        val body = formBuilder.build()

        /* Send POST request in background thread */
        Thread {
            try {
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer " + api_key)
                    .post(body)
                    .build()
                val response = http_client.newCall(request).execute()
                Log.d("66text", "Status updated: " + response.code)
            } catch (ex: Exception) {
                Log.e("66text", "Failed to update status: " + ex.message)
            }
        }.start()
    }
}