package com.example.a66text

/*
  SmsRespondService.kt
  Service requerido por Android para ser la app SMS predeterminada.
  Permite al sistema enviar respuestas SMS desde notificaciones de llamada.
  Al ser un gateway, no implementamos lógica adicional.
*/

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

class SmsRespondService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("66text", "SmsRespondService: solicitud recibida")
        stopSelf()
        return START_NOT_STICKY
    }
}
