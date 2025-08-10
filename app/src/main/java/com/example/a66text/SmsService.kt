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
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

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
      Called when the service is started; starts foreground service and draining.
    */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        /* Start as a foreground service for reliability */
        start_foreground()

        /* Begin draining on demand (push-to-wake, or app-start) */
        start_sms()

        return START_NOT_STICKY
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
                .setContentText("Syncing with your server...")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("66text Running")
                .setContentText("Syncing with your server...")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .build()
        }

        startForeground(1, notification)
    }

    /*
      Drains the server queue: keep fetching and sending until no more messages are pending.
    */
    private fun start_sms() {
        Log.d("66text", "Sms sending loop started")

        /* Load API config from SharedPreferences */
        val prefs: SharedPreferences = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val site_url = prefs.getString("pref_site_url", "")!!
        val api_key = prefs.getString("pref_api_key", "")!!
        val device_id = prefs.getString("pref_device_id", "")!!

        if (site_url.isEmpty() || api_key.isEmpty() || device_id.isEmpty()) {
            Log.e("66text", "Missing API config; aborting drain")
            stopSelf()
            return
        }

        Thread {
            try {
                var iterations = 0
                while (true) {
                    iterations += 1

                    val battery_manager = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
                    val device_battery = battery_manager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    val battery_status_intent = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                    val device_is_charging = if (battery_status_intent?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, -1) != 0) 1 else 0

                    val url = "${site_url}api/sms/get_pending/${device_id}?device_battery=${device_battery}&device_is_charging=${device_is_charging}"
                    Log.d("66text", "SMS HTTP GET: $url") /* comment */

                    val request = Request.Builder()
                        .url(url)
                        .addHeader("Authorization", "Bearer $api_key")
                        .build()

                    val response = http_client.newCall(request).execute()
                    val code = response.code
                    val body_string = response.body?.string()
                    response.close()

                    val shared_preferences = getSharedPreferences("app_prefs", MODE_PRIVATE)
                    shared_preferences.edit().putLong("pref_last_poll_ts", System.currentTimeMillis()).apply()

                    /* No content or not OK -> stop draining */
                    if (code == 204 || body_string.isNullOrEmpty()) {
                        Log.d("66text", "No pending messages (204/empty body). Stopping drain.")
                        break
                    }

                    val json = try { JSONObject(body_string) } catch (ex: Exception) {
                        Log.e("66text", "Invalid JSON: ${ex.message}")
                        break
                    }

                    /* Expect either data object or data array; handle both defensively */
                    val data_any = json.opt("data")
                    if (data_any == null) {
                        Log.d("66text", "No 'data' in response. Stopping drain.")
                        break
                    }

                    if (data_any is JSONObject) {
                        val phone_number = data_any.optString("phone_number", "")
                        val content = data_any.optString("content", "")
                        val sms_id = data_any.optString("id", "")
                        val sim_subscription_id = data_any.optInt("sim_subscription_id", -1)

                        if (phone_number.isEmpty() || sms_id.isEmpty()) {
                            Log.d("66text", "Empty job. Stopping drain.")
                            break
                        }
                        send_sms(phone_number, content, sim_subscription_id, sms_id)
                    } else if (data_any is JSONArray) {
                        if (data_any.length() == 0) {
                            Log.d("66text", "Empty array. Stopping drain.")
                            break
                        }
                        for (i in 0 until data_any.length()) {
                            val item = data_any.optJSONObject(i) ?: continue
                            val phone_number = item.optString("phone_number", "")
                            val content = item.optString("content", "")
                            val sms_id = item.optString("id", "")
                            val sim_subscription_id = item.optInt("sim_subscription_id", -1)
                            if (phone_number.isNotEmpty() && sms_id.isNotEmpty()) {
                                send_sms(phone_number, content, sim_subscription_id, sms_id)
                            }
                        }
                    } else {
                        Log.d("66text", "Unknown data type. Stopping drain.")
                        break
                    }

                    /* Safety to avoid infinite tight loop if server misbehaves */
                    if (iterations >= 100) {
                        Log.w("66text", "Drain loop safeguard hit (100 iterations). Stopping.")
                        break
                    }
                }
            } catch (ex: Exception) {
                Log.e("66text", "Drain failed: ${ex.message}")
            } finally {
                stopSelf()
            }
        }.start()
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

/*
  PushMessagingService
  Receives FCM pushes and starts SmsService to drain the queue.
*/
class PushMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(remote_message: RemoteMessage) {
        /* Check data payload type if provided */
        val message_type = remote_message.data["type"] ?: "sms"
        if (message_type == "sms") {
            val context = applicationContext
            val service_intent = Intent(context, SmsService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(service_intent)
                } else {
                    context.startService(service_intent)
                }
            } catch (ex: Exception) {
                Log.e("66text", "Failed to start SmsService from FCM: ${ex.message}")
            }
        }
    }

    override fun onNewToken(new_token: String) {
        Log.d("66text", "66text FCM DEVICE token: $new_token")
        /* persist latest token for initial connect or later updates */
        val shared_preferences = getSharedPreferences("app_prefs", MODE_PRIVATE)
        shared_preferences.edit().putString("pref_fcm_token", new_token).apply()
    }
}