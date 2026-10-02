package com.example.sih

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

@SuppressLint("MissingPermission")
class OBDConnectionManager(private val targetMacAddress: String) {

    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var bluetoothSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var isPolling = false

    fun connectAndStartPolling(onDataReceived: (Float, Float, Float) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val adapter = BluetoothAdapter.getDefaultAdapter()
                adapter?.cancelDiscovery()

                val device: BluetoothDevice = adapter.getRemoteDevice(targetMacAddress)
                bluetoothSocket = establishSocketConnection(device)

                inputStream = bluetoothSocket?.inputStream
                outputStream = bluetoothSocket?.outputStream
                isPolling = true

                delay(500)
                sendCommand("ATZ\r")
                delay(1000)
                sendCommand("ATE0\r")
                sendCommand("ATSP0\r")

                while (isPolling) {
                    val rpm = requestTelemetry("010C\r")
                    val load = requestTelemetry("0104\r")
                    val speed = requestTelemetry("010D\r")

                    withContext(Dispatchers.Main) { onDataReceived(rpm, load, speed) }
                    delay(100)
                }
            } catch (e: Exception) {
                Log.e("OBD_SYS", "Connection failed: ${e.message}")
            }
        }
    }

    private fun establishSocketConnection(device: BluetoothDevice): BluetoothSocket {
        try {
            val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
            val socket = method.invoke(device, 1) as BluetoothSocket
            socket.connect()
            return socket
        } catch (e: Exception) { }
        try {
            val socket = device.createInsecureRfcommSocketToServiceRecord(sppUuid)
            socket.connect()
            return socket
        } catch (e: Exception) { }
        val socket = device.createRfcommSocketToServiceRecord(sppUuid)
        socket.connect()
        return socket
    }

    private fun sendCommand(cmd: String) {
        try {
            // 🔴 CRITICAL FIX: Flush stale bytes in the input stream before sending a new command
            while ((inputStream?.available() ?: 0) > 0) {
                inputStream?.read()
            }
            outputStream?.write(cmd.toByteArray())
            outputStream?.flush()
        } catch (e: Exception) { }
    }

    private fun requestTelemetry(pid: String): Float {
        sendCommand(pid)
        // Give the ELM327 microcontroller time to write the clean response
        Thread.sleep(30)
        val buffer = ByteArray(1024)
        val bytesRead = try { inputStream?.read(buffer) ?: 0 } catch (e: Exception) { return 0f }
        return parseHexResponse(String(buffer, 0, bytesRead).replace(" ", "").trim())
    }

    // 🔴 CRITICAL FIX: Validate the strict response header (410C, 4104, 410D)
    private fun parseHexResponse(hexStr: String): Float {
        if (!hexStr.contains("41")) return 0f
        return try {
            val cleanHex = hexStr.substring(hexStr.indexOf("41"))
            when {
                cleanHex.startsWith("410C") && cleanHex.length >= 8 -> {
                    (((cleanHex.substring(4, 6).toInt(16) * 256f) + cleanHex.substring(6, 8).toInt(16)) / 4f)
                }
                cleanHex.startsWith("4104") && cleanHex.length >= 6 -> {
                    (cleanHex.substring(4, 6).toInt(16) * 100f) / 255f
                }
                cleanHex.startsWith("410D") && cleanHex.length >= 6 -> {
                    cleanHex.substring(4, 6).toInt(16).toFloat()
                }
                else -> 0f
            }
        } catch (e: Exception) { 0f }
    }

    fun disconnect() {
        isPolling = false
        try { bluetoothSocket?.close() } catch (e: Exception) { }
    }
}