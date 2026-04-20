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
 * Two protocols, two services:
 *   1. **Commands** — 16-byte fixed packets with XOR checksum over Nordic UART.
 *      Used for real-time HR/SpO2/HRV/stress, battery, steps, time, find.
 *   2. **Big Data** — variable-length packets over a separate custom service.
 *      Used for historical sleep, SpO2 history, temperature. Multi-packet
 *      responses reassembled using a length field in the first packet.
 *
 *   Commands service: 6e40fff0-b5a3-f393-e0a9-e50e24dcca9e
 *   Big Data service: de5bf728-d711-4e47-af26-65e3012a5dc7
 *
 * No bonding, no encryption, no pairing PIN.
 */
class ColmiRingService(private val context: Context) {

    companion object {
        private const val TAG = "ColmiRing"

        // Commands service (Nordic UART) — 16-byte packets, XOR checksum
        val SERVICE_UUID: UUID = UUID.fromString("6e40fff0-b5a3-f393-e0a9-e50e24dcca9e")
        val WRITE_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        val NOTIFY_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Big Data service — variable-length packets
        val BIG_DATA_SERVICE_UUID: UUID = UUID.fromString("de5bf728-d711-4e47-af26-65e3012a5dc7")
        val BIG_DATA_WRITE_UUID: UUID = UUID.fromString("de5bf72a-d711-4e47-af26-65e3012a5dc7")
        val BIG_DATA_NOTIFY_UUID: UUID = UUID.fromString("de5bf729-d711-4e47-af26-65e3012a5dc7")

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
        // Gesture events from the ring's motion detector. Subtype in byte[1].
        const val CMD_GESTURE: Byte = 0x2F
        const val GESTURE_DOUBLE_TAP: Byte = 0xF4.toByte()
        val CMD_BIG_DATA: Byte = 0xBC.toByte()

        // Big Data dataIds (Puxtril/colmi-docs)
        const val BD_ID_SLEEP: Byte = 0x27
        const val BD_ID_SPO2_HISTORY: Byte = 0x2A
        // Temperature dataId varies by firmware; we probe a few.
        val BD_ID_TEMP_CANDIDATES: List<Byte> = listOf(0x25, 0x28, 0x2B)
    }

    data class SleepData(
        val totalMinutes: Int = -1,
        val deepMinutes: Int = 0,
        val lightMinutes: Int = 0,
        val remMinutes: Int = 0,
        val awakeMinutes: Int = 0,
        /** Ordered stage timeline: (stageType 0x02=light / 0x03=deep / 0x04=REM / 0x05=awake, minutes).
         *  Empty if the ring only returned aggregate totals. */
        val stages: List<Pair<Int, Int>> = emptyList()
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
    private var bigDataWriteChar: BluetoothGattCharacteristic? = null
    private var bigDataNotifyChar: BluetoothGattCharacteristic? = null
    private val handler = Handler(Looper.getMainLooper())
    private var scanner: BluetoothLeScanner? = null
    private var isRealtimeHrActive = false
    private var syncTimer: java.util.Timer? = null
    private var bigDataType: Byte = 0
    private var bigDataExpectedSize: Int = 0
    private val bigDataBuffer = ByteArrayOutputStream()

    /** Populated from SetTime response byte[1]. `false` means this firmware
     *  physically can't report skin temperature — no point asking. */
    @Volatile private var supportsTemperature: Boolean = true

    /** Once a temperature dataId succeeds, remember it so we don't probe again. */
    @Volatile private var knownTempDataId: Byte? = null

    // Big-data requests (sleep, temperature, SpO2 history) share one
    // accumulator buffer, so we serialize them. Two concurrent requests would
    // interleave packets and corrupt both readings.
    private data class BigDataRequest(val type: Byte, val metric: String)
    private val bigDataQueue = ArrayDeque<BigDataRequest>()
    private var bigDataInFlight = false
    private var bigDataStartedAt = 0L
    private val bigDataTimeoutRunnable = Runnable { onBigDataTimeout() }

    // Last-successful-read timestamps per metric (epoch millis). Observed by
    // the UI so the user can see "HR: 62 BPM, 3 min ago" and spot stale reads.
    private val _lastReadAt = MutableStateFlow<Map<String, Long>>(emptyMap())
    val lastReadAt: StateFlow<Map<String, Long>> = _lastReadAt

    private fun markRead(metric: String) {
        _lastReadAt.value = _lastReadAt.value + (metric to System.currentTimeMillis())
    }

    // Tracks which real-time measurement type is pending (0x69 serves HR, SpO2, HRV, stress)
    // Reading types: 1=HR, 3=SpO2, 4=Fatigue/Stress, 10=HRV
    private var pendingReadingType: Int = 1

    /** Callback fired when any health metric is updated. (metric name, value) */
    var onReading: ((String, Float) -> Unit)? = null

    /** Callback fired when the ring emits a motion gesture (e.g. "double_tap"). */
    var onGesture: ((String) -> Unit)? = null

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
        clearBigDataQueue()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        writeCharacteristic = null
        _state.value = State.DISCONNECTED
        LuiLogger.i(TAG, "Disconnected")
    }

