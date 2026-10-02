package com.example.sih

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.telephony.SmsManager
import android.util.Log

class SOSBlackboxManager(private val context: Context) {
    private var isCrashLocked = false

    @SuppressLint("MissingPermission")
    fun triggerSOSProtocol(leanAngle: Float, deceleration: Float) {
        if (isCrashLocked || leanAngle < 85f) return
        isCrashLocked = true

        try {
            val locManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val location = locManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val lat = location?.latitude ?: 18.6298
            val lon = location?.longitude ?: 73.7997

            // Backward-compatible SMS call
            val smsManager = context.getSystemService(SmsManager::class.java) ?: @Suppress("DEPRECATION") SmsManager.getDefault()
            smsManager.sendTextMessage("+919876543210", null, "🚨 CEP SOS: Crash detected! Loc: maps.google.com/maps?q=$lat,$lon", null, null)
            Log.e("SOS", "SMS Sent")
        } catch (e: Exception) { Log.e("SOS", "SMS Failed: ${e.message}") }
    }
}