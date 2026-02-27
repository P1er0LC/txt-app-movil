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
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.ContactsContract
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
    private val per_sms_delay_ms_default: Long = 5000
    private val batch_pause_ms_default: Long = 0
    private val jitter_ms_max: Long = 300
    private var polling_timer: Timer? = null
    private val http_client = OkHttpClient()
    private var sms_observer: ContentObserver? = null
    @Volatile private var is_loop_running: Boolean = false

    /*
      Called when the service is first created.
    */
    override fun onCreate() {
        super.onCreate()
        register_sms_inbox_observer()
    }

    /*
      Called when the service is started; starts foreground service and draining.
    */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        /* Start as a foreground service for reliability */
        start_foreground()

        /* Si SmsReceiver nos pasa un SMS entrante directamente, procesarlo de inmediato */
        if (intent?.getStringExtra("action") == "receive_sms") {
            val phone_number = intent.getStringExtra("phone_number") ?: ""
            val body = intent.getStringExtra("body") ?: ""
            val sub_id = intent.getIntExtra("sub_id", 1)
            val sms_db_id = intent.getLongExtra("sms_db_id", -1L)
            if (phone_number.isNotEmpty()) {
                Log.d("66text", "SmsService: recibido SMS directo de $phone_number (db_id=$sms_db_id), enviando a API...")
                val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
                val site_url = prefs.getString("pref_site_url", "")!!
                val api_key = prefs.getString("pref_api_key", "")!!
                val device_id = prefs.getString("pref_device_id", "")!!
                if (site_url.isNotEmpty() && api_key.isNotEmpty() && device_id.isNotEmpty()) {
                    Thread {
                        val success = send_received_sms_to_api(site_url, api_key, device_id, phone_number, body, sub_id)
                        if (success) {
                            Log.d("66text", "SMS entrante enviado a API correctamente (vía intent directo)")
                            /* Mostrar notificación al usuario */
                            show_received_sms_notification(phone_number, body)
                            /* Guardar último recibido para la UI */
                            prefs.edit()
                                .putString("pref_last_received_phone", phone_number)
                                .putLong("pref_last_received_ts", System.currentTimeMillis())
                                .apply()
                            /* Marcar como procesado para que el loop no lo reenvíe */
                            if (sms_db_id > 0) {
                                val current_last = prefs.getLong("pref_last_sms_id", 0L)
                                if (sms_db_id > current_last) {
                                    prefs.edit().putLong("pref_last_sms_id", sms_db_id).apply()
                                    Log.d("66text", "pref_last_sms_id actualizado a $sms_db_id")
                                }
                            }
                        } else {
                            Log.e("66text", "Falló envío a API del SMS directo; el loop de polling reintentará")
                        }
                    }.start()
                }
            }
        }

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
      Muestra una notificación emergente cuando se recibe un nuevo SMS.
    */
    private fun show_received_sms_notification(phone_number: String, body: String) {
        val channel_id = "66text_received"
        val notification_manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channel_id,
                "SMS Recibidos",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificaciones de SMS recibidos"
                enableLights(true)
                enableVibration(true)
            }
            notification_manager.createNotificationChannel(channel)
        }

        /* Al tocar la notificación abre la app */
        val tap_intent = packageManager.getLaunchIntentForPackage(packageName)
        val pending_intent = PendingIntent.getActivity(
            this, 0, tap_intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        /* Buscar nombre del contacto en la agenda del teléfono */
        val display_name = get_contact_name(phone_number) ?: phone_number
        val preview = if (body.length > 60) body.take(60) + "…" else body

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channel_id)
                .setContentTitle("SMS de $display_name")
                .setContentText(preview)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentIntent(pending_intent)
                .setAutoCancel(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("SMS de $display_name")
                .setContentText(preview)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentIntent(pending_intent)
                .setAutoCancel(true)
                .build()
        }

        /* ID único por número para agrupar mensajes del mismo remitente */
        val notification_id = phone_number.hashCode().and(0x7FFFFFFF) + 1000
        notification_manager.notify(notification_id, notification)
        Log.d("66text", "Notificación enviada para SMS de $phone_number")
    }

    /*
      Busca el nombre del contacto en la agenda del teléfono por número de teléfono.
      Devuelve null si no se encuentra o si no hay permiso READ_CONTACTS.
    */
    private fun get_contact_name(phone_number: String): String? {
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phone_number)
            )
            val cursor = contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )
            cursor?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (ex: Exception) {
            Log.w("66text", "No se pudo buscar contacto para $phone_number: ${ex.message}")
            null
        }
    }

    /*
      Drains the server queue: keep fetching and sending until no more messages are pending.
    */
    private fun start_sms() {
        /* Guard: evitar múltiples loops si SmsReceiver llama startService() varias veces */
        if (is_loop_running) {
            Log.d("66text", "Loop ya en ejecución, ignorando start_sms() duplicado")
            return
        }
        is_loop_running = true
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
                val prefs_for_delay = getSharedPreferences("app_prefs", MODE_PRIVATE)
                val delay_min = prefs_for_delay.getLong("pref_per_sms_delay_minimum", 3L)
                val delay_max = prefs_for_delay.getLong("pref_per_sms_delay_maximum", 3L)
                val per_sms_delay_ms = (delay_min..delay_max).random() * 1000L
                val batch_pause_ms = prefs_for_delay.getLong("pref_batch_pause_ms", batch_pause_ms_default)

                while (true) {
                    /* 1. Revisar inbox por SMS entrantes nuevos */
                    check_inbox_for_new_sms()

                    /* 2. Revisar API por SMS salientes pendientes */
                    try {
                        val battery_manager = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
                        val device_battery = battery_manager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                        val battery_status_intent = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                        val device_is_charging = if (battery_status_intent?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, -1) != 0) 1 else 0

                        val url = "${site_url}api/sms/get_pending/${device_id}?device_battery=${device_battery}&device_is_charging=${device_is_charging}"
                        Log.d("66text", "SMS HTTP GET: $url")

                        val request = Request.Builder()
                            .url(url)
                            .addHeader("Authorization", "Bearer $api_key")
                            .build()

                        val response = http_client.newCall(request).execute()
                        val body_string = response.body?.string()
                        response.close()

                        val shared_preferences = getSharedPreferences("app_prefs", MODE_PRIVATE)
                        shared_preferences.edit().putLong("pref_last_poll_ts", System.currentTimeMillis()).apply()

                        if (!body_string.isNullOrEmpty()) {
                            val json = try { JSONObject(body_string) } catch (_: Exception) { null }
                            val data_any = json?.opt("data")

                            if (data_any is JSONObject) {
                                val phone_number = data_any.optString("phone_number", "")
                                val content = data_any.optString("content", "")
                                val sms_id = data_any.optString("id", "")
                                val sim_subscription_id = data_any.optInt("sim_subscription_id", -1)
                                if (phone_number.isNotEmpty() && sms_id.isNotEmpty()) {
                                    send_sms(phone_number, content, sim_subscription_id, sms_id)
                                    val parts = SmsManager.getDefault().divideMessage(content).size
                                    val jitter = (0..jitter_ms_max).random().toLong()
                                    try { Thread.sleep((parts * per_sms_delay_ms) + jitter) } catch (_: InterruptedException) {}
                                }
                            } else if (data_any is JSONArray) {
                                for (i in 0 until data_any.length()) {
                                    val item = data_any.optJSONObject(i) ?: continue
                                    val phone_number = item.optString("phone_number", "")
                                    val content = item.optString("content", "")
                                    val sms_id = item.optString("id", "")
                                    val sim_subscription_id = item.optInt("sim_subscription_id", -1)
                                    if (phone_number.isNotEmpty() && sms_id.isNotEmpty()) {
                                        send_sms(phone_number, content, sim_subscription_id, sms_id)
                                        val parts = SmsManager.getDefault().divideMessage(content).size
                                        val jitter = (0..jitter_ms_max).random().toLong()
                                        try { Thread.sleep((parts * per_sms_delay_ms) + jitter) } catch (_: InterruptedException) {}
                                    }
                                }
                            }
                        }
                    } catch (ex: Exception) {
                        Log.e("66text", "Error en polling saliente: ${ex.message}")
                    }

                    /* 3. Esperar antes del próximo ciclo */
                    try { Thread.sleep(polling_interval_ms) } catch (_: InterruptedException) { break }
                }
            } catch (ex: Exception) {
                Log.e("66text", "Loop principal falló: ${ex.message}")
            } finally {
                is_loop_running = false
                stopSelf()
            }
        }.start()
    }

    /*
      Sends an SMS message to the specified phone number, optionally using the specified SIM subscription ID.
    */
    /*
  Sends an SMS message to the specified phone number, handling long messages
*/
    /*
  Sends an SMS message to the specified phone number, optionally using the specified SIM subscription ID.
  Handles restricted access on Android 14+ (e.g., Pixel 8 Pro) by gracefully falling back to the default SIM.
*/
    private fun send_sms(
        phone_number: String,
        content: String,
        sim_subscription_id: Int,
        sms_id: String
    ) {
        try {
            var sms_manager: SmsManager

            if (sim_subscription_id != -1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                try {
                    /* Try to get SmsManager for the requested SIM */
                    sms_manager = SmsManager.getSmsManagerForSubscriptionId(sim_subscription_id)
                    Log.d("66text", "Using specific SIM ID: $sim_subscription_id for $phone_number")
                } catch (security_exception: SecurityException) {
                    /* Access restricted on Pixels or Android 14+ devices */
                    Log.w("66text", "SecurityException: SIM access restricted; using default SIM instead.")
                    sms_manager = SmsManager.getDefault()
                } catch (illegal_exception: IllegalArgumentException) {
                    /* Invalid subscription ID */
                    Log.w("66text", "Invalid SIM ID: $sim_subscription_id; using default SIM instead.")
                    sms_manager = SmsManager.getDefault()
                } catch (exception: Exception) {
                    /* Any other unexpected issue */
                    Log.w("66text", "Unexpected error creating SmsManager for SIM $sim_subscription_id: ${exception.message}")
                    sms_manager = SmsManager.getDefault()
                }
            } else {
                /* fallback to default SIM (single-SIM or no SIM ID) */
                sms_manager = SmsManager.getDefault()
                Log.d("66text", "Using default SIM for $phone_number")
            }

            /* Split the message if it exceeds the limit */
            val message_parts = sms_manager.divideMessage(content)

            if (message_parts.size > 1) {
                /* Send multipart SMS for long messages */
                sms_manager.sendMultipartTextMessage(phone_number, null, message_parts, null, null)
                Log.d("66text", "Multipart SMS sent to $phone_number (SIM used: ${if (sim_subscription_id != -1) sim_subscription_id else "default"})")
            } else {
                /* Send normal SMS */
                sms_manager.sendTextMessage(phone_number, null, content, null, null)
                Log.d("66text", "Single-part SMS sent to $phone_number (SIM used: ${if (sim_subscription_id != -1) sim_subscription_id else "default"})")
            }

            /* Guardar último SMS enviado para mostrar en la UI */
            val sp = getSharedPreferences("app_prefs", MODE_PRIVATE)
            sp.edit()
                .putString("pref_last_sent_phone", phone_number)
                .putLong("pref_last_sent_ts", System.currentTimeMillis())
                .apply()

            update_sms_status(sms_id, "sent", null) /* update status to sent */
        } catch (exception: SecurityException) {
            Log.e("66text", "SEND_SMS permission denied: ${exception.message}")
            update_sms_status(sms_id, "failed", "Permission denied")
        } catch (exception: Exception) {
            Log.e("66text", "Failed to send SMS: ${exception.message}")
            update_sms_status(sms_id, "failed", exception.message)
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
        sms_observer?.let { contentResolver.unregisterContentObserver(it) }
        polling_timer?.cancel()
        super.onDestroy()
    }

    private fun register_sms_inbox_observer() {
        /* Initialize last_sms_id to current max so we don't resend old messages */
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        if (prefs.getLong("pref_last_sms_id", -1L) == -1L) {
            val cursor = contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("_id"), null, null, "_id DESC"
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val max_id = it.getLong(it.getColumnIndexOrThrow("_id"))
                    prefs.edit().putLong("pref_last_sms_id", max_id).apply()
                    Log.d("66text", "Initialized last_sms_id to $max_id")
                }
            }
        }

        sms_observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                /* Delay para esperar que Android termine de escribir el SMS en el inbox */
                Thread {
                    try { Thread.sleep(1500) } catch (_: InterruptedException) {}
                    check_inbox_for_new_sms()
                }.start()
            }
        }
        contentResolver.registerContentObserver(
            Uri.parse("content://sms/inbox"),
            true,
            sms_observer!!
        )
        Log.d("66text", "SMS inbox observer registered")
    }

    private fun check_inbox_for_new_sms() {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val site_url = prefs.getString("pref_site_url", "") ?: return
        val api_key = prefs.getString("pref_api_key", "") ?: return
        val device_id = prefs.getString("pref_device_id", "") ?: return
        if (site_url.isEmpty() || api_key.isEmpty() || device_id.isEmpty()) return

        val last_sms_id = prefs.getLong("pref_last_sms_id", 0L)

        val cursor = contentResolver.query(
            Uri.parse("content://sms/inbox"),
            arrayOf("_id", "address", "body", "sub_id"),
            "_id > ?",
            arrayOf(last_sms_id.toString()),
            "_id ASC"
        ) ?: return

        var new_last_id = last_sms_id
        cursor.use {
            while (it.moveToNext()) {
                val sms_id = it.getLong(it.getColumnIndexOrThrow("_id"))
                val phone_number = it.getString(it.getColumnIndexOrThrow("address")) ?: ""
                val body = it.getString(it.getColumnIndexOrThrow("body")) ?: ""
                val sub_id_col = it.getColumnIndex("sub_id")
                val sub_id = if (sub_id_col >= 0) it.getInt(sub_id_col).takeIf { v -> v > 0 } ?: 1 else 1

                /* Saltar remitentes que no son números reales (ej: "Entel", "BANCO", etc.) */
                val is_real_number = phone_number.matches(Regex("^[+0-9][0-9\\s\\-().]{3,}$"))
                if (!is_real_number) {
                    Log.d("66text", "SMS de remitente de texto ignorado: $phone_number (avanzando ID)")
                    if (sms_id > new_last_id) new_last_id = sms_id
                    continue
                }

                Log.d("66text", "Nuevo SMS en inbox de $phone_number, enviando a API...")
                val success = send_received_sms_to_api(site_url, api_key, device_id, phone_number, body, sub_id)

                if (success) {
                    prefs.edit()
                        .putString("pref_last_received_phone", phone_number)
                        .putLong("pref_last_received_ts", System.currentTimeMillis())
                        .apply()
                    Log.d("66text", "SMS recibido enviado correctamente a 66text")
                    /* Mostrar notificación al usuario */
                    show_received_sms_notification(phone_number, body)
                } else {
                    Log.e("66text", "Falló el envío del SMS a 66text, se reintentará en el próximo ciclo")
                    /* No actualizamos new_last_id para reintentar en el próximo ciclo */
                    continue
                }

                if (sms_id > new_last_id) new_last_id = sms_id
            }
        }

        if (new_last_id > last_sms_id) {
            prefs.edit().putLong("pref_last_sms_id", new_last_id).apply()
        }
    }

    private fun send_received_sms_to_api(site_url: String, api_key: String, device_id: String, phone_number: String, content: String, sub_id: Int = 1): Boolean {
        val battery_manager = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
        val device_battery = battery_manager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val battery_status_intent = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val device_is_charging = if (battery_status_intent?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, -1) != 0) 1 else 0

        val form_body = FormBody.Builder()
            .add("device_id", device_id)
            .add("phone_number", phone_number)
            .add("content", content)
            .add("sim_subscription_id", sub_id.toString())
            .add("device_battery", device_battery.toString())
            .add("device_is_charging", device_is_charging.toString())
            .build()

        val request = Request.Builder()
            .url("${site_url}api/sms/receive")
            .addHeader("Authorization", "Bearer $api_key")
            .post(form_body)
            .build()

        return try {
            val response = http_client.newCall(request).execute()
            val code = response.code
            val body = response.body?.string()
            response.close()
            Log.d("66text", "API sms/receive respondió: $code — $body")
            code in 200..299
        } catch (ex: Exception) {
            Log.e("66text", "Error enviando SMS recibido a API: ${ex.message}")
            false
        }
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