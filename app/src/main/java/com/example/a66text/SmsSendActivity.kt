package com.example.a66text

/*
  SmsSendActivity.kt
  Activity requerida por Android para ser la app SMS predeterminada.
  Redirige al hilo de conversación de Google Messages si está disponible,
  o simplemente cierra. No usamos esta app para redactar SMS.
*/

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log

class SmsSendActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("66text", "SmsSendActivity: redirigiendo a app de mensajes del sistema...")

        /* Intentar abrir Google Messages para redactar */
        try {
            val forward = Intent(intent)
            forward.setPackage("com.google.android.apps.messaging")
            forward.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(forward)
        } catch (e: Exception) {
            Log.w("66text", "No se pudo abrir Google Messages: ${e.message}")
        }

        finish()
    }
}
