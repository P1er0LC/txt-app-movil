package com.example.a66text

/*
  BootReceiver.kt
  Arranca SmsService automáticamente cuando el teléfono se reinicia.
  Sin esto, el servicio no volvería a funcionar hasta que el usuario
  abra la app manualmente.
*/

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON") return

        Log.d("66text", "Boot detectado, arrancando SmsService...")

        val service_intent = Intent(context, SmsService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service_intent)
            } else {
                context.startService(service_intent)
            }
        } catch (ex: Exception) {
            Log.e("66text", "Error arrancando SmsService en boot: ${ex.message}")
        }
    }
}
