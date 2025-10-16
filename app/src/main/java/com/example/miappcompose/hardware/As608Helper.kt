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
import android.util.Base64

/**
 * ============================================================
 *  🧠 AS608Helper
 *  Clase encargada de manejar la comunicación entre Android y
 *  el sensor de huellas AS608 mediante puerto serial USB.
 * ============================================================
 */
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

    @Volatile
    private var modelReadyInRam = false

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

    /**
     * Lee una respuesta del lector AS608.
     * Devuelve null si no hay respuesta.
     */
    private fun readResponse(timeout: Int = 4000): ByteArray? {
        val buf = ByteArray(64)
        val acc = ArrayList<Byte>(64)
        val t0 = System.currentTimeMillis()

        fun syncToHeader() {
            while (acc.size >= 2 && !(acc[0] == 0xEF.toByte() && acc[1] == 0x01.toByte())) {
                acc.removeAt(0)
            }
        }

        while (System.currentTimeMillis() - t0 < timeout) {
            val n = try { serial?.read(buf, 200) ?: 0 } catch (_: Exception) { 0 }

            logRxChunk("AS608_RX_ACK", buf, n)

            if (n > 0) {
                for (i in 0 until n) acc.add(buf[i])
                syncToHeader()
                if (acc.size >= 9) {
                    val pid = acc[6].toInt() and 0xFF
                    val lenH = acc[7].toInt() and 0xFF
                    val lenL = acc[8].toInt() and 0xFF
                    val total = 9 + ((lenH shl 8) or lenL)
                    if (pid == 0x07 && acc.size >= total) {
                        return acc.subList(0, total).toByteArray()
                    }
                }
            }
        }
        return null
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

    /** Capturar imagen y devolver código */
    fun genImgWithResponse() {
        CoroutineScope(Dispatchers.IO).launch {
            drainBuffer(100)
            sendCommand(AS608Protocol.genImg())
            delay(80)
            val resp = readResponse(4000)
            val code = if (resp != null) AS608Protocol.getConfirmationCode(resp) else -1
            withContext(Dispatchers.Main) {
                onStatus?.invoke(if (code >= 0) "CODE:$code" else "⚠️ No se recibió respuesta")
            }
        }
    }

    /** Generar modelo a partir de buffers 1 y 2 */
    fun regModelWithResponse() {
        CoroutineScope(Dispatchers.IO).launch {
            drainBuffer(100)
            sendCommand(AS608Protocol.regModel())
            delay(80)
            val resp = readResponse(4000)
            val code = if (resp != null) AS608Protocol.getConfirmationCode(resp) else -1
            withContext(Dispatchers.Main) {
                onStatus?.invoke(if (code >= 0) "CODE:$code" else "⚠️ No se recibió respuesta")
            }
        }
    }

    /** Borrar toda la base de datos del lector */
    fun emptyWithResponse() = launchCmd {
        purgeBoth()
        pacedSend(AS608Protocol.empty())
        val resp = readResponse(2000)
        val msg = if (resp != null) AS608Protocol.parseResponse(resp) else "⚠️ No se recibió respuesta"
        msg?.let { withContext(Dispatchers.Main) { onStatus?.invoke(it) } }
    }

    /** Cancelar operación actual */
    fun cancelWithResponse() = launchCmd {
        purgeBoth()
        pacedSend(AS608Protocol.cancel())
        val resp = readResponse(1000)
        val msg = if (resp != null) AS608Protocol.parseResponse(resp) else "⚠️ No se recibió respuesta"
        msg?.let { withContext(Dispatchers.Main) { onStatus?.invoke(it) } }
    }

    /** Conversión doble automática */
    fun convertWithResponse() {
        CoroutineScope(Dispatchers.IO).launch {
            withContext(Dispatchers.Main) {
                onStatus?.invoke("🔸 Iniciando conversión de huella (doble captura)...")
            }
            buildModel { ok ->
                if (ok) onStatus?.invoke("✅ Conversión completada. Modelo listo en RAM.")
                else onStatus?.invoke("❌ Falló la conversión de huella.")
            }
        }
    }

    /** Guardar template en un ID */
    fun storeTemplateWithResponse(pageId: Int, bufferId: Int = 1) {
        CoroutineScope(Dispatchers.IO).launch {
            withContext(Dispatchers.Main) {
                onStatus?.invoke("💾 Guardando template en ID=$pageId ...")
            }

            sendCommand(AS608Protocol.store(bufferId, pageId))
            val resp = readResponse(4000)
            val code = if (resp != null) AS608Protocol.getConfirmationCode(resp) else -1

            withContext(Dispatchers.Main) {
                if (code == 0x00) {
                    onStatus?.invoke("✅ Template almacenado correctamente en ID=$pageId")
                } else {
                    onStatus?.invoke(
                        AS608Protocol.confirmationMessage(code)
                            .ifEmpty { "⚠️ No se pudo guardar (code=$code)" }
                    )
                }
            }
        }
    }

    /** Buscar template en la base */
    fun searchWithResponse(startPage: Int = 0, pageNum: Int = 1023) {
        CoroutineScope(Dispatchers.IO).launch {
            // Paso 1: Capturar huella
            sendCommand(AS608Protocol.genImg())
            val resp1 = readResponse(4000)
            val code1 = if (resp1 != null) AS608Protocol.getConfirmationCode(resp1) else -1
            if (code1 != 0x00) {
                withContext(Dispatchers.Main) {
                    onStatus?.invoke("⚠️ No se pudo capturar huella para búsqueda (${code1})")
                }
                return@launch
            }

            // Paso 2: Convertir a template temporal en buffer 1
            sendCommand(AS608Protocol.img2Tz(1))
            val resp2 = readResponse(4000)
            val code2 = if (resp2 != null) AS608Protocol.getConfirmationCode(resp2) else -1
            if (code2 != 0x00) {
                withContext(Dispatchers.Main) {
                    onStatus?.invoke("⚠️ No se pudo generar template (${code2})")
                }
                return@launch
            }

            // Paso 3: Buscar en base
            sendCommand(AS608Protocol.search(1, startPage, pageNum))
            val resp3 = readResponse(4000)
            if (resp3 == null) {
                withContext(Dispatchers.Main) {
                    onStatus?.invoke("❌ No se recibió respuesta de búsqueda")
                }
                return@launch
            }

            val code3 = AS608Protocol.getConfirmationCode(resp3)
            if (code3 == 0x00 && resp3.size >= 16) {
                val pageId = ((resp3[10].toInt() and 0xFF) shl 8) or (resp3[11].toInt() and 0xFF)
                val score = ((resp3[12].toInt() and 0xFF) shl 8) or (resp3[13].toInt() and 0xFF)
                withContext(Dispatchers.Main) {
                    onStatus?.invoke("✅ Huella encontrada — ID=$pageId, Score=$score")
                }
            } else {
                withContext(Dispatchers.Main) {
                    onStatus?.invoke("⚠️ No se encontró coincidencia (code=$code3)")
                }
            }
        }
    }

    // =======================================================
    // 🔹 5. Leer parámetros del dispositivo
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
    // 🧤 6. Verificación de huella
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

                    onStatus?.invoke("⚠️ No se detectó huella. Cancelando lectura.")
                }
                cancelWithResponse()
                isReading.set(false)
                return@launchCmd
            }

            // 🕒 pequeño delay antes de solicitar la imagen
            delay(100)

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
    // 🔹 7. Conversión a modelo
    // =======================================================
    fun buildModel(
        buffer1: Int = 1,
        buffer2: Int = 2,
        onDone: (Boolean) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            modelReadyInRam = false
            // --- Captura 1 ---
            if (!verifyBeforeGetImage()) {
                withContext(Dispatchers.Main) { onStatus?.invoke("⚠️ No se detectó huella (1)") }
                onDone(false); return@launch
            }
            sendCommand(AS608Protocol.img2Tz(buffer1))
            val r1 = readResponse(4000)
            val c1 = AS608Protocol.getConfirmationCode(r1 ?: byteArrayOf())
            if (c1 != 0x00) {
                withContext(Dispatchers.Main) { onStatus?.invoke("❌ Falló img2Tz(1): ${AS608Protocol.confirmationMessage(c1)}") }
                onDone(false); return@launch
            }

            // --- Captura 2 ---
            if (!verifyBeforeGetImage()) {
                withContext(Dispatchers.Main) { onStatus?.invoke("⚠️ No se detectó huella (2)") }
                onDone(false); return@launch
            }
            sendCommand(AS608Protocol.img2Tz(buffer2))
            val r2 = readResponse(4000)
            val c2 = AS608Protocol.getConfirmationCode(r2 ?: byteArrayOf())
            if (c2 != 0x00) {
                withContext(Dispatchers.Main) { onStatus?.invoke("❌ Falló img2Tz(2): ${AS608Protocol.confirmationMessage(c2)}") }
                onDone(false); return@launch
            }

            // --- Generar modelo ---
            sendCommand(AS608Protocol.regModel())
            val r3 = readResponse(4000)
            val c3 = AS608Protocol.getConfirmationCode(r3 ?: byteArrayOf())
            if (c3 != 0x00) {
                withContext(Dispatchers.Main) { onStatus?.invoke("❌ Falló regModel: ${AS608Protocol.confirmationMessage(c3)}") }
                onDone(false); return@launch
            }

            withContext(Dispatchers.Main) {
                onStatus?.invoke("✅ Modelo generado correctamente (buffers 1 y 2)")
                modelReadyInRam = true  // 👈 Indicamos que hay modelo en RAM listo para descargar
                onDone(true)
            }
        }
    }

    // =======================================================
    //
    // =======================================================
    private fun uploadTemplate(bufferId: Int = 1, tpl: ByteArray, callback: (Boolean) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                purgeBoth()
                pacedSend(AS608Protocol.downChar(bufferId))
                delay(50) // pequeña espera para no pisar el ACK

                // ✅ Esperar ACK inicial (confirmación para iniciar recepción)
                val ack = readResponse(3000)
                val ackCode = if (ack != null) AS608Protocol.getConfirmationCode(ack) else -1
                if (ackCode != 0x00) {
                    withContext(Dispatchers.Main) {
                        callback(false)
                    }
                    return@launch
                }

                // ✨ Fragmentar template en paquetes de 128 bytes (según doc AS608)
                val chunkSize = 128
                var offset = 0
                val totalSize = tpl.size

                while (offset < totalSize) {
                    val end = minOf(offset + chunkSize, totalSize)
                    val chunk = tpl.copyOfRange(offset, end)
                    val isLast = end == totalSize

                    val packet = AS608Protocol.buildDataPacket(chunk, isLast)
                    serial?.write(packet, 1000)
                    offset = end
                    delay(2) // pequeño respiro para el puerto
                }

                // ✅ Esperar ACK final después de enviar toda la plantilla
                val finalAck = readResponse(4000)
                val finalCode = if (finalAck != null) AS608Protocol.getConfirmationCode(finalAck) else -1

                withContext(Dispatchers.Main) {
                    callback(finalCode == 0x00)
                }

            } catch (e: Exception) {
                Log.e("AS608", "Error subiendo template: ${e.message}")
                withContext(Dispatchers.Main) {
                    callback(false)
                }
            }
        }
    }

    // =======================================================
    //
    // =======================================================
    fun downloadTemplate(bufferId: Int = 1, callback: (ByteArray?) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                purgeBoth()
                pacedSend(AS608Protocol.upChar(bufferId))

                val rawBuffer = ArrayList<Byte>(2048)
                val tplBuffer = ArrayList<Byte>()
                val temp = ByteArray(1024)

                var ackOk = false
                var sawFirstData = false
                var sawLastPacket = false

                val tEnd = System.currentTimeMillis() + 10_000
                var lastDataTs = System.currentTimeMillis()

                while (System.currentTimeMillis() < tEnd) {
                    val n = try { serial?.read(temp, 300) ?: 0 } catch (_: Exception) { 0 }
                    if (n > 0) {
                        for (i in 0 until n) rawBuffer.add(temp[i])
                        lastDataTs = System.currentTimeMillis()
                        val parseRes = parseIncomingStream(rawBuffer, tplBuffer)
                        if (parseRes.ackJustSeen) ackOk = true
                        if (parseRes.firstDataJustSeen && !sawFirstData) sawFirstData = true
                        if (parseRes.lastPacketSeen && !sawLastPacket) {
                            sawLastPacket = true
                            break
                        }
                    } else {
                        if (System.currentTimeMillis() - lastDataTs > 2000) break
                    }
                }

                if (ackOk && sawLastPacket && tplBuffer.isNotEmpty()) {
                    callback(tplBuffer.toByteArray())
                } else {
                    callback(null)
                }
            } catch (e: Exception) {
                Log.e("AS608", "Error descargando template: ${e.message}")
                callback(null)
            }
        }
    }

    // =======================================================
    //
    // =======================================================
    fun downloadTemplateBase64(pageId: Int, onResult: (String?) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            // 1. Cargar template desde el ID en el módulo
            sendCommand(AS608Protocol.store(1, pageId)) // Cargar en buffer 1
            val resp1 = readResponse(3000)
            val code1 = if (resp1 != null) AS608Protocol.getConfirmationCode(resp1) else -1
            if (code1 != 0x00) {
                withContext(Dispatchers.Main) {
                    onStatus?.invoke("⚠️ Error al cargar template ID=$pageId (code=$code1)")
                    onResult(null)
                }
                return@launch
            }

            // 2. Descargar el template desde buffer 1
            val templateData = CompletableDeferred<ByteArray?>()
            downloadTemplate(1) { tpl -> templateData.complete(tpl) }
            val tplBytes = templateData.await()

            if (tplBytes != null) {
                val base64 = Base64.encodeToString(tplBytes, Base64.NO_WRAP)
                withContext(Dispatchers.Main) {
                    onStatus?.invoke("✅ Template ID=$pageId descargado (${tplBytes.size} bytes)")
                    onResult(base64)
                }
            } else {
                withContext(Dispatchers.Main) {
                    onResult(null)
                }
            }
        }
    }

    // =======================================================
    //
    // =======================================================
    fun uploadTemplateBase64(base64: String, pageId: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            val tplBytes = Base64.decode(base64, Base64.NO_WRAP)

            val uploadOk = CompletableDeferred<Boolean>()
            uploadTemplate(1, tplBytes) { ok -> uploadOk.complete(ok) }
            if (!uploadOk.await()) {
                withContext(Dispatchers.Main) {
                    onStatus?.invoke("⚠️ Falló subida de template a buffer 1")
                }
                return@launch
            }

            // Guardar en memoria del lector
            storeTemplateWithResponse(pageId, 1)
        }
    }

    // =======================================================
    //   Descargar desde Ram
    // =======================================================
    fun downloadTemplateFromBufferBase64(bufferId: Int = 1, onResult: (String?) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            if (!modelReadyInRam) {
                withContext(Dispatchers.Main) {
                    onStatus?.invoke("⚠️ No hay modelo generado en RAM. Usa 'Convertir' primero.")
                    onResult(null)
                }
                return@launch
            }

            val templateData = CompletableDeferred<ByteArray?>()
            downloadTemplate(bufferId) { tpl -> templateData.complete(tpl) }
            val tplBytes = templateData.await()

            if (tplBytes != null) {
                val base64 = Base64.encodeToString(tplBytes, Base64.NO_WRAP)
                withContext(Dispatchers.Main) {
                    onStatus?.invoke("✅ Template descargado desde RAM (buffer $bufferId)")
                    onResult(base64)
                }
            } else {
                withContext(Dispatchers.Main) {
                    onStatus?.invoke("❌ No se pudo descargar template desde RAM")
                    onResult(null)
                }
            }
        }
    }
    // =======================================================
    // 🔹 7. Borrar Template
    // =======================================================

    fun deleteTemplateWithResponse(pageId: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            withContext(Dispatchers.Main) {
                onStatus?.invoke("🗑️ Borrando template ID=$pageId ...")
            }

            sendCommand(AS608Protocol.deleteTemplate(pageId))
            val resp = readResponse(4000)
            val code = if (resp != null) AS608Protocol.getConfirmationCode(resp) else -1

            withContext(Dispatchers.Main) {
                if (code == 0x00) {
                    onStatus?.invoke("✅ Template ID=$pageId borrado correctamente")
                } else {
                    onStatus?.invoke("⚠️ No se pudo borrar ID=$pageId (code=$code)")
                }
            }
        }
    }


    // =======================================================
    // 🧰 8. Utilidades
    // =======================================================
    private fun LogHex(prefix: String, data: ByteArray) {
        val hex = data.joinToString(" ") { "%02X".format(it) }
        Log.d("${TAG}_$prefix", hex)
    }

    @Volatile private var dumpRx = true
    fun setRxDump(enabled: Boolean) { dumpRx = enabled }
    private fun logRxChunk(tag: String, buf: ByteArray, n: Int) {
        if (!dumpRx || n <= 0) return
        val shown = n.coerceAtMost(256)
        val hex = (0 until shown).joinToString(" ") { "%02X".format(buf[it]) }
        val suffix = if (n > shown) " ...(+${n - shown} bytes)" else ""
        Log.d(tag, "RX[$n] $hex$suffix")
    }
}
