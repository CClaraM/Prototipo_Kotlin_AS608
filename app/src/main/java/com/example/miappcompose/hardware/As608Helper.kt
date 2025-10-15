package com.example.miappcompose.hardware

import android.content.Context
import android.graphics.Bitmap
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.*
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class AS608Helper(private val context: Context) {

    // =======================================================
    // 🧭 1. Estado base y configuración
    // =======================================================
    private var serial: UsbSerialPort? = null
    private var job: Job? = null
    private val TAG = "AS608"
    private val isReading = AtomicBoolean(false)

    var onStatus: ((String) -> Unit)? = null
    var onImage: ((Bitmap) -> Unit)? = null

    // =======================================================
    // 🔌 2. Inicio y cierre de la conexión
    // =======================================================
    fun start(onStatus: (String) -> Unit, onImage: (Bitmap) -> Unit) {
        this.onStatus = onStatus
        this.onImage = onImage

        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (drivers.isEmpty()) {
            onStatus("❌ No se encontró ningún dispositivo USB")
            return
        }

        val driver: UsbSerialDriver = drivers.first()
        val connection = usbManager.openDevice(driver.device)
        if (connection == null) {
            onStatus("⚠️ Permiso USB no concedido")
            return
        }

        serial = driver.ports[0]
        serial?.open(connection)
        serial?.setParameters(57600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

        onStatus("🔌 AS608 conectado")

        // 🔄 Limpieza inicial de buffer
        CoroutineScope(Dispatchers.IO).launch {
            try {
                drainBuffer(300)
                sendCommand(AS608Protocol.handshake())
                delay(200)
                drainBuffer(200)
                Log.d(TAG, "Buffer inicial limpiado correctamente")
            } catch (e: Exception) {
                Log.e(TAG,"❌ Error al limpiar buffer inicial: ${e.message}")
            }
        }
    }

    fun stop() {
        try {
            job?.cancel()
            serial?.close()
            onStatus?.invoke("🔴 Conexión cerrada")
        } catch (e: Exception) {
            Log.e(TAG, "Error al cerrar el puerto: ${e.message}")
        }
    }

    // =======================================================
    // 📡 3. Comunicación básica
    // =======================================================
    private fun sendCommand(cmd: ByteArray) {
        try {
            serial?.write(cmd, 1000)
            LogHex("TX", cmd)
        } catch (e: IOException) {
            Log.e(TAG, "Error enviando comando: ${e.message}")
        }
    }

    private fun readResponse(timeout: Int = 2000): ByteArray? {
        val buffer = ByteArray(64)
        val total = mutableListOf<Byte>()
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeout) {
            val len = serial?.read(buffer, 200) ?: 0
            if (len > 0) {
                total.addAll(buffer.take(len))
                if (total.size >= 12) break
            }
        }
        return if (total.isNotEmpty()) total.toByteArray() else null
    }

    private fun sendAndRead(cmd: ByteArray, timeout: Int = 2000) {
        sendCommand(cmd)
        CoroutineScope(Dispatchers.IO).launch {
            val resp = readResponse(timeout)
            val msg = if (resp != null) AS608Protocol.parseResponse(resp) else "⚠️ No se recibió respuesta"
            if (msg != null) {
                withContext(Dispatchers.Main) { onStatus?.invoke(msg) }
            }
        }
    }

    private fun drainBuffer(extraTimeMs: Long = 100) {
        val temp = ByteArray(1024)
        val start = System.currentTimeMillis()
        var totalDrained = 0
        while (System.currentTimeMillis() - start < extraTimeMs) {
            val len = try { serial?.read(temp, 50) ?: 0 } catch (e: Exception) { 0 }
            if (len > 0) totalDrained += len
        }
        if (totalDrained > 0) Log.w(TAG, "🧹 Limpieza de buffer: $totalDrained bytes drenados")
    }

    // =======================================================
    // 🧪 4. Comandos de alto nivel
    // =======================================================
    fun genImgWithResponse() = sendAndRead(AS608Protocol.genImg())
    fun img2TzWithResponse() = sendAndRead(AS608Protocol.img2Tz(1))
    fun regModelWithResponse() = sendAndRead(AS608Protocol.regModel())
    fun storeWithResponse(id: Int) = sendAndRead(AS608Protocol.store(1, id))
    fun searchWithResponse() = sendAndRead(AS608Protocol.search(1))
    fun emptyWithResponse() = sendAndRead(AS608Protocol.empty())
    fun cancelWithResponse() = sendAndRead(AS608Protocol.cancel())

    // =======================================================
    // 🧠 5. Lectura de parámetros
    // =======================================================
    fun readSysParameters(onParams: (String) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            onStatus?.invoke("📡 Solicitando parámetros del lector...")
            sendCommand(AS608Protocol.readSysParams())
            val resp = readResponse(2000)
            if (resp != null) {
                val msg = AS608Protocol.parseSysParams(resp)
                withContext(Dispatchers.Main) {
                    // ⬅️ Solo actualiza sysParams
                    onParams(msg)

                    // ✅ Mantiene log detallado sin llenar la etiqueta de estado
                    onStatus?.invoke("✅ Parámetros recibidos")
                }
            } else {
                withContext(Dispatchers.Main) {
                    onStatus?.invoke("❌ No se recibió respuesta del lector")
                }
            }
            /*val msg = if (resp != null) AS608Protocol.parseSysParams(resp)
            else "❌ No se recibió respuesta del lector"
            withContext(Dispatchers.Main) {
                onStatus?.invoke(msg)
            }*/
        }
    }

    // =======================================================
    // 🧤 6. Verificación y lectura de imagen
    // =======================================================
    fun verifyFinger(callback: (Boolean) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            sendCommand(AS608Protocol.genImg())
            val resp = readResponse(2000)
            val code = resp?.let { AS608Protocol.getConfirmationCode(it) } ?: -1

            if (code == 0x00) {
                withContext(Dispatchers.Main) {
                    onStatus?.invoke("✅ Huella detectada")
                    callback(true)
                }
            } else {
                withContext(Dispatchers.Main) {
                    onStatus?.invoke(AS608Protocol.confirmationMessage(code))
                    callback(false)
                }
                delay(50)
                cancelWithResponse()
                drainBuffer(100)
            }
        }
    }

    suspend fun verifyBeforeGetImage(): Boolean {
        sendCommand(AS608Protocol.genImg())
        val resp = readResponse(2000)
        return resp?.let { AS608Protocol.getConfirmationCode(it) == 0x00 } ?: false
    }

    fun getImage() {
        if (isReading.get()) {
            onStatus?.invoke("⏳ Lectura en curso...")
            return
        }
        isReading.set(true)

        job = CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!verifyBeforeGetImage()) {
                    withContext(Dispatchers.Main) {
                        delay(100)
                        onStatus?.invoke("⚠️ No se detectó huella. Cancelando lectura.")
                    }
                    cancelWithResponse()
                    isReading.set(false)
                    return@launch
                }

                onStatus?.invoke("📷 Solicitando imagen...")
                sendCommand(AS608Protocol.upImage())

                val rawBuffer = ArrayList<Byte>(64 * 1024)
                val imageBuffer = ArrayList<Byte>(128 * 288)
                val temp = ByteArray(4096)

                var sawLastPacket = false
                val tEnd = System.currentTimeMillis() + 15_000
                var lastDataTs = System.currentTimeMillis()

                while (System.currentTimeMillis() < tEnd) {
                    val n = try { serial?.read(temp, 300) ?: 0 } catch (_: Exception) { 0 }
                    if (n > 0) {
                        for (i in 0 until n) rawBuffer.add(temp[i])
                        lastDataTs = System.currentTimeMillis()
                        val parseRes = parseIncomingStream(rawBuffer, imageBuffer)
                        if (parseRes.lastPacketSeen) {
                            sawLastPacket = true
                            break
                        }
                    } else if (System.currentTimeMillis() - lastDataTs > 2000) break
                }

                val expected = 128 * 288
                val final = ByteArray(expected)
                val size = imageBuffer.size
                val pad = if (imageBuffer.isNotEmpty()) imageBuffer.last() else 0
                for (i in 0 until expected) {
                    final[i] = if (i < size) imageBuffer[i] else pad
                }

                val bmp = convertToBitmapFinal(final)
                withContext(Dispatchers.Main) {
                    onImage?.invoke(bmp)
                    onStatus?.invoke("✅ Imagen recibida (${final.size} bytes)")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error leyendo imagen: ${e.message}")
                withContext(Dispatchers.Main) {
                    onStatus?.invoke("❌ Error: ${e.message}")
                }
            } finally {
                isReading.set(false)
            }
        }
    }

    // =======================================================
    // 🧰 7. Utilidades internas
    // =======================================================
    private data class ParseResult(
        val ackJustSeen: Boolean,
        val lastAckCode: Int,
        val firstDataJustSeen: Boolean,
        val lastPacketSeen: Boolean
    )

    private fun parseIncomingStream(
        rawBuffer: MutableList<Byte>,
        imageBuffer: MutableList<Byte>
    ): ParseResult {
        var ackSeen = false
        var lastAck = -1
        var firstDataSeenNow = false
        var lastSeen = false

        // 🧹 Limpiar basura antes del header
        while (rawBuffer.size >= 2 && !(rawBuffer[0] == 0xEF.toByte() && rawBuffer[1] == 0x01.toByte())) {
            rawBuffer.removeAt(0)
        }

        while (rawBuffer.size >= 9) {
            if (!(rawBuffer[0] == 0xEF.toByte() && rawBuffer[1] == 0x01.toByte())) {
                rawBuffer.removeAt(0)
                continue
            }

            if (rawBuffer.size < 9) break

            val pid = rawBuffer[6].toInt() and 0xFF
            val lenHi = rawBuffer[7].toInt() and 0xFF
            val lenLo = rawBuffer[8].toInt() and 0xFF
            val length = (lenHi shl 8) or lenLo
            val total = 9 + length
            if (rawBuffer.size < total) break

            val payloadLen = length - 2
            val payloadStart = 9
            val payloadEnd = payloadStart + payloadLen

            when (pid) {
                0x07 -> {
                    if (payloadLen >= 1) {
                        lastAck = rawBuffer[payloadStart].toInt() and 0xFF
                        ackSeen = true
                    }
                    repeat(total) { rawBuffer.removeAt(0) }
                }
                0x02, 0x08 -> {
                    if (!firstDataSeenNow && imageBuffer.isEmpty()) {
                        firstDataSeenNow = true
                    }
                    for (i in payloadStart until payloadEnd) {
                        imageBuffer.add(rawBuffer[i])
                    }
                    if (pid == 0x08) {
                        lastSeen = true
                    }
                    repeat(total) { rawBuffer.removeAt(0) }
                }
                else -> {
                    rawBuffer.removeAt(0)
                }
            }

            while (rawBuffer.size >= 2 && !(rawBuffer[0] == 0xEF.toByte() && rawBuffer[1] == 0x01.toByte())) {
                rawBuffer.removeAt(0)
            }
        }

        return ParseResult(
            ackJustSeen = ackSeen,
            lastAckCode = lastAck,
            firstDataJustSeen = firstDataSeenNow,
            lastPacketSeen = lastSeen
        )
    }

    private fun convertToBitmapFinal(imageData: ByteArray): Bitmap {
        val width = 128
        val height = 288
        val total = width * height
        val safeData = if (imageData.size >= total) imageData else ByteArray(total).also {
            System.arraycopy(imageData, 0, it, 0, imageData.size)
            val pad = if (imageData.isNotEmpty()) imageData.last() else 0
            for (i in imageData.size until total) it[i] = pad
        }

        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        var i = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val gray = safeData[i++].toInt() and 0xFF
                val pixel = 0xFF shl 24 or (gray shl 16) or (gray shl 8) or gray
                bmp.setPixel(x, y, pixel)
            }
        }
        return bmp
    }

    private fun LogHex(prefix: String, data: ByteArray) {
        val hex = data.joinToString(" ") { "%02X".format(it) }
        Log.d("${TAG}_$prefix", hex)
    }
}
