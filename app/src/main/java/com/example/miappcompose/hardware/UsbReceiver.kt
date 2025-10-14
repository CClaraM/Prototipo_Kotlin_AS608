package com.example.miappcompose.hardware

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log

class UsbReceiver(
    private val helper: UsbManagerHelper,
    private val onStatusUpdate: (String) -> Unit
) : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            UsbManagerHelper.ACTION_USB_PERMISSION -> {
                val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    device?.let {
                        onStatusUpdate("""
                            ✅ Permiso concedido
                            Device: ${it.deviceName}
                            Vendor ID: ${it.vendorId}
                            Product ID: ${it.productId}
                        """.trimIndent())
                        Log.d(UsbManagerHelper.TAG, "Permiso concedido para ${it.deviceName}")
                    }
                } else {
                    onStatusUpdate("❌ Permiso denegado")
                }
            }

            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                helper.handleDeviceAttached(device)
                onStatusUpdate("🔌 Dispositivo conectado: ${device?.deviceName}")
            }

            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                helper.handleDeviceDetached()
                onStatusUpdate("Dispositivo desconectado")
            }
        }
    }
}