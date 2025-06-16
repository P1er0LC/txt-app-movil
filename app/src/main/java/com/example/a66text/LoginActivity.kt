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
        super.onCreate(bundle)
        setContentView(R.layout.activity_login)

        shared_preferences = getSharedPreferences("app_prefs", MODE_PRIVATE)

        val connectButton: Button = findViewById(R.id.connect_button)
        val apiKeyInput: EditText = findViewById(R.id.api_key_input)
        val siteUrlInput: EditText = findViewById(R.id.site_url_input)

        connectButton.setOnClickListener {
            val api_key = apiKeyInput.text.toString()
            val site_url = siteUrlInput.text.toString()

            /* save credentials */
            shared_preferences.edit()
                .putString("pref_api_key", api_key)
                .putString("pref_site_url", site_url)
                .apply()

            /* start SMS polling service */
            val service_intent = Intent(this, SmsService::class.java)
            ContextCompat.startForegroundService(this, service_intent)

            Toast.makeText(this, "Connected and service started", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}