package com.airpointer.remote

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context

fun Context.bluetoothAdapter(): BluetoothAdapter? =
    (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
