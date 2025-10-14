package com.example.miappcompose.hardware

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log

class UsbManagerHelper(private val context: Context) {

    companion object {
        const val ACTION_USB_PERMISSION = "com.example.miappcompose.USB_PERMISSION"
        const val TAG = "USB_DEBUG"
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    val permissionIntent: PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(ACTION_USB_PERMISSION),
        PendingIntent.FLAG_IMMUTABLE
    )

    fun requestPermission(device: UsbDevice) {
        usbManager.requestPermission(device, permissionIntent)
    }

    fun getConnectedDevices(): List<UsbDevice> {
        return usbManager.deviceList.values.toList()
    }

    fun handleDeviceAttached(device: UsbDevice?) {
        device?.let {
            Log.d(TAG, "🔌 Dispositivo conectado: ${it.deviceName}")
            requestPermission(it)
        }
    }

    fun handleDeviceDetached() {
        Log.d(TAG, "❌ Dispositivo desconectado")
    }
}