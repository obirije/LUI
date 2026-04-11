package com.lui.app.health

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.lui.app.helper.LuiLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * BLE client for Colmi smart rings (R02/R03/R06/R07/R09/R10/R12).
 *
 * Protocol: 16-byte packets over Nordic UART Service.
 * Service:  6e40fff0-b5a3-f393-e0a9-e50e24dcca9e
 * Write:    6e400002-b5a3-f393-e0a9-e50e24dcca9e
 * Notify:   6e400003-b5a3-f393-e0a9-e50e24dcca9e
 *
 * No bonding, no encryption, no pairing PIN.
 */
class ColmiRingService(private val context: Context) {

    companion object {
        private const val TAG = "ColmiRing"

        val SERVICE_UUID: UUID = UUID.fromString("6e40fff0-b5a3-f393-e0a9-e50e24dcca9e")
        val WRITE_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        val NOTIFY_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Command bytes
        const val CMD_BATTERY: Byte = 0x03
        const val CMD_SET_TIME: Byte = 0x01
        const val CMD_SYNC_HR: Byte = 0x15
        const val CMD_REALTIME_HR: Byte = 0x1E
        const val CMD_MANUAL_HR: Byte = 0x69
        const val CMD_SYNC_ACTIVITY: Byte = 0x43
        const val CMD_SYNC_STRESS: Byte = 0x37
        const val CMD_SYNC_HRV: Byte = 0x39
        const val CMD_FIND_DEVICE: Byte = 0x50
        const val CMD_NOTIFICATION: Byte = 0x73
    }

    enum class State { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

    private val _state = MutableStateFlow(State.DISCONNECTED)
    val state: StateFlow<State> = _state

    private val _batteryLevel = MutableStateFlow(-1)
    val batteryLevel: StateFlow<Int> = _batteryLevel

    private val _heartRate = MutableStateFlow(-1)
    val heartRate: StateFlow<Int> = _heartRate

    private val _deviceName = MutableStateFlow("")
    val deviceName: StateFlow<String> = _deviceName

    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private val handler = Handler(Looper.getMainLooper())
    private var scanner: BluetoothLeScanner? = null
    private var isRealtimeHrActive = false

    // ── Scanning ──

    @Suppress("MissingPermission")
    fun scan(onFound: (BluetoothDevice, String) -> Unit) {
        if (!hasBluetoothPermission()) {
            LuiLogger.w(TAG, "Missing Bluetooth permission")
            return
        }

        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return

        // Check already-paired devices first
        try {
            val bonded = adapter.bondedDevices
            for (device in bonded) {
                val name = device.name ?: continue
                if (name.matches(Regex("(?:COLMI )?R\\d{2}_.*", RegexOption.IGNORE_CASE))) {
                    LuiLogger.i(TAG, "Found paired ring: $name (${device.address})")
                    onFound(device, name)
                    return
                }
            }
        } catch (e: SecurityException) {
            LuiLogger.w(TAG, "Can't check paired devices: ${e.message}")
        }

        // No paired ring found — do BLE scan
        scanner = adapter.bluetoothLeScanner ?: return
        _state.value = State.SCANNING

        LuiLogger.i(TAG, "Scanning for Colmi rings...")

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: return
                if (name.matches(Regex("(?:COLMI )?R\\d{2}_.*", RegexOption.IGNORE_CASE))) {
                    LuiLogger.i(TAG, "Found ring via scan: $name (${result.device.address})")
                    stopScan()
                    onFound(result.device, name)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                LuiLogger.e(TAG, "Scan failed: $errorCode")
                _state.value = State.DISCONNECTED
            }
        }

        scanner?.startScan(callback)

        // Auto-stop after 15 seconds
        handler.postDelayed({
            stopScan()
            if (_state.value == State.SCANNING) {
                LuiLogger.w(TAG, "Scan timeout — no ring found")
                _state.value = State.DISCONNECTED
            }
        }, 15000)
    }

    @Suppress("MissingPermission")
    private fun stopScan() {
        try { scanner?.stopScan(object : ScanCallback() {}) } catch (_: Exception) {}
    }

    // ── Connection ──

    @Suppress("MissingPermission")
    fun connect(device: BluetoothDevice) {
        _state.value = State.CONNECTING
        _deviceName.value = device.name ?: "Colmi Ring"
        LuiLogger.i(TAG, "Connecting to ${device.name}...")

        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @Suppress("MissingPermission")
    fun disconnect() {
        isRealtimeHrActive = false
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        writeCharacteristic = null
        _state.value = State.DISCONNECTED
        LuiLogger.i(TAG, "Disconnected")
    }

    val isConnected: Boolean get() = _state.value == State.CONNECTED

    // ── Commands ──

    fun requestBattery() = sendCommand(byteArrayOf(CMD_BATTERY))

    fun requestManualHeartRate() {
        LuiLogger.i(TAG, "Requesting manual heart rate...")
        sendCommand(byteArrayOf(CMD_MANUAL_HR, 0x01))
    }

    fun startRealtimeHeartRate() {
        LuiLogger.i(TAG, "Starting real-time heart rate...")
        isRealtimeHrActive = true
        sendCommand(byteArrayOf(CMD_REALTIME_HR, 0x01))
    }

    fun stopRealtimeHeartRate() {
        isRealtimeHrActive = false
        sendCommand(byteArrayOf(CMD_REALTIME_HR, 0x02))
    }

    fun findDevice() {
        LuiLogger.i(TAG, "Finding device (ring vibrates)...")
        sendCommand(byteArrayOf(CMD_FIND_DEVICE, 0x55, 0xAA.toByte()))
    }

    fun syncActivity(daysAgo: Int = 0) {
        sendCommand(byteArrayOf(CMD_SYNC_ACTIVITY, daysAgo.toByte(), 0x0F, 0x00, 0x5F, 0x01))
    }

    // ── Packet building ──

    @Suppress("MissingPermission")
    private fun sendCommand(contents: ByteArray) {
        val char = writeCharacteristic ?: return
        val gatt = bluetoothGatt ?: return

        val packet = buildPacket(contents)
        char.value = packet
        gatt.writeCharacteristic(char)
        LuiLogger.d(TAG, "Sent: ${packet.toHex()}")
    }

    private fun buildPacket(contents: ByteArray): ByteArray {
        val packet = ByteArray(16)
        contents.copyInto(packet, 0, 0, minOf(contents.size, 15))
        var checksum = 0
        for (i in 0..14) checksum = (checksum + packet[i]) and 0xFF
        packet[15] = checksum.toByte()
        return packet
    }

    // ── GATT Callback ──

    private val gattCallback = object : BluetoothGattCallback() {

        @Suppress("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    LuiLogger.i(TAG, "Connected to GATT, discovering services...")
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    LuiLogger.i(TAG, "Disconnected from GATT")
                    _state.value = State.DISCONNECTED
                    writeCharacteristic = null
                }
            }
        }

