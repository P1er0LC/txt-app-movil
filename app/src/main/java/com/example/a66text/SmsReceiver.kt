package com.example.a66text

/*
  SmsReceiver.kt
  BroadcastReceiver to listen for incoming SMS messages and forward them to the PHP API endpoint if the app is authenticated.
*/

/*
  Listens for the SMS_RECEIVED broadcast and posts SMS details to the API after login.
*/
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.telephony.SmsMessage
import android.util.Log
import okhttp3.*
import java.io.IOException

class SmsReceiver : BroadcastReceiver() {

    /*
      Called automatically when an SMS is received.
      Checks for valid API configuration and forwards SMS if authenticated.
    */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "android.provider.Telephony.SMS_RECEIVED") {

            /* Get the saved API config from SharedPreferences */
            val shared_preferences = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val api_key = shared_preferences.getString("pref_api_key", "")
            val site_url = shared_preferences.getString("pref_site_url", "")
            val device_id = shared_preferences.getString("pref_device_id", "")

            /* Abort if API config is missing (not logged in) */
            if (api_key.isNullOrEmpty() || site_url.isNullOrEmpty() || device_id.isNullOrEmpty()) {
                Log.d("66text", "No API config, will not send incoming SMS to server")
                return
            }

            /* Extract SMS messages from the broadcast intent */
            val bundle: Bundle? = intent.extras
            if (bundle != null) {
                try {
                    val pdus = bundle.get("pdus") as Array<*>

                    /* For each message, parse sender and body */
                    for (pdu in pdus) {
                        val sms_message = SmsMessage.createFromPdu(pdu as ByteArray)
                        val phone_number = sms_message.displayOriginatingAddress
                        val content = sms_message.displayMessageBody

                        /* Send the parsed SMS to the API endpoint */
                        send_sms_to_api(site_url, api_key, device_id, phone_number, content)
                    }
                } catch (exception: Exception) {
                    Log.e("66text", "SMS parsing failed: ${exception.message}")
                }
            }
        }
    }

    /*
      Sends the SMS data to the PHP API endpoint using OkHttp.
    */
    private fun send_sms_to_api(site_url: String, api_key: String, device_id: String, phone_number: String, content: String) {

        /* Build the URL for the API endpoint */
        val url = "$site_url/api/sms/receive"
        val client = OkHttpClient()

        /* Build the form data for the POST request */
        val form_body = FormBody.Builder()
            .add("device_id", device_id)
            .add("phone_number", phone_number)
            .add("content", content)
            .build()

        /* Build and send the POST request asynchronously */
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $api_key")
            .post(form_body)
            .build()

        client.newCall(request).enqueue(object : Callback {

            /* Handle failure to send */
            override fun onFailure(call: Call, exception: IOException) {
                Log.e("66text", "Failed to send SMS to API: ${exception.message}")
            }

            /* Handle successful response */
            override fun onResponse(call: Call, response: Response) {
                Log.d("66text", "Sent incoming SMS to API, code: ${response.code}")
                response.close()
            }
        })
    }
}