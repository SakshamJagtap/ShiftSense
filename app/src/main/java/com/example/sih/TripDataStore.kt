package com.example.sih

// Represents a single 100ms frame of your ride
data class TelemetryFrame(
    val rpm: Float,
    val gear: Int,
    val speed: Float,
    val throttle: Float,
    val leanAngle: Float
)

object TripDataStore {
    val tripLog = mutableListOf<TelemetryFrame>()

    fun clear() {
        tripLog.clear()
    }
}