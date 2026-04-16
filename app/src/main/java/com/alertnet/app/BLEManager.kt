package com.alertnet.app

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.util.Log

class BLEManager {

    private val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

    fun startScan() {
        val scanner = bluetoothAdapter.bluetoothLeScanner

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                Log.d("BLE", "Found: ${result.device.name}")
            }
        }

        scanner.startScan(callback)
    }
}