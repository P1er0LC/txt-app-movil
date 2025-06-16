package com.example.a66text

import android.Manifest
import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.util.Log

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d("66text", "MainActivity started")

        val text_view = TextView(this)
        text_view.text = "66text is running"
        setContentView(text_view)

        request_sms_permission()

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

    private fun request_sms_permission() {
        val permissions = arrayOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_PHONE_STATE
        )

        val missing_permissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing_permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing_permissions.toTypedArray(), 101)
        }
    }

    override fun onRequestPermissionsResult(request_code: Int, permissions: Array<out String>, grant_results: IntArray) {
        super.onRequestPermissionsResult(request_code, permissions, grant_results)

        if (request_code == 101) {
            if (grant_results.all { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "Permissions granted", Toast.LENGTH_SHORT).show()
                // We'll start the foreground service + polling in the next step
            } else {
                Toast.makeText(this, "Missing permissions", Toast.LENGTH_LONG).show()
            }
        }
    }
}