    private fun clearBigDataQueue() {
        handler.removeCallbacks(bigDataTimeoutRunnable)
        bigDataQueue.clear()
        bigDataInFlight = false
        bigDataBuffer.reset()
        bigDataExpectedSize = 0
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
        enqueueBigData(BigDataRequest(BD_ID_SLEEP, "sleep"))
    }

    /**
     * Request temperature data. R09 and some earlier firmwares don't expose
     * temperature — we short-circuit if `supportsTemperature` is false. When
     * we haven't probed yet, try each candidate dataId one at a time.
     */
    fun requestTemperature() {
        if (!supportsTemperature) {
            LuiLogger.d(TAG, "Temperature request skipped — firmware reports unsupported")
            return
        }
        val id = knownTempDataId ?: BD_ID_TEMP_CANDIDATES.first()
        enqueueBigData(BigDataRequest(id, "temperature"))
    }

    /** Request all SpO2 historical data. */
    fun requestSpO2History() {
        enqueueBigData(BigDataRequest(BD_ID_SPO2_HISTORY, "spo2_history"))
    }

    private fun enqueueBigData(req: BigDataRequest) {
        // De-dup: don't queue a second request for the same metric that's
        // already queued or in flight.
        if (bigDataInFlight && bigDataType == req.type) return
        if (bigDataQueue.any { it.type == req.type }) return
        bigDataQueue.addLast(req)
        LuiLogger.i(TAG, "Big data queued: ${req.metric} (queue size=${bigDataQueue.size})")
        dispatchNextBigData()
    }

    private fun dispatchNextBigData() {
        if (bigDataInFlight) return
        if (!isConnected) { bigDataQueue.clear(); return }
        val next = bigDataQueue.removeFirstOrNull() ?: return
        if (bigDataWriteChar == null) {
            LuiLogger.w(TAG, "Big Data write char unavailable — dropping ${next.metric}")
            handler.postDelayed({ dispatchNextBigData() }, 1000L)
            return
        }
        bigDataInFlight = true
        bigDataStartedAt = System.currentTimeMillis()
        bigDataBuffer.reset()
        bigDataExpectedSize = 0
        bigDataType = next.type
        LuiLogger.i(TAG, "Requesting ${next.metric} data (dataId=0x${String.format("%02X", next.type)})")
        sendBigDataRequest(next.type)
        // Safety timeout: if packets stop arriving, unblock the queue.
        handler.postDelayed(bigDataTimeoutRunnable, 20_000L)
    }

    /**
     * Build and send a Big Data request on the Big Data write characteristic.
     * Format (6 bytes, variable-length — NOT the Commands-channel 16-byte
     * fixed framing with XOR checksum):
     *   [0]   = 0xBC magic
     *   [1]   = dataId
     *   [2-3] = dataLen (little-endian)  — 0 for "give me everything"
     *   [4-5] = crc16 (little-endian)    — 0xFFFF sentinel for empty payload
     */
    @Suppress("MissingPermission")
    private fun sendBigDataRequest(dataId: Byte) {
        val char = bigDataWriteChar ?: return
        val gatt = bluetoothGatt ?: return
        val packet = byteArrayOf(
            CMD_BIG_DATA,
            dataId,
            0x00, 0x00,                    // dataLen = 0
            0xFF.toByte(), 0xFF.toByte()   // crc16 sentinel
        )
        char.value = packet
        gatt.writeCharacteristic(char)
        LuiLogger.d(TAG, "BD sent: ${packet.toHex()}")
    }

    private fun onBigDataComplete(success: Boolean) {
        handler.removeCallbacks(bigDataTimeoutRunnable)
        bigDataInFlight = false
        bigDataBuffer.reset()
        bigDataExpectedSize = 0
        // Gap so the ring has a moment before the next BLE write.
        handler.postDelayed({ dispatchNextBigData() }, 3000L)
    }

    private fun onBigDataTimeout() {
        if (!bigDataInFlight) return
        LuiLogger.w(TAG, "Big data timeout (type=0x${String.format("%02X", bigDataType)})")
        val type = bigDataType
        onBigDataComplete(success = false)
        if (type in BD_ID_TEMP_CANDIDATES && knownTempDataId == null) probeNextTemperatureId(type)
    }

