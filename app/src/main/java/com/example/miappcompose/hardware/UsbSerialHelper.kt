
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.util.concurrent.Executors

class UsbSerialHelper(private val context: Context) {
    private var usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var serialPort: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null

    fun connect(onDataReceived: (ByteArray) -> Unit): Boolean {
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (availableDrivers.isEmpty()) return false

        val driver = availableDrivers.firstOrNull()
        val connection = usbManager.openDevice(driver?.device) ?: return false

        serialPort = driver?.ports?.firstOrNull()
        serialPort?.open(connection)
        serialPort?.setParameters(57600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

        ioManager = SerialInputOutputManager(serialPort, object : SerialInputOutputManager.Listener {
            override fun onRunError(e: Exception?) {
                Log.e("USB_SERIAL", "Error: ${e?.message}")
            }

            override fun onNewData(data: ByteArray?) {
                data?.let {
                    // 🔍 Log hexadecimal detallado
                    val hex = it.joinToString(" ") { b -> "%02X".format(b) }
                    Log.d("AS608_RX", "Datos recibidos (${it.size} bytes): $hex")

                    // Notifica al callback de As608Helper
                    onDataReceived(it)
                }
            }
        })

        Executors.newSingleThreadExecutor().submit(ioManager)
        return true
    }

    fun write(data: ByteArray) {
        serialPort?.write(data, 1000)
    }

    fun close() {
        ioManager?.stop()
        serialPort?.close()
        serialPort = null
    }
}