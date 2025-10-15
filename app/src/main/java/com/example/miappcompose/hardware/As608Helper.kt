package com.example.miappcompose.hardware

import android.content.Context
import android.graphics.Bitmap
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val cmdMutex = Mutex()

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

        // 🔹 Forzar flow-control desactivado y ajustar buffers si están disponibles
        //try { serial?.setFlowControl(UsbSerialPort.FLOW_CONTROL_DISABLED) } catch (_: Exception) {}
        //try { serial?.setReadBufferSize(32 * 1024) } catch (_: Exception) {}
        //try { serial?.setWriteBufferSize(16 * 1024) } catch (_: Exception) {}

        // 👉 Reiniciar líneas DTR/RTS para evitar bloqueos
        serial?.setDTR(true)
        serial?.setRTS(true)
        serial?.setDTR(false); Thread.sleep(10)
        serial?.setDTR(true);  Thread.sleep(10)
        serial?.setRTS(false); Thread.sleep(10)
        serial?.setRTS(true)

        onStatus("🔌 AS608 conectado")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                purgeBoth()
                sendCommand(AS608Protocol.handshake())
                delay(200)
                purgeBoth()
                Log.d(TAG, "Buffer inicial limpiado correctamente")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error al limpiar buffer inicial: ${e.message}")
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

    /*private suspend fun pacedSend(cmd: ByteArray, postDelay: Long = 80) {
        sendCommand(cmd)
        delay(postDelay)
    }*/

    private suspend fun pacedSend(cmd: ByteArray, postDelayMs: Int = 8) {
        sendCommand(cmd)
        delay(postDelayMs.toLong())
    }

    private fun purgeBoth() {
        try {
            serial?.purgeHwBuffers(true, true)
            drainBuffer(80)
        } catch (e: Exception) {
            Log.w(TAG, "purgeBoth: ${e.message}")
        }
    }

    private fun readResponse(timeout: Int = 4000): ByteArray? {
        val buf = ByteArray(64)
        val acc = ArrayList<Byte>(64)
        val t0 = System.currentTimeMillis()
        var firstByteTime: Long? = null

        fun syncToHeader() {
            while (acc.size >= 2 && !(acc[0] == 0xEF.toByte() && acc[1] == 0x01.toByte())) {
                acc.removeAt(0)
            }
        }

        while (System.currentTimeMillis() - t0 < timeout) {
            val n = try { serial?.read(buf, 200) ?: 0 } catch (_: Exception) { 0 }
            if (n > 0) {
                if (firstByteTime == null) firstByteTime = System.currentTimeMillis()
                for (i in 0 until n) acc.add(buf[i])
                syncToHeader()

                if (acc.size >= 9) {
                    val pid = acc[6].toInt() and 0xFF
                    val lenH = acc[7].toInt() and 0xFF
                    val lenL = acc[8].toInt() and 0xFF
                    val total = 9 + ((lenH shl 8) or lenL)

                    // 🕒 margen de 100 ms después de recibir el primer byte
                    if (pid == 0x07 && acc.size >= total &&
                        (System.currentTimeMillis() - (firstByteTime ?: t0) > 100)) {
                        return acc.subList(0, total).toByteArray()
                    }
                }
            }
        }
        return null
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

    private fun launchCmd(block: suspend () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            cmdMutex.withLock { block() }
        }
    }

    // =======================================================
    // 🧪 4. Comandos de alto nivel
    // =======================================================
    fun genImgWithResponse() = launchCmd {
        purgeBoth()
        pacedSend(AS608Protocol.genImg())
        val resp = readResponse(4000)
        val msg = if (resp != null) AS608Protocol.parseResponse(resp) else "⚠️ No se recibió respuesta"
        msg?.let { withContext(Dispatchers.Main) { onStatus?.invoke(it) } }
    }

    fun img2TzWithResponse() = launchCmd {
        purgeBoth()
        pacedSend(AS608Protocol.img2Tz(1))
        val resp = readResponse(3000)
        val msg = if (resp != null) AS608Protocol.parseResponse(resp) else "⚠️ No se recibió respuesta"
        msg?.let { withContext(Dispatchers.Main) { onStatus?.invoke(it) } }
    }

    fun regModelWithResponse() = launchCmd {
        purgeBoth()
        pacedSend(AS608Protocol.regModel())
        val resp = readResponse(2000)
        val msg = if (resp != null) AS608Protocol.parseResponse(resp) else "⚠️ No se recibió respuesta"
        msg?.let { withContext(Dispatchers.Main) { onStatus?.invoke(it) } }
    }

    fun storeWithResponse(id: Int) = launchCmd {
        purgeBoth()
        pacedSend(AS608Protocol.store(1, id))
        val resp = readResponse(2000)
        val msg = if (resp != null) AS608Protocol.parseResponse(resp) else "⚠️ No se recibió respuesta"
        msg?.let { withContext(Dispatchers.Main) { onStatus?.invoke(it) } }
    }

    fun searchWithResponse() = launchCmd {
        purgeBoth()
        pacedSend(AS608Protocol.search(1))
        val resp = readResponse(2000)
        val msg = if (resp != null) AS608Protocol.parseResponse(resp) else "⚠️ No se recibió respuesta"
        msg?.let { withContext(Dispatchers.Main) { onStatus?.invoke(it) } }
    }

    fun emptyWithResponse() = launchCmd {
        purgeBoth()
        pacedSend(AS608Protocol.empty())
        val resp = readResponse(2000)
        val msg = if (resp != null) AS608Protocol.parseResponse(resp) else "⚠️ No se recibió respuesta"
        msg?.let { withContext(Dispatchers.Main) { onStatus?.invoke(it) } }
    }
    fun cancelWithResponse() = launchCmd {
        purgeBoth()
        pacedSend(AS608Protocol.cancel())
        val resp = readResponse(1000)
        val msg = if (resp != null) AS608Protocol.parseResponse(resp) else "⚠️ No se recibió respuesta"
        msg?.let { withContext(Dispatchers.Main) { onStatus?.invoke(it) } }
    }

    // =======================================================
    // 🔹 Leer parámetros
    // =======================================================
    fun readSysParameters(onParams: (String) -> Unit) = launchCmd {
        purgeBoth()
        pacedSend(AS608Protocol.readSysParams())
        val resp = readResponse(2000)
        if (resp != null) {
            val msg = AS608Protocol.parseSysParams(resp)
            withContext(Dispatchers.Main) {
                onParams(msg)
                onStatus?.invoke("✅ Parámetros recibidos")
            }
        } else {
            withContext(Dispatchers.Main) {
                onStatus?.invoke("❌ No se recibió respuesta del lector")
            }
        }
    }

    // =======================================================
    // 🧤 6. Verificación y lectura de imagen
    // =======================================================

    fun verifyFinger(callback: (Boolean) -> Unit) = launchCmd {
        purgeBoth()
        pacedSend(AS608Protocol.genImg())
        val resp = readResponse(3000)

        if (resp != null) {
            val code = AS608Protocol.getConfirmationCode(resp)
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
        } else {
            withContext(Dispatchers.Main) {
                onStatus?.invoke("❌ No se recibió handshake de genImg")
                callback(false)
            }
            delay(50)
            cancelWithResponse()
            drainBuffer(100)
        }
    }

    private suspend fun verifyBeforeGetImage(): Boolean {
        purgeBoth()
        pacedSend(AS608Protocol.genImg())
        val resp = readResponse(2000)
        if (resp != null) {
            val code = AS608Protocol.getConfirmationCode(resp)
            return code == 0x00
        }
        return false
    }

    // =======================================================
    // 🔹 Obtener imagen completa
    // =======================================================
    fun getImage() = launchCmd {
        if (isReading.get()) {
            withContext(Dispatchers.Main) { onStatus?.invoke("⏳ Lectura en curso...") }
            return@launchCmd
        }
        isReading.set(true)
        try {
            if (!verifyBeforeGetImage()) {
                withContext(Dispatchers.Main) {
                    delay(100)
                    onStatus?.invoke("⚠️ No se detectó huella. Cancelando lectura.")
                }
                cancelWithResponse()
                isReading.set(false)
                return@launchCmd
            }

            withContext(Dispatchers.Main) { onStatus?.invoke("📷 Solicitando imagen...") }
            purgeBoth()
            pacedSend(AS608Protocol.upImage(), 2)

            val rawBuffer = ArrayList<Byte>(64 * 1024)
            val imageBuffer = ArrayList<Byte>(128 * 288)
            val temp = ByteArray(4096)

            var ackOk = false
            var sawFirstData = false
            var sawLastPacket = false

            val tEnd = System.currentTimeMillis() + 15_000
            var lastDataTs = System.currentTimeMillis()

            while (System.currentTimeMillis() < tEnd) {
                val n = try { serial?.read(temp, 300) ?: 0 } catch (_: Exception) { 0 }
                if (n > 0) {
                    for (i in 0 until n) rawBuffer.add(temp[i])
                    lastDataTs = System.currentTimeMillis()
                    val parseRes = parseIncomingStream(rawBuffer, imageBuffer)
                    if (parseRes.ackJustSeen) {
                        ackOk = true
                        Log.d(TAG, "🤝 ACK detectado code=${parseRes.lastAckCode}")
                    }
                    if (parseRes.firstDataJustSeen && !sawFirstData) {
                        sawFirstData = true
                        Log.d(TAG, "🟡 Primer paquete de datos detectado (offset=${imageBuffer.size})")
                    }
                    if (parseRes.lastPacketSeen && !sawLastPacket) {
                        sawLastPacket = true
                        Log.d(TAG, "🟢 Último paquete detectado offset ${imageBuffer.size}")
                        break
                    }
                } else {
                    if (System.currentTimeMillis() - lastDataTs > 2000) break
                }
            }

            val expected = 128 * 288
            val final = when {
                imageBuffer.size == expected -> imageBuffer.toByteArray()
                imageBuffer.size > expected -> {
                    Log.w(TAG, "⚠️ Excedente ${imageBuffer.size - expected} bytes, truncando.")
                    imageBuffer.subList(0, expected).toByteArray()
                }
                else -> {
                    Log.w(TAG, "⚠️ Faltan ${expected - imageBuffer.size} bytes, padding.")
                    val out = ByteArray(expected)
                    for (i in imageBuffer.indices) out[i] = imageBuffer[i]
                    val pad = if (imageBuffer.isNotEmpty()) imageBuffer.last() else 0
                    for (i in imageBuffer.size until expected) out[i] = pad
                    out
                }
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

    // =======================================================
    // 🔹 Parseo de stream
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
                    if (!firstDataSeenNow && imageBuffer.isEmpty()) firstDataSeenNow = true
                    for (i in payloadStart until payloadEnd) imageBuffer.add(rawBuffer[i])
                    if (pid == 0x08) lastSeen = true
                    repeat(total) { rawBuffer.removeAt(0) }
                }
                else -> rawBuffer.removeAt(0)
            }
        }

        return ParseResult(ackSeen, lastAck, firstDataSeenNow, lastSeen)
    }

    // =======================================================
    // 🔹 Conversión a imagen
    // =======================================================
    private fun convertToBitmapFinal(imageData: ByteArray): Bitmap {
        val width = 128
        val height = 288
        val total = width * height

        val safeData = if (imageData.size >= total) imageData else {
            val out = ByteArray(total)
            System.arraycopy(imageData, 0, out, 0, imageData.size)
            val pad = if (imageData.isNotEmpty()) imageData.last() else 0
            for (i in imageData.size until total) out[i] = pad
            out
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



    // =======================================================
    // 🔹 Utilidades
    // =======================================================
    private fun LogHex(prefix: String, data: ByteArray) {
        val hex = data.joinToString(" ") { "%02X".format(it) }
        Log.d("${TAG}_$prefix", hex)
    }
}
