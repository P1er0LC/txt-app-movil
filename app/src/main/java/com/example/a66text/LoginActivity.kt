package com.example.a66text

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import android.widget.Button
import android.widget.EditText
import android.widget.Toast

class LoginActivity : AppCompatActivity() {

    private lateinit var shared_preferences: SharedPreferences

    override fun onCreate(bundle: Bundle?) {
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
        setContentView(R.layout.activity_login)

        shared_preferences = getSharedPreferences("app_prefs", MODE_PRIVATE)

        val connectButton: Button = findViewById(R.id.connect_button)
        val apiKeyInput: EditText = findViewById(R.id.api_key_input)
        val siteUrlInput: EditText = findViewById(R.id.site_url_input)
        val device_id_input: EditText = findViewById(R.id.device_id_input)

        connectButton.setOnClickListener {
            val api_key = apiKeyInput.text.toString()
            val site_url = siteUrlInput.text.toString()
            val device_id = device_id_input.text.toString()

            val link_url = "$site_url/api/devices/$device_id/link"
            Thread {
                try {
                    val url = java.net.URL(link_url)
                    val connection = url.openConnection() as java.net.HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.setRequestProperty("Authorization", "Bearer $api_key")
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    connection.doOutput = true

                    val response_code = connection.responseCode
                    runOnUiThread {
                        if (response_code == 200) {
                            shared_preferences.edit()
                                .putString("pref_api_key", api_key)
                                .putString("pref_site_url", site_url)
                                .putString("pref_device_id", device_id)
                                .apply()

                            Toast.makeText(this, "Connected and service started", Toast.LENGTH_SHORT).show()
                            val main_activity_intent = Intent(this, MainActivity::class.java)
                            startActivity(main_activity_intent)
                            finish()
                        } else {
                            Toast.makeText(this, "Link failed: $response_code", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (exception: Exception) {
                    runOnUiThread {
                        Toast.makeText(this, "Connection error: ${exception.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }
    }
}