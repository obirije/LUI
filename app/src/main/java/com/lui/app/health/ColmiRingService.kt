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
import java.io.ByteArrayOutputStream
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
        val CMD_BIG_DATA: Byte = 0xBC.toByte()
    }

    data class SleepData(
        val totalMinutes: Int = -1,
        val deepMinutes: Int = 0,
        val lightMinutes: Int = 0,
        val remMinutes: Int = 0,
        val awakeMinutes: Int = 0
    )

    enum class State { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

    private val _state = MutableStateFlow(State.DISCONNECTED)
    val state: StateFlow<State> = _state

    private val _batteryLevel = MutableStateFlow(-1)
    val batteryLevel: StateFlow<Int> = _batteryLevel

    private val _heartRate = MutableStateFlow(-1)
    val heartRate: StateFlow<Int> = _heartRate

    private val _deviceName = MutableStateFlow("")
    val deviceName: StateFlow<String> = _deviceName

    private val _spO2 = MutableStateFlow(-1)
    val spO2: StateFlow<Int> = _spO2

    private val _steps = MutableStateFlow(-1)
    val steps: StateFlow<Int> = _steps

    private val _calories = MutableStateFlow(-1)
    val calories: StateFlow<Int> = _calories

    private val _stress = MutableStateFlow(-1)
    val stress: StateFlow<Int> = _stress

    private val _hrv = MutableStateFlow(-1)
    val hrv: StateFlow<Int> = _hrv

    private val _temperature = MutableStateFlow(-1f)
    val temperature: StateFlow<Float> = _temperature

    private val _sleepData = MutableStateFlow(SleepData())
    val sleepData: StateFlow<SleepData> = _sleepData

    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private val handler = Handler(Looper.getMainLooper())
    private var scanner: BluetoothLeScanner? = null
    private var isRealtimeHrActive = false
    private var syncTimer: java.util.Timer? = null
    private var bigDataType: Byte = 0
    private var bigDataExpectedSize: Int = 0
    private val bigDataBuffer = ByteArrayOutputStream()

    // Tracks which real-time measurement type is pending (0x69 serves HR, SpO2, HRV, stress)
    // Reading types: 1=HR, 3=SpO2, 4=Fatigue/Stress, 10=HRV
    private var pendingReadingType: Int = 1

    /** Callback fired when any health metric is updated. (metric name, value) */
    var onReading: ((String, Float) -> Unit)? = null

    // ── Auto-connect ──

    /**
     * Silently connects to a previously paired Colmi ring if one exists.
     * Call on app start — no UI interaction needed.
     */
    @Suppress("MissingPermission")
    fun autoConnect(): Boolean {
        if (isConnected || _state.value == State.CONNECTING) return false
        if (!hasBluetoothPermission()) return false

        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
        try {
            for (device in adapter.bondedDevices) {
                val name = device.name ?: continue
                if (name.matches(Regex("(?:COLMI )?R\\d{2}_.*", RegexOption.IGNORE_CASE))) {
                    LuiLogger.i(TAG, "Auto-connecting to paired ring: $name")
                    connect(device)
                    return true
                }
            }
        } catch (e: SecurityException) {
            LuiLogger.w(TAG, "Can't check paired devices: ${e.message}")
        }
        return false
    }

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
        stopPeriodicSync()
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
        pendingReadingType = 1
        sendCommand(byteArrayOf(CMD_MANUAL_HR, 0x01, 0x01)) // type=HR, action=START
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

    fun requestActivity() {
        LuiLogger.i(TAG, "Requesting activity data...")
        // Reset step accumulator before syncing
        _steps.value = 0
        _calories.value = 0
        sendCommand(byteArrayOf(CMD_SYNC_ACTIVITY, 0x00, 0x0F, 0x00, 0x5F, 0x01))
    }

    fun requestSpO2() {
        LuiLogger.i(TAG, "Requesting SpO2...")
        pendingReadingType = 3
        sendCommand(byteArrayOf(CMD_MANUAL_HR, 0x03, 0x01)) // type=SpO2, action=START
    }

    fun requestStress() {
        LuiLogger.i(TAG, "Requesting stress level...")
        pendingReadingType = 4
        sendCommand(byteArrayOf(CMD_MANUAL_HR, 0x04, 0x01)) // type=Fatigue, action=START
    }

    fun requestHrv() {
        LuiLogger.i(TAG, "Requesting HRV...")
        pendingReadingType = 10
        sendCommand(byteArrayOf(CMD_MANUAL_HR, 0x0A, 0x01)) // type=HRV, action=START
    }

    fun requestSleep() {
        LuiLogger.i(TAG, "Requesting sleep data...")
        bigDataBuffer.reset()
        bigDataExpectedSize = 0
        sendCommand(byteArrayOf(CMD_BIG_DATA, 0x27))
    }

    fun requestTemperature() {
        LuiLogger.i(TAG, "Requesting temperature data...")
        bigDataBuffer.reset()
        bigDataExpectedSize = 0
        sendCommand(byteArrayOf(CMD_BIG_DATA, 0x25))
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

                // Post-connect: set time + request battery + start periodic sync
                handler.postDelayed({
                    setDateTime()
                    handler.postDelayed({ requestBattery() }, 500)
                    handler.postDelayed({ startPeriodicSync() }, 2000)
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
                val responseType = value[1].toInt() and 0xFF
                val errorCode = value[2].toInt() and 0xFF
                val reading = value[3].toInt() and 0xFF
                // Ring echoes reading type in byte[1]; fall back to tracked pending type
                val knownTypes = setOf(1, 2, 3, 4, 5, 7, 8, 9, 10)
                val type = if (responseType in knownTypes) responseType else pendingReadingType
                LuiLogger.i(TAG, "Real-time: type=$type (byte1=$responseType, pending=$pendingReadingType), error=$errorCode, value=$reading, raw=${value.toHex()}")
                when (errorCode) {
                    0 -> {
                        if (reading > 0) {
                            when (type) {
                                1 -> {
                                    _heartRate.value = reading
                                    LuiLogger.i(TAG, "Heart rate: $reading BPM")
                                    onReading?.invoke("heart_rate", reading.toFloat())
                                }
                                3 -> {
                                    _spO2.value = reading
                                    LuiLogger.i(TAG, "SpO2: $reading%")
                                    onReading?.invoke("spo2", reading.toFloat())
                                }
                                4 -> {
                                    _stress.value = reading
                                    LuiLogger.i(TAG, "Stress: $reading")
                                    onReading?.invoke("stress", reading.toFloat())
                                }
                                10 -> {
                                    _hrv.value = reading
                                    LuiLogger.i(TAG, "HRV: $reading ms")
                                    onReading?.invoke("hrv", reading.toFloat())
                                }
                                else -> LuiLogger.i(TAG, "Reading type $type: $reading")
                            }
                        }
                    }
                    1 -> LuiLogger.w(TAG, "Reading error: ring not worn correctly")
                    2 -> LuiLogger.d(TAG, "Temporary measurement error (type=$type)")
                    else -> LuiLogger.w(TAG, "Error code: $errorCode (type=$type)")
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
            CMD_SYNC_ACTIVITY -> {
                LuiLogger.d(TAG, "Activity packet: ${value.toHex()}")
                // colmi_r02_client: bytes[9-10]=steps(LE), bytes[7-8]=calories(LE), bytes[11-12]=distance(LE)
                // Multiple packets per day (15-min intervals), accumulate totals
                if (value.size >= 13) {
                    val steps = (value[9].toInt() and 0xFF) or ((value[10].toInt() and 0xFF) shl 8)
                    val cals = (value[7].toInt() and 0xFF) or ((value[8].toInt() and 0xFF) shl 8)
                    if (steps > 0) {
                        _steps.value = (_steps.value.coerceAtLeast(0)) + steps
                        _calories.value = (_calories.value.coerceAtLeast(0)) + cals
                        LuiLogger.i(TAG, "Activity: +$steps steps, +$cals cal → total ${_steps.value}")
                        onReading?.invoke("steps", _steps.value.toFloat())
                    }
                }
            }
            CMD_BIG_DATA -> handleBigDataResponse(value)
            CMD_FIND_DEVICE -> LuiLogger.i(TAG, "Find device acknowledged")
            CMD_SET_TIME -> LuiLogger.d(TAG, "Time set acknowledged")
            else -> LuiLogger.d(TAG, "Unknown response: cmd=0x${String.format("%02X", cmd)}")
        }
    }

    // ── Big data protocol (SpO2, sleep, temperature) ──

    private fun handleBigDataResponse(value: ByteArray) {
        LuiLogger.d(TAG, "Big data packet: ${value.toHex()}")
        if (value.size < 4) return
        val dataType = value[1]

        if (bigDataBuffer.size() == 0) {
            // First packet — extract header: [cmd, type, sizeLo, sizeHi, ...]
            bigDataType = dataType
            bigDataExpectedSize = (value[2].toInt() and 0xFF) or ((value[3].toInt() and 0xFF) shl 8)
            LuiLogger.i(TAG, "Big data start: type=0x${String.format("%02X", dataType)}, expected=$bigDataExpectedSize bytes")
            if (bigDataExpectedSize == 0) {
                // No data available on ring for this metric
                LuiLogger.w(TAG, "Ring reports 0 bytes for type 0x${String.format("%02X", dataType)}")
                return
            }
            if (value.size > 6) bigDataBuffer.write(value, 6, value.size - 6)
        } else {
            // Continuation — append payload after [cmd, type]
            if (value.size > 2) bigDataBuffer.write(value, 2, value.size - 2)
        }

        if (bigDataExpectedSize > 0 && bigDataBuffer.size() >= bigDataExpectedSize) {
            val data = bigDataBuffer.toByteArray()
            bigDataBuffer.reset()
            bigDataExpectedSize = 0
            LuiLogger.i(TAG, "Big data complete: ${data.size} bytes")
            parseBigData(bigDataType, data)
        }
    }

    private fun parseBigData(type: Byte, data: ByteArray) {
        LuiLogger.i(TAG, "Parsing big data type=0x${String.format("%02X", type)}, ${data.size} bytes")
        when (type) {
            0x2A.toByte() -> parseSpO2Data(data)
            0x27.toByte() -> parseSleepStages(data)
            0x25.toByte() -> parseTemperatureData(data)
            else -> LuiLogger.w(TAG, "Unknown big data type: 0x${String.format("%02X", type)}")
        }
    }

    private fun parseSpO2Data(data: ByteArray) {
        // Scan for latest valid SpO2 percentage (typically 80-100%)
        for (i in (data.size - 1) downTo 0) {
            val v = data[i].toInt() and 0xFF
            if (v in 80..100) {
                _spO2.value = v
                LuiLogger.i(TAG, "SpO2: $v%")
                return
            }
        }
        LuiLogger.w(TAG, "No valid SpO2 reading in ${data.size} bytes")
    }

    private fun parseSleepStages(data: ByteArray) {
        // Sleep records: [stageType, durationMinutes] pairs
        // Stage types: 0x02=light, 0x03=deep, 0x04=REM, 0x05=awake
        var deep = 0; var light = 0; var rem = 0; var awake = 0; var found = false
        var i = 0
        while (i < data.size - 1) {
            val stage = data[i].toInt() and 0xFF
            val mins = data[i + 1].toInt() and 0xFF
            when (stage) {
                0x02 -> { light += mins; found = true }
                0x03 -> { deep += mins; found = true }
                0x04 -> { rem += mins; found = true }
                0x05 -> { awake += mins; found = true }
            }
            i += 2
        }
        if (found) {
            val total = deep + light + rem + awake
            _sleepData.value = SleepData(total, deep, light, rem, awake)
            LuiLogger.i(TAG, "Sleep: ${total}min total (deep=$deep, light=$light, rem=$rem, awake=$awake)")
            // Persist each metric so get_health_trend can query
            onReading?.invoke("sleep_total", total.toFloat())
            onReading?.invoke("sleep_deep", deep.toFloat())
            onReading?.invoke("sleep_light", light.toFloat())
            onReading?.invoke("sleep_rem", rem.toFloat())
            onReading?.invoke("sleep_awake", awake.toFloat())
        } else {
            LuiLogger.w(TAG, "No valid sleep stages in ${data.size} bytes")
        }
    }

    private fun parseTemperatureData(data: ByteArray) {
        // Temperature as value × 10 (e.g. 367 = 36.7°C), 2 bytes LE
        for (i in 0 until data.size - 1) {
            val raw = (data[i].toInt() and 0xFF) or ((data[i + 1].toInt() and 0xFF) shl 8)
            val temp = raw / 10f
            if (temp in 34f..42f) {
                _temperature.value = temp
                LuiLogger.i(TAG, "Temperature: ${temp}°C")
                onReading?.invoke("temperature", temp)
                return
            }
        }
        LuiLogger.w(TAG, "No valid temperature in ${data.size} bytes")
    }

    // ── Periodic background sync ──

    private fun startPeriodicSync() {
        stopPeriodicSync()
        LuiLogger.i(TAG, "Starting periodic health sync (every 15 min)")
        syncTimer = java.util.Timer("RingSync", true).apply {
            // First sync 5s after connect, then every 15 minutes
            schedule(object : java.util.TimerTask() {
                override fun run() { runSyncCycle() }
            }, 5000L, 15 * 60 * 1000L)
        }
    }

    private fun stopPeriodicSync() {
        syncTimer?.cancel()
        syncTimer = null
    }

    private fun runSyncCycle() {
        if (!isConnected) return
        LuiLogger.i(TAG, "Background sync: starting cycle")

        // Battery first (instant)
        requestBattery()

        // Then cycle through real-time metrics with delays so they don't collide
        // Each measurement needs ~30s. Space them 35s apart.
        handler.postDelayed({
            if (isConnected) requestManualHeartRate()
        }, 2000)

        handler.postDelayed({
            if (isConnected) requestSpO2()
        }, 35000)

        handler.postDelayed({
            if (isConnected) requestStress()
        }, 70000)

        handler.postDelayed({
            if (isConnected) requestHrv()
        }, 105000)

        // Activity sync (instant, no PPG)
        handler.postDelayed({
            if (isConnected) requestActivity()
        }, 140000)

        // Temperature via big data — retry once if first attempt yields nothing
        handler.postDelayed({
            if (isConnected) requestTemperature()
        }, 150000)
        handler.postDelayed({
            if (isConnected && _temperature.value <= 0f) requestTemperature()
        }, 180000)

        // Sleep via big data (last night's data)
        handler.postDelayed({
            if (isConnected) requestSleep()
        }, 210000)
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
