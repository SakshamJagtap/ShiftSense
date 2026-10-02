package com.example.sih

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.channels.FileChannel

class MainActivity : AppCompatActivity() {

    private lateinit var obdManager: OBDConnectionManager
    private lateinit var txtShiftCue: TextView
    private lateinit var txtRPM: TextView
    private lateinit var txtLoad: TextView
    private lateinit var txtGear: TextView
    private lateinit var txtTargetRPM: TextView
    private lateinit var btnFinishRide: Button

    private var tflite: Interpreter? = null

    // REPLACE THIS WITH YOUR ELM327 MAC ADDRESS
    private val elm327MacAddress = "01:23:45:67:89:BA"
    private val PERMISSION_REQUEST_CODE = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        txtShiftCue = findViewById(R.id.txtShiftCue)
        txtRPM = findViewById(R.id.txtRPM)
        txtLoad = findViewById(R.id.txtLoad)
        txtGear = findViewById(R.id.txtGear)
        txtTargetRPM = findViewById(R.id.txtTargetRPM)
        btnFinishRide = findViewById(R.id.btnFinishRide)

        // 1. Force default disconnected state immediately upon launch
        setDisconnectedUi()

        try {
            tflite = Interpreter(loadModelFile("cep_shift_model.tflite"))
        } catch (e: Exception) {
            android.util.Log.e("AI_ERROR", "Failed to load model: ${e.message}")
            txtShiftCue.text = "AI CRASHED"
            txtTargetRPM.text = "CHECK LOGCAT"
        }

        btnFinishRide.setOnClickListener {
            if (::obdManager.isInitialized) obdManager.disconnect()
            startActivity(Intent(this, RideSummaryActivity::class.java))
            finish()
        }

        checkAndRequestPermissions()
    }

    private fun setDisconnectedUi() {
        txtShiftCue.text = "BIKE NOT CONNECTED"
        txtShiftCue.setTextColor(getColor(android.R.color.darker_gray))
        txtGear.text = "-"
        txtRPM.text = "0"
        txtLoad.text = "0%"
        txtTargetRPM.text = "TARGET SHIFT: - RPM"
    }

    private fun checkAndRequestPermissions() {
        val reqPermissions = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = reqPermissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSION_REQUEST_CODE) else initializeHardware()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) initializeHardware()
    }

    private fun initializeHardware() {
        obdManager = OBDConnectionManager(elm327MacAddress)

        // Loop now expects (RPM, Load, Speed) from OBDConnectionManager
        obdManager.connectAndStartPolling { liveRpm, liveLoad, liveSpeed ->
            txtRPM.text = "${liveRpm.toInt()}"
            txtLoad.text = "${liveLoad.toInt()}%"

            val gearString = estimateCurrentGear(liveRpm, liveSpeed)
            txtGear.text = gearString

            // Bypass inference if the bike is stopped or idling (Gear "-")
            if (gearString == "-") {
                txtTargetRPM.text = "TARGET SHIFT: - RPM"
                txtShiftCue.text = "🟢 HOLD"
                txtShiftCue.setTextColor(getColor(android.R.color.holo_green_light))
            } else {
                val currentGear = gearString.toInt()
                val throttlePos = liveLoad * 0.8f
                val roadIncline = 0f
                val leanAngle = 0f // Hardware IMU integration pending

                // 🔴 Log the physical frame into the data store for scoring
                TripDataStore.tripLog.add(
                    TelemetryFrame(liveRpm, currentGear, liveSpeed, throttlePos, leanAngle)
                )

                // Pass live gear into AI model
                val predictedOptimalRpm = runInference(liveLoad, throttlePos, roadIncline, currentGear.toFloat())
                txtTargetRPM.text = "TARGET SHIFT: ${predictedOptimalRpm.toInt()} RPM"

                updateDashboardUi(generateHudCommand(liveRpm, predictedOptimalRpm, currentGear))
            }
        }
    }

    private fun estimateCurrentGear(rpm: Float, speed: Float): String {
        // If speed is negligible, the motorcycle is stopped or idling in neutral
        if (speed < 5f || rpm < 500f) return "-"

        val ratio = rpm / speed
        return when {
            ratio > 110 -> "1"
            ratio > 80 -> "2"
            ratio > 60 -> "3"
            ratio > 50 -> "4"
            ratio > 40 -> "5"
            else -> "6"
        }
    }

    private fun loadModelFile(modelName: String): java.nio.MappedByteBuffer {
        val fd = assets.openFd(modelName)
        return FileInputStream(fd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
    }

    private fun runInference(engineLoad: Float, throttle: Float, incline: Float, gear: Float): Float {
        if (tflite == null) {
            android.util.Log.e("AI_ERROR", "Inference aborted: Model is null")
            return -1f
        }
        val outputArr = Array(1) { FloatArray(1) }
        tflite?.run(arrayOf(floatArrayOf(engineLoad, throttle, incline, gear)), outputArr)
        return outputArr[0][0]
    }

    private fun generateHudCommand(currentRpm: Float, optimalRpm: Float, gear: Int): Int {
        // Calibrated 250 RPM threshold band to eliminate rapid signal oscillation[cite: 6, 9]
        val buffer = 250f
        return when {
            currentRpm > (optimalRpm + buffer) && gear < 6 -> 1
            currentRpm < (optimalRpm - buffer) && gear > 1 -> -1
            else -> 0
        }
    }

    private fun updateDashboardUi(command: Int) {
        when (command) {
            1 -> { txtShiftCue.text = "🔼 SHIFT UP"; txtShiftCue.setTextColor(getColor(android.R.color.holo_green_light)) }
            -1 -> { txtShiftCue.text = "🔽 SHIFT DOWN"; txtShiftCue.setTextColor(getColor(android.R.color.holo_red_light)) }
            0 -> { txtShiftCue.text = "🟢 HOLD"; txtShiftCue.setTextColor(getColor(android.R.color.holo_green_light)) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tflite?.close()
        if (::obdManager.isInitialized) obdManager.disconnect()
    }
}