    /**
     * When temperature is supposedly supported but a given dataId returned
     * 0 bytes or timed out, queue the next candidate. After exhausting the
     * list, stop trying this session.
     */
    private fun probeNextTemperatureId(failed: Byte) {
        val idx = BD_ID_TEMP_CANDIDATES.indexOf(failed)
        val next = BD_ID_TEMP_CANDIDATES.getOrNull(idx + 1)
        if (next == null) {
            LuiLogger.w(TAG, "Temperature probe exhausted — no dataId worked")
            supportsTemperature = false
            return
        }
        LuiLogger.i(TAG, "Temperature probe: trying next dataId 0x${String.format("%02X", next)}")
        enqueueBigData(BigDataRequest(next, "temperature"))
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
                    bigDataWriteChar = null
                    bigDataNotifyChar = null
                    clearBigDataQueue()
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

            // Enable notifications on the Commands channel.
            gatt.setCharacteristicNotification(notifyChar, true)
            val cccd = notifyChar.getDescriptor(CCCD_UUID)
            cccd?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(cccd)

            // Find the Big Data service — separate from Nordic UART. Rings
            // without this service (older firmwares?) can't deliver
            // sleep/temperature; we just keep bigDataWriteChar null and
            // skip those requests.
            val bdService = gatt.getService(BIG_DATA_SERVICE_UUID)
            if (bdService != null) {
                bigDataWriteChar = bdService.getCharacteristic(BIG_DATA_WRITE_UUID)
                bigDataNotifyChar = bdService.getCharacteristic(BIG_DATA_NOTIFY_UUID)
                if (bigDataNotifyChar != null) {
                    gatt.setCharacteristicNotification(bigDataNotifyChar, true)
                    val bdCccd = bigDataNotifyChar!!.getDescriptor(CCCD_UUID)
                    bdCccd?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    // Queue — one descriptor write at a time. Commands-channel
                    // CCCD write is already in flight above; post after it
                    // completes via onDescriptorWrite.
                }
                LuiLogger.i(TAG, "Big Data service found (write=${bigDataWriteChar != null}, notify=${bigDataNotifyChar != null})")
            } else {
                LuiLogger.w(TAG, "Big Data service not found — sleep/temperature unavailable")
            }

            LuiLogger.i(TAG, "Services discovered, notifications enabled")
        }

        @Suppress("MissingPermission")
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return

            val writtenChar = descriptor.characteristic
            // First CCCD write was on the Commands notify channel. Chain the
            // Big Data notify subscription before we mark connected.
            if (writtenChar?.uuid == NOTIFY_UUID && bigDataNotifyChar != null) {
                val bdCccd = bigDataNotifyChar!!.getDescriptor(CCCD_UUID)
                if (bdCccd != null) {
                    bdCccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(bdCccd)
                    return // wait for this descriptor's callback
                }
            }

            _state.value = State.CONNECTED
            LuiLogger.i(TAG, "Ring connected and ready")