        @Suppress("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                LuiLogger.e(TAG, "Service discovery failed: $status")
                return
            }

            val service = gatt.getService(SERVICE_UUID)
            if (service == null) {
                LuiLogger.e(TAG, "Nordic UART service not found")
                return
            }

            writeCharacteristic = service.getCharacteristic(WRITE_UUID)
            val notifyChar = service.getCharacteristic(NOTIFY_UUID)

            if (writeCharacteristic == null || notifyChar == null) {
                LuiLogger.e(TAG, "Required characteristics not found")
                return
            }

            // Enable notifications
            gatt.setCharacteristicNotification(notifyChar, true)
            val cccd = notifyChar.getDescriptor(CCCD_UUID)
            cccd?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(cccd)

            LuiLogger.i(TAG, "Services discovered, notifications enabled")
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _state.value = State.CONNECTED
                LuiLogger.i(TAG, "Ring connected and ready")

                // Post-connect: set time + request battery
                handler.postDelayed({
                    setDateTime()
                    handler.postDelayed({ requestBattery() }, 500)
                }, 1000)
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.value ?: return
            if (value.isEmpty()) return
            handleResponse(value)
        }
    }

    // ── Response handling ──

    private fun handleResponse(value: ByteArray) {
        val cmd = value[0]
        LuiLogger.d(TAG, "Response: ${value.toHex()}")

        when (cmd) {
            CMD_BATTERY -> {
                val level = value[1].toInt() and 0xFF
                val charging = value[2].toInt() == 1
                _batteryLevel.value = level
                LuiLogger.i(TAG, "Battery: $level% (charging: $charging)")
            }
            CMD_MANUAL_HR -> {
                val errorCode = value[2].toInt() and 0xFF
                val bpm = value[3].toInt() and 0xFF
                when (errorCode) {
                    0 -> {
                        if (bpm > 0) {
                            _heartRate.value = bpm
                            LuiLogger.i(TAG, "Heart rate: $bpm BPM")
                        }
                    }
                    1 -> LuiLogger.w(TAG, "HR error: ring not worn correctly")
                    2 -> LuiLogger.d(TAG, "HR: temporary measurement error")
                    else -> LuiLogger.w(TAG, "HR error code: $errorCode")
                }
            }
            CMD_REALTIME_HR -> {
                val bpm = value[1].toInt() and 0xFF
                if (bpm > 0) {
                    _heartRate.value = bpm
                    LuiLogger.d(TAG, "Real-time HR: $bpm BPM")
                }
                // Continue real-time if active (send keep-alive every 30 readings)
                if (isRealtimeHrActive) {
                    sendCommand(byteArrayOf(CMD_REALTIME_HR, 0x03))
                }
            }
            CMD_NOTIFICATION -> {
                val type = value[1]
                when (type) {
                    0x01.toByte() -> LuiLogger.i(TAG, "New HR data available")
                    0x03.toByte() -> LuiLogger.i(TAG, "New SpO2 data available")
                    0x04.toByte() -> LuiLogger.i(TAG, "New steps data available")
                    0x0C.toByte() -> {
                        val level = value[2].toInt() and 0xFF
                        val charging = value[3].toInt() == 1
                        _batteryLevel.value = level
                        LuiLogger.i(TAG, "Battery notification: $level% (charging: $charging)")
                    }
                }
            }
            CMD_FIND_DEVICE -> LuiLogger.i(TAG, "Find device acknowledged")
            CMD_SET_TIME -> LuiLogger.d(TAG, "Time set acknowledged")
            else -> LuiLogger.d(TAG, "Unknown response: cmd=0x${String.format("%02X", cmd)}")
        }
    }

    // ── Helpers ──

    private fun setDateTime() {
        val cal = java.util.Calendar.getInstance()
        sendCommand(byteArrayOf(
            CMD_SET_TIME,
            (cal.get(java.util.Calendar.YEAR) % 100).toByte(),
            (cal.get(java.util.Calendar.MONTH) + 1).toByte(),
            cal.get(java.util.Calendar.DAY_OF_MONTH).toByte(),
            cal.get(java.util.Calendar.HOUR_OF_DAY).toByte(),
            cal.get(java.util.Calendar.MINUTE).toByte(),
            cal.get(java.util.Calendar.SECOND).toByte(),
            (cal.get(java.util.Calendar.DAY_OF_WEEK) - 1).toByte()
        ))
    }

    private fun hasBluetoothPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    private fun ByteArray.toHex(): String = joinToString(" ") { String.format("%02X", it) }
}
