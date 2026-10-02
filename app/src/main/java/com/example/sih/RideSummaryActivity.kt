package com.example.sih

import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.max
import kotlin.math.roundToInt

class RideSummaryActivity : AppCompatActivity() {

    private lateinit var txtFinalScore: TextView
    private lateinit var txtFeedback: TextView
    private lateinit var btnDone: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ride_summary)

        txtFinalScore = findViewById(R.id.txtFinalScore)
        txtFeedback = findViewById(R.id.txtFeedback)
        btnDone = findViewById(R.id.btnDone)

        btnDone.setOnClickListener { finish() }

        // 1. Pull the actual physical ride data logged by MainActivity
        val physicalTripData = TripDataStore.tripLog.toList()

        // 2. Score the ride (Fallback to simulation if no physical data was logged)
        if (physicalTripData.isNotEmpty()) {
            generateRideScore(physicalTripData)
        } else {
            generateRideScore(simulateTripLog())
        }

        // 3. Clear memory for the next ride
        TripDataStore.clear()
    }

    private fun generateRideScore(tripData: List<TelemetryFrame>) {
        val totalFrames = tripData.size
        if (totalFrames == 0) return

        var luggingEvents = 0
        var revvingEvents = 0
        var aggressiveCorneringEvents = 0

        for (frame in tripData) {
            if (frame.gear >= 4 && frame.speed < 35.0f) luggingEvents++
            if (frame.gear <= 2 && frame.rpm > 6500.0f) revvingEvents++
            if (frame.leanAngle > 25.0f && frame.throttle > 70.0f) aggressiveCorneringEvents++
        }

        val luggingDeduction = (luggingEvents.toFloat() / totalFrames) * 100 * 2.0f
        val revvingDeduction = (revvingEvents.toFloat() / totalFrames) * 100 * 1.5f
        val corneringDeduction = (aggressiveCorneringEvents.toFloat() / totalFrames) * 100 * 3.0f

        val finalScore = max(0.0f, 100.0f - (luggingDeduction + revvingDeduction + corneringDeduction)).roundToInt()

        val feedbackList = mutableListOf<String>()
        if (luggingDeduction > 5) feedbackList.add("⚠️ Frequent engine lugging detected. Downshift earlier in traffic to improve fuel economy.")
        if (revvingDeduction > 5) feedbackList.add("⚠️ Excessive revving in low gears. Follow the HUD upshift cues to minimize engine strain.")
        if (corneringDeduction > 5) feedbackList.add("⚠️ Aggressive throttle applied during steep cornering. Roll on smoother to maintain traction.")

        if (feedbackList.isEmpty()) feedbackList.add("🌟 Excellent ride! Perfect transmission efficiency and smooth cornering.")

        txtFinalScore.text = finalScore.toString()
        txtFeedback.text = feedbackList.joinToString("\n\n")

        when {
            finalScore >= 90 -> txtFinalScore.setTextColor(Color.parseColor("#00FF66"))
            finalScore >= 70 -> txtFinalScore.setTextColor(Color.parseColor("#FFD700"))
            else -> txtFinalScore.setTextColor(Color.parseColor("#FF3333"))
        }
    }

    private fun simulateTripLog(): List<TelemetryFrame> {
        return listOf(
            TelemetryFrame(4000f, 3, 45f, 20f, 5f),
            TelemetryFrame(2000f, 5, 30f, 40f, 2f),
            TelemetryFrame(7000f, 2, 60f, 85f, 10f)
        )
    }
}