            // Post-connect: set time + request battery + start periodic sync
            handler.postDelayed({
                setDateTime()
                handler.postDelayed({ requestBattery() }, 500)
                handler.postDelayed({ startPeriodicSync() }, 2000)
            }, 1000)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.value ?: return
            if (value.isEmpty()) return
            // Route by channel: Commands (Nordic UART) vs Big Data.
            when (characteristic.uuid) {
                BIG_DATA_NOTIFY_UUID -> handleBigDataResponse(value)
                else -> handleResponse(value)
            }
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
                markRead("battery")
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
                                    markRead("heart_rate")
                                    LuiLogger.i(TAG, "Heart rate: $reading BPM")
                                    onReading?.invoke("heart_rate", reading.toFloat())
                                }
                                3 -> {
                                    _spO2.value = reading
                                    markRead("spo2")
                                    LuiLogger.i(TAG, "SpO2: $reading%")
                                    onReading?.invoke("spo2", reading.toFloat())
                                }
                                4 -> {
                                    _stress.value = reading
                                    markRead("stress")
                                    LuiLogger.i(TAG, "Stress: $reading")
                                    onReading?.invoke("stress", reading.toFloat())
                                }
                                10 -> {
                                    _hrv.value = reading
                                    markRead("hrv")
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
                        markRead("steps")
                        LuiLogger.i(TAG, "Activity: +$steps steps, +$cals cal → total ${_steps.value}")
                        onReading?.invoke("steps", _steps.value.toFloat())
                    }
                }
            }
            CMD_BIG_DATA -> {
                // Stray BC packets on the Commands channel shouldn't happen
                // post-fix — BD responses come on the BigData notify char now.
                // Log for diagnosis.
                LuiLogger.d(TAG, "BD response on Commands channel (ignored): ${value.toHex()}")
            }
            CMD_FIND_DEVICE -> LuiLogger.i(TAG, "Find device acknowledged")
            CMD_GESTURE -> {
                val subtype = if (value.size > 1) value[1] else 0
                val gesture = when (subtype) {
                    GESTURE_DOUBLE_TAP -> "double_tap"
                    else -> "gesture_0x${String.format("%02X", subtype)}"
                }
                LuiLogger.i(TAG, "Gesture: $gesture (subtype=0x${String.format("%02X", subtype)})")
                onGesture?.invoke(gesture)
            }
            CMD_SET_TIME -> {
                // Puxtril docs: byte[1] of the SetTime ACK is supportsTemperature.
                // Check before asking for temperature so we don't probe firmwares
                // that physically can't deliver it.
                if (value.size > 1) {
                    val flag = value[1].toInt() and 0xFF
                    supportsTemperature = flag != 0
                    LuiLogger.i(TAG, "Ring supportsTemperature=$supportsTemperature (flag=$flag)")
                }
            }
            else -> LuiLogger.d(TAG, "Unknown response: cmd=0x${String.format("%02X", cmd)}")
        }
    }

    // ── Big data protocol (SpO2, sleep, temperature) ──

    /**
     * Parse Big Data notifications. Format (Puxtril/colmi-docs, QRing):
     *   First packet: [0]=0xBC, [1]=dataId, [2..3]=dataLen (LE),
     *                 [4..5]=crc16 (LE), [6..]=payload chunk.
     *   Continuation packets: raw payload (no header). Reassemble until
     *   buffer size reaches dataLen.
     */
    private fun handleBigDataResponse(value: ByteArray) {
        LuiLogger.d(TAG, "BD packet: ${value.toHex()}")
        if (value.isEmpty()) return

        if (bigDataBuffer.size() == 0) {
            // First packet. Spec requires the 0xBC magic at [0].
            if (value.size < 6 || value[0] != CMD_BIG_DATA) {
                LuiLogger.w(TAG, "BD first packet malformed: ${value.toHex()}")
                return
            }
            val dataType = value[1]
            bigDataType = dataType
            bigDataExpectedSize = (value[2].toInt() and 0xFF) or ((value[3].toInt() and 0xFF) shl 8)
            LuiLogger.i(TAG, "BD start: type=0x${String.format("%02X", dataType)}, expected=$bigDataExpectedSize bytes")
            if (bigDataExpectedSize == 0) {
                // Ring has no data for this dataId — may be unsupported or just empty.
                // For temperature: try the next candidate dataId before giving up.
                LuiLogger.w(TAG, "Ring reports 0 bytes for 0x${String.format("%02X", dataType)}")
                val metric = if (dataType == BD_ID_SLEEP) "sleep"
                    else if (dataType == BD_ID_SPO2_HISTORY) "spo2_history"
                    else "temperature"
                onBigDataComplete(success = false)
                if (metric == "temperature" && knownTempDataId == null) probeNextTemperatureId(dataType)
                return
            }
            if (value.size > 6) bigDataBuffer.write(value, 6, value.size - 6)
        } else {
            // Continuation — entire packet is raw payload.
            bigDataBuffer.write(value, 0, value.size)
        }

        if (bigDataExpectedSize > 0 && bigDataBuffer.size() >= bigDataExpectedSize) {
            val data = bigDataBuffer.toByteArray()
            val type = bigDataType
            LuiLogger.i(TAG, "BD complete: ${data.size} bytes for 0x${String.format("%02X", type)}")
            // If this was a temperature probe and we got real data, remember
            // the working dataId so we don't re-probe on future cycles.
            if (type in BD_ID_TEMP_CANDIDATES && knownTempDataId == null) {
                knownTempDataId = type
                LuiLogger.i(TAG, "Temperature dataId locked at 0x${String.format("%02X", type)}")
            }
            // Reset queue/buffer state BEFORE parse so parseBigData's onReading
            // callbacks and retry checks see a clean "done" state.
            onBigDataComplete(success = true)
            parseBigData(type, data)
        }
    }

    private fun parseBigData(type: Byte, data: ByteArray) {
        LuiLogger.i(TAG, "Parsing big data type=0x${String.format("%02X", type)}, ${data.size} bytes")
        when {
            type == BD_ID_SLEEP -> parseSleepStages(data)
            type == BD_ID_SPO2_HISTORY -> parseSpO2Data(data)
            type in BD_ID_TEMP_CANDIDATES -> parseTemperatureData(data)
            else -> LuiLogger.w(TAG, "Unknown big data type: 0x${String.format("%02X", type)}")
        }
    }

    private fun parseSpO2Data(data: ByteArray) {
        // Scan for latest valid SpO2 percentage (typically 80-100%)
        for (i in (data.size - 1) downTo 0) {
            val v = data[i].toInt() and 0xFF
            if (v in 80..100) {
                _spO2.value = v
                markRead("spo2")
                LuiLogger.i(TAG, "SpO2: $v%")
                return
            }
        }
        LuiLogger.w(TAG, "No valid SpO2 reading in ${data.size} bytes")
    }

    private fun parseSleepStages(data: ByteArray) {
        // Sleep records: [stageType, durationMinutes] pairs
        // Stage types: 0x02=light, 0x03=deep, 0x04=REM, 0x05=awake
        var deep = 0; var light = 0; var rem = 0; var awake = 0
        val stages = mutableListOf<Pair<Int, Int>>()
        var i = 0
        while (i < data.size - 1) {
            val stage = data[i].toInt() and 0xFF
            val mins = data[i + 1].toInt() and 0xFF
            if (mins in 1..240 && stage in 0x02..0x05) {
                stages.add(stage to mins)
                when (stage) {
                    0x02 -> light += mins
                    0x03 -> deep += mins
                    0x04 -> rem += mins
                    0x05 -> awake += mins
                }
            }
            i += 2
        }
        if (stages.isNotEmpty()) {
            val total = deep + light + rem + awake
            _sleepData.value = SleepData(total, deep, light, rem, awake, stages)
            markRead("sleep")
            LuiLogger.i(TAG, "Sleep: ${total}min total (deep=$deep, light=$light, rem=$rem, awake=$awake, segments=${stages.size})")
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

    /**
     * Parse temperature Big Data payload.
     *
     * Each byte encodes skin temp as `(byte + 200) / 10.0` — so 0xA0 = 36.0°C,
     * 0xA9 = 36.9°C, etc. Readings are packed sequentially (one per time slot
     * through the day), with zero bytes for slots where the ring wasn't worn.
     *
     * The packet starts with a small record header (e.g. `01 1E 00`) we skip
     * by filtering anything below the plausible byte range. We pick the LAST
     * valid reading as "latest" and average the rest for a daily mean.
     */
    private fun parseTemperatureData(data: ByteArray) {
        // Plausible raw byte range for skin temp 32–42°C under the (b+200)/10
        // encoding: byte in 120..220.
        val readings = mutableListOf<Float>()
        for (b in data) {
            val v = b.toInt() and 0xFF
            if (v < 120 || v > 220) continue  // padding zeros + record-header bytes
            val temp = (v + 200) / 10.0f
            if (temp in 32f..42f) readings.add(temp)
        }
        if (readings.isEmpty()) {
            LuiLogger.w(TAG, "No valid temperature in ${data.size} bytes (first 16: ${data.take(16).toByteArray().toHex()})")
            return
        }
        val latest = readings.last()
        val avg = readings.average().toFloat()
        _temperature.value = latest
        markRead("temperature")
        LuiLogger.i(TAG, "Temperature: latest=${"%.1f".format(latest)}°C, avg=${"%.1f".format(avg)}°C over ${readings.size} readings")
        onReading?.invoke("temperature", latest)
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
        val cycleStart = System.currentTimeMillis()
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

        // Big-data metrics: enqueue once, then retry at +60s and +120s from the
        // initial attempt if the metric didn't land. Retries are no-ops if the
        // first attempt succeeded (skipIfReadAfter guards by cycle start).
        scheduleBigDataWithRetries("temperature", cycleStart, initialDelayMs = 150_000L) {
            requestTemperature()
        }
        scheduleBigDataWithRetries("sleep", cycleStart, initialDelayMs = 210_000L) {
            requestSleep()
        }
    }

    private fun scheduleBigDataWithRetries(
        metric: String,
        cycleStart: Long,
        initialDelayMs: Long,
        request: () -> Unit
    ) {
        val retryOffsets = listOf(0L, 30_000L, 90_000L, 210_000L) // +0, +30s, +90s, +3.5min
        for (offset in retryOffsets) {
            handler.postDelayed({
                if (!isConnected) return@postDelayed
                val lastRead = _lastReadAt.value[metric] ?: 0L
                if (lastRead >= cycleStart) return@postDelayed // already got it this cycle
                request()
            }, initialDelayMs + offset)
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
