/*
  MainActivity.kt
  Main entry activity for the app. Handles UI, authentication check, permission requests, and starting the background SMS service.
*/

package com.example.a66text

import java.util.Timer
import kotlin.concurrent.timerTask

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.os.Bundle
import android.provider.Telephony
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.util.Log
import android.widget.Button
import android.os.Handler
import android.os.Looper

/*
  Displays the main interface, checks authentication, and manages background SMS service and polling UI.
*/
class MainActivity : Activity() {

    private val poll_handler = Handler(Looper.getMainLooper())
    private val poll_runnable = object : Runnable {
        override fun run() {
            val shared_preferences = getSharedPreferences("app_prefs", MODE_PRIVATE)
            val updated_ts = shared_preferences.getLong("pref_last_poll_ts", 0L)
            findViewById<TextView>(R.id.text_last_poll).text =
                "Última consulta: ${formatTimeAgo(updated_ts)}"

            val last_received_phone = shared_preferences.getString("pref_last_received_phone", null)
            val last_received_ts = shared_preferences.getLong("pref_last_received_ts", 0L)
            val last_sent_phone = shared_preferences.getString("pref_last_sent_phone", null)
            val last_sent_ts = shared_preferences.getLong("pref_last_sent_ts", 0L)

            findViewById<TextView>(R.id.text_last_received).text =
                if (last_received_phone != null) "$last_received_phone · ${formatTimeAgo(last_received_ts)}"
                else "Ninguno aún"

            findViewById<TextView>(R.id.text_last_sent).text =
                if (last_sent_phone != null) "$last_sent_phone · ${formatTimeAgo(last_sent_ts)}"
                else "Ninguno aún"

            poll_handler.postDelayed(this, 1000)
        }
    }

    /*
      Formats a timestamp as a "time ago" string.
    */
    fun formatTimeAgo(timestamp: Long): String {
        if (timestamp == 0L) return "Nunca"
        val diff = System.currentTimeMillis() - timestamp
        val seconds = diff / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        return when {
            hours > 0 -> "hace $hours hora(s)"
            minutes > 0 -> "hace $minutes minuto(s)"
            else -> "hace $seconds segundo(s)"
        }
    }

    /*
      Handles activity creation, checks login, sets up UI, and starts background service.
    */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        /* Load API key and site URL from SharedPreferences */
        val shared_preferences = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val api_key = shared_preferences.getString("pref_api_key", "")
        val site_url = shared_preferences.getString("pref_site_url", "")

        /* Redirect to login screen if not authenticated */
        if (api_key.isNullOrEmpty() || site_url.isNullOrEmpty()) {
            Toast.makeText(this, "Falta API key o URL del sitio. Por favor inicia sesión.", Toast.LENGTH_LONG).show()
            val login_intent = Intent(this, LoginActivity::class.java)
            startActivity(login_intent)
            finish()
            return
        }

        Log.d("66text", "MainActivity started")

        /* Setup main UI from XML layout */
        setContentView(R.layout.activity_main) /* use XML layout */

        /* Setup button and text view references */
        val status_value = findViewById<TextView>(R.id.text_status_value)
        val last_poll_view = findViewById<TextView>(R.id.text_last_poll)
        val device_name_view = findViewById<TextView>(R.id.text_device_name)
        val site_url_view = findViewById<TextView>(R.id.text_site_url)
        val disconnect_button = findViewById<Button>(R.id.button_disconnect)

        /* Set device name, site URL, last poll info */
        val device_name = shared_preferences.getString("pref_device_name", "Dispositivo desconocido")

        device_name_view.text = "Dispositivo: $device_name"
        site_url_view.text = "URL: ${site_url ?: ""}"
        last_poll_view.text = "Última consulta: ahora"

        val last_poll_timestamp = shared_preferences.getLong("pref_last_poll_ts", 0L)

        poll_handler.post(poll_runnable)

        status_value.text = "Conectado"
        status_value.setTextColor(getColor(android.R.color.holo_green_dark))

        /* Handle disconnect button click */
        disconnect_button.setOnClickListener {
            val editor = shared_preferences.edit()
            editor.remove("pref_api_key")
            editor.remove("pref_site_url")
            editor.remove("pref_device_id")
            editor.remove("pref_device_name")
            editor.apply()

            /* Remove poll handler callbacks */
            poll_handler.removeCallbacks(poll_runnable)

            /* Stop the background SMS polling service */
            stopService(Intent(this, SmsService::class.java))

            val login_intent = Intent(this, LoginActivity::class.java)
            startActivity(login_intent)
            finish()
        }

        /* Request SMS/phone permissions */
        request_sms_permission()

        /* Start background SMS service (foreground for Android O+) */
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Log.d("66text", "Starting foreground service")
                startForegroundService(Intent(this, SmsService::class.java))
            } else {
                Log.d("66text", "Starting regular service")
                startService(Intent(this, SmsService::class.java))
            }
        } catch (ex: Exception) {
            Log.e("66text", "Service start failed: ${ex.message}")
        }
    }

    /*
      Verifica si somos la app SMS predeterminada y muestra el diálogo del sistema para serlo.
      Cuando somos la default, Google Messages del remitente cae a SMS tradicional (sin RCS).
    */
    private fun prompt_set_default_sms_app() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val role_manager = getSystemService(RoleManager::class.java)
                if (!role_manager.isRoleHeld(RoleManager.ROLE_SMS)) {
                    val role_intent = role_manager.createRequestRoleIntent(RoleManager.ROLE_SMS)
                    startActivityForResult(role_intent, 200)
                }
            } else {
                val default_pkg = Telephony.Sms.getDefaultSmsPackage(this)
                if (default_pkg != packageName) {
                    val change_intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
                    change_intent.putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
                    startActivityForResult(change_intent, 200)
                }
            }
        } catch (e: Exception) {
            Log.w("66text", "No se pudo solicitar app SMS predeterminada: ${e.message}")
        }
    }

    /*
      Requests necessary SMS and phone permissions.
    */
    private fun request_sms_permission() {
        val permissions = arrayOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_PHONE_NUMBERS,
            Manifest.permission.READ_CONTACTS
        )

        val missing_permissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing_permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing_permissions.toTypedArray(), 101)
        }
    }

    /*
      Handles the result of the permission request dialog.
    */
    override fun onRequestPermissionsResult(request_code: Int, permissions: Array<out String>, grant_results: IntArray) {
        super.onRequestPermissionsResult(request_code, permissions, grant_results)

        if (request_code == 101) {
            if (grant_results.all { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "Permisos concedidos", Toast.LENGTH_SHORT).show()
                // We'll start the foreground service + polling in the next step
            } else {
                Toast.makeText(this, "Faltan permisos", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 200) {
            if (resultCode == RESULT_OK) {
                Log.d("66text", "App establecida como SMS predeterminada correctamente")
                Toast.makeText(this, "66text es ahora la app SMS predeterminada", Toast.LENGTH_SHORT).show()
            } else {
                Log.w("66text", "Usuario rechazó ser app SMS predeterminada")
            }
        }
    }

    /*
      Cleans up poll handler on activity destroy.
    */
    override fun onResume() {
        super.onResume()
        /* Pedir ser la app SMS predeterminada al volver al foco (elimina RCS) */
        prompt_set_default_sms_app()
    }

    override fun onDestroy() {
        super.onDestroy()
        /* Remove poll handler callbacks */
        poll_handler.removeCallbacks(poll_runnable)
    }
}