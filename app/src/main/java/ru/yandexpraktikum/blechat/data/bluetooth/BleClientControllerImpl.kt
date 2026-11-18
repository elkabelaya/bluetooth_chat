package ru.yandexpraktikum.blechat.data.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import ru.yandexpraktikum.blechat.R
import ru.yandexpraktikum.blechat.domain.bluetooth.BleClientController
import ru.yandexpraktikum.blechat.domain.model.Message
import ru.yandexpraktikum.blechat.domain.model.ScannedBluetoothDevice
import ru.yandexpraktikum.blechat.presentation.notifications.NotificationsHelper
import ru.yandexpraktikum.blechat.utils.checkForConnectPermission
import ru.yandexpraktikum.blechat.utils.notifyCharUUID
import ru.yandexpraktikum.blechat.utils.serviceUUID
import ru.yandexpraktikum.blechat.utils.writeCharUUID
import java.nio.charset.Charset
import javax.inject.Inject

class BleClientControllerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter?,
    private val locationManager: LocationManager,
    private val viewModelScope: CoroutineScope,
    private val notificationsHelper: NotificationsHelper,
): BleClientController {

    private val bleScanner by lazy {
        bluetoothAdapter?.bluetoothLeScanner
    }

    private var currentGatt: BluetoothGatt? = null

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Log.e("BLE", "Connection failed with status: $status")
                        return
                    }
                    context.checkForConnectPermission {
                        gatt.discoverServices()
                    }
                    _scannedDevices.update { devices ->
                        devices.map {
                            if (it.address == gatt.device.address) {
                                it.copy(isConnected = true)
                            } else it
                        }
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Log.e("BLE", "Disconnected with status: $status")
                    }
                    _scannedDevices.update { devices ->
                        devices.map {
                            if (it.address == gatt.device.address) {
                                it.copy(isConnected = false)
                            } else it
                        }
                    }
                    closeGatt(gatt)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                context.checkForConnectPermission {
                    val service = gatt?.getService(serviceUUID)
                    val notifyCharacteristic = service?.getCharacteristic(notifyCharUUID)
                    if (notifyCharacteristic != null) {
                        gatt.setCharacteristicNotification(notifyCharacteristic, true)
                    }
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (characteristic.uuid != writeCharUUID) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e("BLE", "Characteristic write failed with status: $status")
                return
            }
            val value = characteristic.value ?: return
            val message = String(value, Charset.defaultCharset())
            viewModelScope.launch {
                _scannedDevices.update { devices ->
                    devices.map {
                        if (it.address == gatt.device.address) {
                            it.copy(
                                messages = it.messages + Message(
                                    text = message,
                                    senderAddress = bluetoothAdapter?.address ?: "",
                                    isFromLocalUser = true
                                )
                            )
                        } else it
                    }
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid == notifyCharUUID) {
                val message = String(characteristic.value, Charset.defaultCharset())
                notificationsHelper.notifyOnMessageReceived(
                    title = context.getString(R.string.new_message),
                    message = message
                )
                viewModelScope.launch {
                    _scannedDevices.update { devices ->
                        devices.map {
                            if (it.address == gatt.device.address) {
                                it.copy(
                                    messages = it.messages + Message(
                                        text = message,
                                        senderAddress = gatt.device.address,
                                        isFromLocalUser = false
                                    )
                                )
                            } else it
                        }
                    }
                }
            }
        }
    }

    private val _isBluetoothEnabled = MutableStateFlow(false)
    override val isBluetoothEnabled: StateFlow<Boolean>
        get() = _isBluetoothEnabled.asStateFlow()

    private val _isLocationEnabled = MutableStateFlow(false)
    override val isLocationEnabled: StateFlow<Boolean>
        get() = _isLocationEnabled.asStateFlow()


    init {
        updateBluetoothState()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            updateLocationState()
        }
    }

    override fun updateBluetoothState() {
        try {
            _isBluetoothEnabled.value = bluetoothAdapter?.isEnabled == true
        } catch (e: Exception) {
            Log.e("BLE", "Failed to initialize Bluetooth state", e)
        }
    }

    override fun updateLocationState() {
        try {
            _isLocationEnabled.value = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {
            Log.e("BLE", "Failed to initialize Location state", e)
        }
    }

    private val _scannedDevices = MutableStateFlow<List<ScannedBluetoothDevice>>(emptyList())
    override val scannedDevices: StateFlow<List<ScannedBluetoothDevice>>
        get() = _scannedDevices.asStateFlow()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            context.checkForConnectPermission {
                val bluetoothDevice = ScannedBluetoothDevice(
                    name = device.name,
                    address = device.address
                )
                _scannedDevices.update { devices ->
                    if (devices.none { it.address == bluetoothDevice.address }) {
                        devices + bluetoothDevice
                    } else devices
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e("BLE", "Scan failed with error code: $errorCode")
        }
    }

    override fun startScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        } else {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_ADMIN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }
        bleScanner?.startScan(scanCallback)
    }

    override fun stopScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        } else {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_ADMIN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }
        bleScanner?.stopScan(scanCallback)
        _scannedDevices.update {
            it.filter { device ->
                device.isConnected
            }
        }
    }

    override fun connectToDevice(device: ScannedBluetoothDevice): Boolean {
        context.checkForConnectPermission {
            val bluetoothDevice = bluetoothAdapter?.getRemoteDevice(device.address)
            currentGatt = bluetoothDevice?.connectGatt(context, false, gattCallback)
        }
        return currentGatt != null
    }

    override suspend fun sendMessage(message: String, deviceAddress: String): Boolean {
        val gatt = currentGatt ?: return false
        if (gatt.device.address != deviceAddress) return false

        val characteristic = gatt.getService(serviceUUID)?.getCharacteristic(writeCharUUID)
            ?: return false

        characteristic.setValue(message.toByteArray(Charset.defaultCharset()))
        var writeStarted = false
        context.checkForConnectPermission {
            writeStarted = gatt.writeCharacteristic(characteristic)
        }
        return writeStarted
    }

    private fun closeGatt(gatt: BluetoothGatt?) {
        gatt?.close()
        if (currentGatt == gatt) {
            currentGatt = null
        }
    }

    override fun closeConnection() {
        closeGatt(currentGatt)
    }

    override fun release() {
        closeConnection()
    }
}