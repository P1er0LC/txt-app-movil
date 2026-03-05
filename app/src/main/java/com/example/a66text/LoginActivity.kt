/*
  LoginActivity.kt
  Handles user authentication, collects device info, and links the device to the PHP API endpoint.
*/

package com.example.a66text

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import android.os.BatteryManager
import android.content.IntentFilter
import android.os.Build
import java.net.NetworkInterface
import java.net.Inet4Address
import java.net.URLEncoder /* URL encoder for form data */
import org.json.JSONObject /* JSON parser */
import android.telephony.SubscriptionManager /* SIM subscription manager */
import android.telephony.SubscriptionInfo /* SIM info */
import android.telephony.TelephonyManager /* to fetch line1Number */
import java.net.HttpURLConnection
import java.net.URL
import android.Manifest /* for permission constants */
import android.content.pm.PackageManager /* for permission checks */
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat /* for checkSelfPermission */
import androidx.core.app.ActivityCompat /* for requestPermissions */
import com.google.firebase.messaging.FirebaseMessaging /* FCM token fetch */
import com.google.android.gms.tasks.Tasks /* await FCM token */
import java.util.concurrent.TimeUnit /* await timeout */

/*
  Collects user credentials and device info, requests permissions, and connects to the API for device pairing.
*/
class LoginActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_PHONE_PERMISSIONS = 1001 /* request READ_PHONE_STATE & READ_PHONE_NUMBERS */
        private const val HARDCODED_API_KEY = "e492ae6cc1d730a28123e844f63e5b0b" /* hardcoded API key */
        private const val HARDCODED_SITE_URL = "https://sms.buho.la/" /* hardcoded site URL */
    }

    private lateinit var shared_preferences: SharedPreferences
    private lateinit var apiKeyInput: EditText
    private lateinit var siteUrlInput: EditText
    private lateinit var device_id_input: EditText

    /*
      Handles activity creation, checks saved credentials, sets up UI, and links device to the API.
    */
    override fun onCreate(bundle: Bundle?) {
        /* Check for saved credentials, skip login if present */
        shared_preferences = getSharedPreferences("app_prefs", MODE_PRIVATE)
        if (
            !shared_preferences.getString("pref_api_key", "").isNullOrEmpty()
            && !shared_preferences.getString("pref_site_url", "").isNullOrEmpty()
            && !shared_preferences.getString("pref_device_id", "").isNullOrEmpty()
        ) {
            /* credentials already exist, skip login */
            val main_activity_intent = Intent(this, MainActivity::class.java)
            startActivity(main_activity_intent)
            finish()
            return
        }

        super.onCreate(bundle)

        /* Setup UI from layout */
        setContentView(R.layout.activity_login)

        shared_preferences = getSharedPreferences("app_prefs", MODE_PRIVATE)

        /* Setup button and input references */
        val connectButton: Button = findViewById(R.id.connect_button)
        apiKeyInput = findViewById(R.id.api_key_input)
        siteUrlInput = findViewById(R.id.site_url_input)
        device_id_input = findViewById(R.id.device_id_input)

        connectButton.setOnClickListener {
            /* On Connect button click, check and request runtime permissions */
            val needed_permissions = mutableListOf<String>() /* collect missing permissions */

            val has_read_phone_state = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
            val has_read_phone_numbers = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED
            val has_send_sms = ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
            val has_receive_sms = ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
            val has_read_sms = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
            val needs_post_notifications = Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

            if (!has_read_phone_state) { needed_permissions.add(Manifest.permission.READ_PHONE_STATE) }
            if (!has_read_phone_numbers) { needed_permissions.add(Manifest.permission.READ_PHONE_NUMBERS) }
            if (!has_send_sms) { needed_permissions.add(Manifest.permission.SEND_SMS) }
            if (!has_receive_sms) { needed_permissions.add(Manifest.permission.RECEIVE_SMS) }
            if (!has_read_sms) { needed_permissions.add(Manifest.permission.READ_SMS) }
            if (needs_post_notifications) { needed_permissions.add(Manifest.permission.POST_NOTIFICATIONS) }

            if (needed_permissions.isNotEmpty()) {
                /* Request any missing permissions in one dialog */
                ActivityCompat.requestPermissions(
                    this,
                    needed_permissions.toTypedArray(),
                    REQUEST_PHONE_PERMISSIONS
                )
            } else {
                handle_connect(apiKeyInput, siteUrlInput, device_id_input)
            }
        }
    }

    /*
      Extracted connect logic: collects device info, sim info, and connects to API.
    */
    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
    private fun handle_connect(apiKeyInput: EditText, siteUrlInput: EditText, device_id_input: EditText) {
        /* Collect battery, model, OS, and SIM information */
        val battery_manager = getSystemService(BATTERY_SERVICE) as BatteryManager /* battery service */
        val device_battery = battery_manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) /* battery level percentage */
        val device_model = Build.MODEL /* device model */
        val device_brand = Build.BRAND /* device brand */
        val device_os = Build.VERSION.RELEASE /* OS version */
        val battery_status_intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) /* battery status intent */
        val device_is_charging = if (battery_status_intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) != 0) 1 else 0 /* charging status as 1 or 0 */
        val ip_address = getLocalIpAddress() /* local IPv4 address */

        /* SIM info collection (safe for Android 14+ / Pixels) */
        val subscription_manager = getSystemService(TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager /* subscription manager */
        val telephony_manager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager /* phone service */
        val sim_params_builder = StringBuilder() /* building SIM params */

        try {
            val active_subscription_info_list = subscription_manager.activeSubscriptionInfoList /* may throw SecurityException on Pixels */

            if (active_subscription_info_list != null && active_subscription_info_list.isNotEmpty()) {
                active_subscription_info_list.forEachIndexed { index, subscription_info ->
                    val sim_subscription_id = subscription_info.subscriptionId /* subscription ID */
                    val sub_tm = telephony_manager.createForSubscriptionId(sim_subscription_id) /* per-SIM telephony manager */
                    val sim_carrier_name = subscription_info.carrierName?.toString() ?: "" /* carrier name */
                    val sim_display_name = subscription_info.displayName?.toString() ?: "" /* display name */
                    val sim_slot_index = subscription_info.simSlotIndex /* slot index */

                    /* Try to get phone number from all sources */
                    var sim_phone_number = ""
                    try {
                        sim_phone_number = sub_tm.line1Number ?: ""
                        if (sim_phone_number.isEmpty()) {
                            sim_phone_number = subscription_info.number ?: ""
                        }
                    } catch (exception: Exception) {
                        /* ignore silently */
                    }

                    /* Build SIM info for form data */
                    sim_params_builder.append("&sims[" + index + "][subscription_id]=" + URLEncoder.encode(sim_subscription_id.toString(), "UTF-8"))
                    sim_params_builder.append("&sims[" + index + "][phone_number]=" + URLEncoder.encode(sim_phone_number, "UTF-8"))
                    sim_params_builder.append("&sims[" + index + "][carrier_name]=" + URLEncoder.encode(sim_carrier_name, "UTF-8"))
                    sim_params_builder.append("&sims[" + index + "][display_name]=" + URLEncoder.encode(sim_display_name, "UTF-8"))
                    sim_params_builder.append("&sims[" + index + "][slot_index]=" + URLEncoder.encode(sim_slot_index.toString(), "UTF-8"))
                }
            } else {
                /* no SIMs found or blocked access */
                Log.w("66TEXTDEBUG", "SIM info not available or list empty; using fallback data.")
                sim_params_builder.append("&sims[0][subscription_id]=-1")
                sim_params_builder.append("&sims[0][carrier_name]=unknown")
                sim_params_builder.append("&sims[0][display_name]=unknown")
                sim_params_builder.append("&sims[0][slot_index]=0")
            }
        } catch (exception: SecurityException) {
            /* Pixel / Android 14+ restricted access */
            Log.w("66TEXTDEBUG", "SecurityException: SIM info restricted on this device. Fallback used.")
            sim_params_builder.append("&sims[0][subscription_id]=-1")
            sim_params_builder.append("&sims[0][carrier_name]=restricted")
            sim_params_builder.append("&sims[0][display_name]=restricted")
            sim_params_builder.append("&sims[0][slot_index]=0")
        } catch (exception: Exception) {
            Log.e("66TEXTDEBUG", "Unexpected SIM info error: ${exception.message}")
            sim_params_builder.append("&sims[0][subscription_id]=-1")
            sim_params_builder.append("&sims[0][carrier_name]=error")
            sim_params_builder.append("&sims[0][display_name]=error")
            sim_params_builder.append("&sims[0][slot_index]=0")
        }

        val sim_params = sim_params_builder.toString() /* serialized SIM params */

        /* Send device info to API using HTTP POST */
        Thread {
            try {
                /* fetch fcm token with short timeout so we can send it on initial connect */
                var device_fcm_token = ""
                try {
                    val fetched_token = Tasks.await(FirebaseMessaging.getInstance().token, 5, TimeUnit.SECONDS)
                    if (fetched_token != null) {
                        device_fcm_token = fetched_token
                    }
                } catch (_: Exception) {
                    /* ignore */
                }

                val api_key = HARDCODED_API_KEY /* using hardcoded API key */
                val site_url = HARDCODED_SITE_URL /* using hardcoded site URL */
                val device_id = device_id_input.text.toString()

                if (device_fcm_token.isNotEmpty()) {
                    shared_preferences.edit().putString("pref_device_fcm_token", device_fcm_token).apply()
                }

                val link_url = "${site_url}api/devices/${device_id}/connect"
                val url = URL(link_url)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Authorization", "Bearer $api_key")
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

                val form_data = "device_battery=" + URLEncoder.encode(device_battery.toString(), "UTF-8") +
                        "&device_model=" + URLEncoder.encode(device_model, "UTF-8") +
                        "&device_brand=" + URLEncoder.encode(device_brand, "UTF-8") +
                        "&device_os=" + URLEncoder.encode(device_os, "UTF-8") +
                        "&device_is_charging=" + URLEncoder.encode(device_is_charging.toString(), "UTF-8") +
                        "&ip=" + URLEncoder.encode(ip_address, "UTF-8") +
                        (if (device_fcm_token.isNotEmpty()) "&device_fcm_token=" + URLEncoder.encode(device_fcm_token, "UTF-8") else "") +
                        sim_params

                val output_stream = connection.outputStream
                output_stream.write(form_data.toByteArray())
                output_stream.flush()
                output_stream.close()

                val response_code = connection.responseCode
                val response_body = connection.inputStream.bufferedReader().use { it.readText() }

                var actual_device_id: String? = null
                var error_message: String? = null
                var device_name: String? = null
                var per_sms_delay_ms_from_api: Long? = null
                var per_sms_delay_minimum_from_api: Long? = null
                var per_sms_delay_maximum_from_api: Long? = null

                if (response_code == 200) {
                    val json_response = JSONObject(response_body)
                    val data_object = json_response.getJSONObject("data")
                    actual_device_id = data_object.getInt("id").toString()
                    device_name = data_object.getString("name")
                    val settings_object = data_object.optJSONObject("settings")

                    if (settings_object != null) {
                        val delay_min_any = settings_object.opt("sms_in_between_delay_minimum")
                        val delay_max_any = settings_object.opt("sms_in_between_delay_maximum")

                        val delay_min: Long? = when (delay_min_any) {
                            is Int -> delay_min_any.toLong()
                            is Long -> delay_min_any
                            is Double -> delay_min_any.toLong()
                            is String -> delay_min_any.toLongOrNull()
                            else -> null
                        }

                        val delay_max: Long? = when (delay_max_any) {
                            is Int -> delay_max_any.toLong()
                            is Long -> delay_max_any
                            is Double -> delay_max_any.toLong()
                            is String -> delay_max_any.toLongOrNull()
                            else -> null
                        }

                        if (delay_min != null && delay_min >= 0) {
                            per_sms_delay_minimum_from_api = delay_min
                        }
                        if (delay_max != null && delay_max >= 0) {
                            per_sms_delay_maximum_from_api = delay_max
                        }
                    }
                } else {
                    val error_body = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    try {
                        val json_error = JSONObject(error_body)
                        val errors_array = json_error.getJSONArray("errors")
                        val first_error = errors_array.getJSONObject(0)
                        error_message = first_error.getString("title")
                    } catch (_: Exception) {
                        error_message = "Link failed: $response_code"
                    }
                }

                runOnUiThread {
                    if (response_code == 200 && device_name != null) {
                        val editor = shared_preferences.edit()
                        editor
                            .putString("pref_api_key", api_key)
                            .putString("pref_site_url", site_url)
                            .putString("pref_device_id", actual_device_id)
                            .putString("pref_device_name", device_name)
                        if (per_sms_delay_ms_from_api != null) {
                            editor.putLong("pref_per_sms_delay_ms", per_sms_delay_ms_from_api!!)
                        }
                        if (per_sms_delay_minimum_from_api != null) {
                            editor.putLong("pref_per_sms_delay_minimum", per_sms_delay_minimum_from_api!!)
                        }
                        if (per_sms_delay_maximum_from_api != null) {
                            editor.putLong("pref_per_sms_delay_maximum", per_sms_delay_maximum_from_api!!)
                        }
                        editor.apply()

                        Toast.makeText(this, "Conectado como $device_name", Toast.LENGTH_SHORT).show()
                        val main_activity_intent = Intent(this, MainActivity::class.java)
                        startActivity(main_activity_intent)
                        finish()
                    } else {
                        Toast.makeText(this, error_message ?: "Error en la conexión: $response_code", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (exception: Exception) {
                runOnUiThread {
                    Log.e("66TEXTDEBUG", "Connection error", exception)
                    Toast.makeText(this, "Error de conexión: ${exception.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }
    /*
      Returns the first available IPv4 address for the device.
    */
    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() /* network interfaces */
            for (intf in interfaces) {
                val addrs = intf.inetAddresses /* interface addresses */
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        /* Return IPv4 address */
                        return addr.hostAddress /* return IPv4 address */
                    }
                }
            }
        } catch (exception: Exception) {
            /* Ignore exception */
        }
        return null /* no address found */
    }

    /*
      Handles the result of the permission request dialog.
    */
    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PHONE_PERMISSIONS) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "Permisos concedidos.", Toast.LENGTH_SHORT).show()
                handle_connect(apiKeyInput, siteUrlInput, device_id_input)
            } else {
                Toast.makeText(this, "Permisos denegados. No se puede recopilar información de SIM.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}