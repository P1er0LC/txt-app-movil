package com.example.a66text

/*
  SmsReceiver.kt
  BroadcastReceiver que recibe SMS_DELIVER (cuando somos la app SMS predeterminada).
  Extrae el SMS del PDU, lo escribe a content://sms/inbox y pasa los datos
  directamente a SmsService para envío inmediato al servidor.
*/

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != "android.provider.Telephony.SMS_DELIVER" &&
            action != "android.provider.Telephony.SMS_RECEIVED") return

        Log.d("66text", "SmsReceiver ($action): procesando PDU...")

        /* Extraer PDUs del intent */
        val format = intent.getStringExtra("format") ?: "3gpp"
        val sub_id = intent.getIntExtra("subscription", -1)
            .takeIf { it > 0 } ?: 1

        @Suppress("DEPRECATION")
        val pdus = (intent.extras?.get("pdus") as? Array<*>)
            ?.filterIsInstance<ByteArray>() ?: emptyList()

        if (pdus.isEmpty()) {
            Log.w("66text", "SmsReceiver: PDUs vacíos, despertando SmsService por si acaso")
            context.startService(Intent(context, SmsService::class.java))
            return
        }

        /* Construir mensajes a partir de los PDUs */
        val messages = pdus.map { pdu ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                SmsMessage.createFromPdu(pdu, format)
            } else {
                @Suppress("DEPRECATION")
                SmsMessage.createFromPdu(pdu)
            }
        }

        val phone_number = messages.firstOrNull()?.originatingAddress ?: ""
        val body = messages.joinToString("") { it.messageBody ?: "" }
        val timestamp = messages.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()

        Log.d("66text", "SMS de $phone_number: $body (sub_id=$sub_id)")

        /* Escribir a content://sms/inbox (requerido cuando somos la app SMS predeterminada) */
        var inserted_sms_id = -1L
        if (action == "android.provider.Telephony.SMS_DELIVER") {
            try {
                val values = ContentValues().apply {
                    put(Telephony.Sms.ADDRESS, phone_number)
                    put(Telephony.Sms.BODY, body)
                    put(Telephony.Sms.DATE, timestamp)
                    put(Telephony.Sms.DATE_SENT, timestamp)
                    put(Telephony.Sms.READ, 0)
                    put(Telephony.Sms.SEEN, 0)
                    put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
                    put(Telephony.Sms.SUBSCRIPTION_ID, sub_id)
                }
                val uri = context.contentResolver.insert(Uri.parse("content://sms/inbox"), values)
                inserted_sms_id = uri?.lastPathSegment?.toLongOrNull() ?: -1L
                Log.d("66text", "SMS escrito a content://sms/inbox → $uri (id=$inserted_sms_id)")
            } catch (ex: Exception) {
                Log.e("66text", "Error escribiendo SMS a content://sms: ${ex.message}")
            }
        }

        /* Iniciar SmsService pasando los datos del SMS para procesado inmediato */
        val service_intent = Intent(context, SmsService::class.java).apply {
            putExtra("action", "receive_sms")
            putExtra("phone_number", phone_number)
            putExtra("body", body)
            putExtra("sub_id", sub_id)
            putExtra("sms_db_id", inserted_sms_id)
        }
        context.startService(service_intent)
    }
}