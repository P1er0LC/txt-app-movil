package com.example.a66text

/*
  SmsReceiverFallback.kt
  Recibe SMS_RECEIVED cuando la app NO es la predeterminada.
  Solo despierta a SmsService (mismo comportamiento que SmsReceiver).
*/

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class SmsReceiverFallback : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "android.provider.Telephony.SMS_RECEIVED") {
            Log.d("66text", "SmsReceiverFallback: SMS recibido (modo no-default), despertando SmsService...")
            context.startService(Intent(context, SmsService::class.java))
        }
    }
}
