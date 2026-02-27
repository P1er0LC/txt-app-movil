package com.example.a66text

/*
  WapPushReceiver.kt
  Requerido por Android para poder ser la app SMS predeterminada.
  Recibe notificaciones WAP/MMS push. Como este dispositivo actúa como
  gateway SMS (no visualiza conversaciones), no procesamos el contenido.
*/

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class WapPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("66text", "WapPushReceiver: WAP/MMS push recibido (ignorado en modo gateway)")
    }
}
