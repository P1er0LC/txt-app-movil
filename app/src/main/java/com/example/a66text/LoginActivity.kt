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
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat /* for checkSelfPermission */
import androidx.core.app.ActivityCompat /* for requestPermissions */

/*
  Collects user credentials and device info, requests permissions, and connects to the API for device pairing.
*/
class LoginActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_PHONE_PERMISSIONS = 1001 /* request READ_PHONE_STATE & READ_PHONE_NUMBERS */
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
            /* On Connect button click, check for phone permissions */
            val hasState = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
            val hasNumber = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED
            if (!hasState || !hasNumber) {
                /* Request phone state and number permissions if missing */
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_PHONE_NUMBERS),
                    REQUEST_PHONE_PERMISSIONS
                ) /* request both permissions */
            }
            else {
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
        val device_is_charging = battery_status_intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) != 0 /* charging status */
        val ip_address = getLocalIpAddress() /* local IPv4 address */

        /* SIM info */
        val subscription_manager = getSystemService(TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager /* subscription manager */
        val telephony_manager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager /* phone service */
        val active_subscription_info_list = subscription_manager.activeSubscriptionInfoList /* list of active SIMs */
        val sim_params_builder = StringBuilder() /* building SIM params */
        active_subscription_info_list?.forEachIndexed { index, subscription_info ->
            val sim_subscription_id = subscription_info.subscriptionId /* subscription ID */
            val sub_tm = telephony_manager.createForSubscriptionId(sim_subscription_id) /* per-SIM telephony manager */
            val sim_phone_number = sub_tm.line1Number ?: subscription_info.number ?: "" /* phone number */
            val sim_carrier_name = subscription_info.carrierName?.toString() ?: "" /* carrier name */
            val sim_display_name = subscription_info.displayName?.toString() ?: "" /* display name */
            val sim_slot_index = subscription_info.simSlotIndex /* slot index */

            /* Build SIM info for form data */
            sim_params_builder.append("&sims[" + index + "][subscription_id]=" + URLEncoder.encode(sim_subscription_id.toString(), "UTF-8"))
            sim_params_builder.append("&sims[" + index + "][phone_number]=" + URLEncoder.encode(sim_phone_number, "UTF-8"))
            sim_params_builder.append("&sims[" + index + "][carrier_name]=" + URLEncoder.encode(sim_carrier_name, "UTF-8"))
            sim_params_builder.append("&sims[" + index + "][display_name]=" + URLEncoder.encode(sim_display_name, "UTF-8"))
            sim_params_builder.append("&sims[" + index + "][slot_index]=" + URLEncoder.encode(sim_slot_index.toString(), "UTF-8"))
        }
        val sim_params = sim_params_builder.toString() /* serialized SIM params */

        /* Send device info to API using HTTP POST */
        Thread {
            try {
                val api_key = apiKeyInput.text.toString()
                val site_url = siteUrlInput.text.toString()
                val device_id = device_id_input.text.toString()

                val link_url = "${site_url}api/devices/${device_id}/connect"
                val url = URL(link_url)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Authorization", "Bearer $api_key")
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded") /* sending form data */
                val form_data = "device_battery=" + URLEncoder.encode(device_battery.toString(), "UTF-8") +
                    "&device_model=" + URLEncoder.encode(device_model, "UTF-8") +
                    "&device_brand=" + URLEncoder.encode(device_brand, "UTF-8") +
                    "&device_os=" + URLEncoder.encode(device_os, "UTF-8") +
                    "&device_is_charging=" + URLEncoder.encode(device_is_charging.toString(), "UTF-8") +
                    "&ip=" + URLEncoder.encode(ip_address, "UTF-8") +
                    sim_params /* form data string including SIM info */

                val output_stream = connection.outputStream /* output stream for form data */
                output_stream.write(form_data.toByteArray())
                output_stream.flush()
                output_stream.close()

                val response_code = connection.responseCode /* HTTP status code */
                val response_body = connection.inputStream.bufferedReader().use { reader -> reader.readText() } /* server response */
                var error_message: String? = null /* to hold error title */
                var device_name: String? = null /* to hold returned device name */

                if (response_code == 200) {
                    val json_response = JSONObject(response_body) /* parse JSON response */
                    val data_object = json_response.getJSONObject("data") /* extract data object */
                    device_name = data_object.getString("name") /* get device name */
                } else {
                    val error_body = connection.errorStream?.bufferedReader()?.use { reader -> reader.readText() } /* error response */
                    try {
                        val json_error = JSONObject(error_body) /* parse error JSON */
                        val errors_array = json_error.getJSONArray("errors") /* errors array */
                        val first_error = errors_array.getJSONObject(0) /* first error object */
                        error_message = first_error.getString("title") /* extract title */
                    } catch (exception: Exception) {
                        error_message = "Link failed: $response_code" /* fallback message */
                    }
                }

                /* Parse API response, save credentials, handle result */
                runOnUiThread {
                    if (response_code == 200 && device_name != null) {
                        shared_preferences.edit()
                            .putString("pref_api_key", api_key)
                            .putString("pref_site_url", site_url)
                            .putString("pref_device_id", device_id)
                            .putString("pref_device_name", device_name) /* save returned name */
                            .apply()

                        Toast.makeText(this, "Connected as $device_name", Toast.LENGTH_SHORT).show() /* show device name */
                        val main_activity_intent = Intent(this, MainActivity::class.java)
                        startActivity(main_activity_intent)
                        finish()
                    } else {
                        Toast.makeText(this, error_message ?: "Link failed: $response_code", Toast.LENGTH_SHORT).show() /* show parsed error */
                    }
                }
            } catch (exception: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Connection error: ${exception.message}", Toast.LENGTH_SHORT).show()
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
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PHONE_PERMISSIONS) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "Permissions granted. Tap Connect again.", Toast.LENGTH_SHORT).show()
                handle_connect(apiKeyInput, siteUrlInput, device_id_input)
            } else {
                Toast.makeText(this, "Permissions denied. Cannot collect SIM info.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}