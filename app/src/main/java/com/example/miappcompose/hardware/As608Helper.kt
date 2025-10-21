package com.example.miappcompose.hardware

import android.content.Context
import android.graphics.Bitmap
import android.hardware.usb.UsbManager
import android.util.Log
import com.example.miappcompose.sdk.SDKResult
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class AS608Helper(private val context: Context) {

    private var serial: UsbSerialPort? = null
    private val tag = "AS608"
    private val isReading = AtomicBoolean(false)
    private val cmdMutex = Mutex()

    var onStatus: ((String) -> Unit)? = null
    var onImage: ((Bitmap) -> Unit)? = null

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

        try {
            serial?.setDTR(true); serial?.setRTS(true)
            serial?.setDTR(false); Thread.sleep(10)
            serial?.setDTR(true);  Thread.sleep(10)
            serial?.setRTS(false); Thread.sleep(10)
            serial?.setRTS(true)
        } catch (_: Exception) {}

        onStatus("🔌 AS608 conectado")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                purgeBoth()
                sendCommand(AS608Protocol.handshake())
                delay(200)
                purgeBoth()
            } catch (e: Exception) {
                Log.e(tag, "Error tras conectar: ${e.message}")
            }
        }
    }

    fun stop() {
        try {
            serial?.close()
            onStatus?.invoke("🔴 Conexión cerrada")
        } catch (e: Exception) {
            Log.e(tag, "Error al cerrar: ${e.message}")
        }
    }

    /**
     * Inicia el lector y verifica la contraseña inmediatamente.
     *
     * - Abre la conexión (start) si no está abierta.
     * - Intenta verificar la contraseña hasta `attempts` veces (espera `attemptDelayMs` entre intentos).
     * - Si se verifica, deja la conexión abierta y marca isUnlocked = true.
     * - Si NO se verifica, opcionalmente cierra la conexión (stop) y notifica por onStatus.
     *
     * Uso desde UI (Composable DisposableEffect):
     * helper.startPass(
     *   password = 0x12340000u,
     *   onStatus = { msg -> status = msg },
     *   onImage  = { bmp -> fingerprint = bmp }
     * )
     */

    private fun sendCommand(cmd: ByteArray) {
        try {
            serial?.write(cmd, 1000)
            Log.d("${tag}_TX", AS608Protocol.printHex(cmd))
        } catch (e: IOException) {
            Log.e(tag, "Error enviando: ${e.message}")
        }
    }

    private suspend fun pacedSend(cmd: ByteArray, postDelayMs: Int = 8) {
        sendCommand(cmd); delay(postDelayMs.toLong())
    }

    private fun purgeBoth() {
        try {
            serial?.purgeHwBuffers(true, true)
            drainBuffer(60)
        } catch (e: Exception) {
            Log.w(tag, "purgeBoth: ${e.message}")
        }
    }

    private fun drainBuffer(extraTimeMs: Long = 80) {
        val temp = ByteArray(1024)
        val start = System.currentTimeMillis()
        var total = 0
        while (System.currentTimeMillis() - start < extraTimeMs) {
            val len = try { serial?.read(temp, 25) ?: 0 } catch (_: Exception) { 0 }
            if (len > 0) total += len
        }
        if (total > 0) Log.w(tag, "🧹 Drenados $total bytes")
    }

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
            if (n > 0) {
                for (i in 0 until n) acc.add(buf[i])
                syncToHeader()
                if (acc.size >= 9) {
                    val pid = acc[6].toInt() and 0xFF
                    val lenH = acc[7].toInt() and 0xFF
                    val lenL = acc[8].toInt() and 0xFF
                    val totalLen = 9 + ((lenH shl 8) or lenL)
                    if (pid == (AS608Protocol.PID_ACK.toInt() and 0xFF) && acc.size >= totalLen) {
                        val out = acc.subList(0, totalLen).toByteArray()
                        Log.d("${tag}_RX_ACK", AS608Protocol.printHex(out))
                        return out
                    }
                }
            }
        }
        return null
    }

    private fun <T> resultFromAck(resp: ByteArray?, okData: T? = null): SDKResult<T> {
        val code = AS608Protocol.getConfirmationCode(resp)
        return if (code == 0x00) {
            SDKResult.ok(okData)
        } else {
            SDKResult.fail(code, AS608Protocol.confirmationMessage(code))
        }
    }

    private fun launch(block: suspend () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            cmdMutex.withLock { block() }
        }
    }

    // === API ===
    fun getParameters(onDone: (SDKResult<String>) -> Unit) = launch {
        purgeBoth()
        pacedSend(AS608Protocol.readSysParams())
        val resp = readResponse(2500)
        if (resp == null) {
            withContext(Dispatchers.Main) { onDone(SDKResult.fail(message = "Sin respuesta")) }
            return@launch
        }
        val code = AS608Protocol.getConfirmationCode(resp)
        if (code == 0x00) {
            val msg = AS608Protocol.parseSysParams(resp)
            withContext(Dispatchers.Main) { onDone(SDKResult.ok(msg, "Parámetros recibidos")) }
        } else {
            withContext(Dispatchers.Main) { onDone(SDKResult.fail(code, AS608Protocol.confirmationMessage(code))) }
        }
    }

    fun genImg(onDone: (SDKResult<Unit>) -> Unit) = launch {
        purgeBoth(); pacedSend(AS608Protocol.genImg())
        val resp = readResponse(3000)
        withContext(Dispatchers.Main) { onDone(resultFromAck(resp)) }
    }

    fun img2Tz(bufferId: Int, onDone: (SDKResult<Unit>) -> Unit) = launch {
        pacedSend(AS608Protocol.img2Tz(bufferId))
        val resp = readResponse(3000)
        withContext(Dispatchers.Main) { onDone(resultFromAck(resp)) }
    }

    fun regModel(onDone: (SDKResult<Unit>) -> Unit) = launch {
        pacedSend(AS608Protocol.regModel())
        val resp = readResponse(3000)
        withContext(Dispatchers.Main) { onDone(resultFromAck(resp)) }
    }

    fun store(bufferId: Int, pageId: Int, onDone: (SDKResult<Unit>) -> Unit) = launch {
        pacedSend(AS608Protocol.store(bufferId, pageId))
        val resp = readResponse(4000)
        withContext(Dispatchers.Main) { onDone(resultFromAck(resp)) }
    }

    fun deleteTemplateWithResponse(pageId: Int, onDone: (Boolean, Int, String) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (pageId !in 0..299) {
                    withContext(Dispatchers.Main) {
                        val msg = "⚠️ ID fuera de rango (0–299)"
                        onStatus?.invoke(msg)
                        onDone(false, 0x0D, msg)
                    }
                    return@launch
                }

                val cmd = AS608Protocol.deleteTemplate(pageId)
                pacedSend(cmd)

                val resp = readResponse(4000)
                val code = if (resp != null) AS608Protocol.getConfirmationCode(resp) else -1

                val msg = when (code) {
                    0x00 -> "🗑️ Template ID=$pageId borrado correctamente"
                    0x10 -> "⚠️ ID inexistente o fuera del rango"
                    0x0D -> "⚠️ ID fuera de rango permitido"
                    else -> "❌ Error al eliminar ID=$pageId (code=$code)"
                }

                withContext(Dispatchers.Main) {
                    onStatus?.invoke(msg)
                    onDone(code == 0x00, code, msg)
                }

            } catch (e: Exception) {
                Log.e("AS608", "Error borrando ID=$pageId: ${e.message}")
                withContext(Dispatchers.Main) {
                    onStatus?.invoke("❌ Error: ${e.message}")
                    onDone(false, -99, "❌ Error inesperado: ${e.message}")
                }
            }
        }
    }

    fun empty(onDone: (SDKResult<Unit>) -> Unit) = launch {
        purgeBoth(); pacedSend(AS608Protocol.empty())
        val resp = readResponse(4000)
        withContext(Dispatchers.Main) { onDone(resultFromAck(resp)) }
    }

    fun verifyPassword(password: UInt, onDone: (SDKResult<Unit>) -> Unit) = launch {
        val bytes = ByteArray(4) { ((password shr ((3 - it) * 8)) and 0xFFu).toByte() }
        pacedSend(AS608Protocol.verifyPassword(bytes))
        val resp = readResponse(3000)
        withContext(Dispatchers.Main) { onDone(resultFromAck(resp)) }
    }

    private fun uIntTo4BE(u: UInt): ByteArray = byteArrayOf(
        ((u shr 24) and 0xFFu).toByte(),
        ((u shr 16) and 0xFFu).toByte(),
        ((u shr  8) and 0xFFu).toByte(),
        ( u         and 0xFFu).toByte()
    )

    fun setPassword(password: UInt, onDone: (SDKResult<Unit>) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val pwd = uIntTo4BE(password)
                pacedSend(AS608Protocol.setPassword(pwd))
                val resp = readResponse(3000)
                val code = if (resp != null) AS608Protocol.getConfirmationCode(resp) else -1
                withContext(Dispatchers.Main) {
                    if (code == 0x00) onDone(SDKResult.ok(message = "🔐 Password seteada"))
                    else onDone(SDKResult.fail(code, "⚠️ Falló setPassword (code=$code)"))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onDone(SDKResult.fail(-1, "❌ setPassword: ${e.message}")) }
            }
        }
    }

    fun setSecurityLevel(level: Int, onDone: (SDKResult<Unit>) -> Unit) {
        // level: 1..5 (↑ menos FAR, ↓ más FRR, y viceversa)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                pacedSend(AS608Protocol.setSysParam(4, level))
                val resp = readResponse(2000)
                val code = AS608Protocol.getConfirmationCode(resp)
                withContext(Dispatchers.Main) {
                    if (code == 0x00) onDone(SDKResult.ok(message = "🔒 SecurityLevel=$level OK"))
                    else onDone(SDKResult.fail(code, "⚠️ setSecurityLevel fail (code=$code)"))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onDone(SDKResult.fail(-1, "❌ setSecurityLevel: ${e.message}")) }
            }
        }
    }

    fun search(onDone: (SDKResult<Pair<Int, Int>>) -> Unit) = launch {
        pacedSend(AS608Protocol.genImg())
        var resp = readResponse(4000)
        var code = AS608Protocol.getConfirmationCode(resp)
        if (code != 0x00) { withContext(Dispatchers.Main){ onDone(SDKResult.fail(code, "genImg: ${AS608Protocol.confirmationMessage(code)}")) }; return@launch }

        pacedSend(AS608Protocol.img2Tz(1))
        resp = readResponse(4000)
        code = AS608Protocol.getConfirmationCode(resp)
        if (code != 0x00) { withContext(Dispatchers.Main){ onDone(SDKResult.fail(code, "img2Tz: ${AS608Protocol.confirmationMessage(code)}")) }; return@launch }

        pacedSend(AS608Protocol.search(1, 0, 1023))
        resp = readResponse(4000)
        code = AS608Protocol.getConfirmationCode(resp)
        if (code == 0x00 && resp != null && resp.size >= 16) {
            val pageId = ((resp[10].toInt() and 0xFF) shl 8) or (resp[11].toInt() and 0xFF)
            val score = ((resp[12].toInt() and 0xFF) shl 8) or (resp[13].toInt() and 0xFF)
            withContext(Dispatchers.Main) { onDone(SDKResult.ok(pageId to score, "Match OK")) }
        } else {
            withContext(Dispatchers.Main) { onDone(SDKResult.fail(code, "No match / ${AS608Protocol.confirmationMessage(code)}")) }
        }
    }

    fun readIndexPage(pageId: Int, onDone: (SDKResult<List<Int>>) -> Unit) = launch {
        pacedSend(AS608Protocol.readIndexTable(pageId))
        val resp = readResponse(3000)
        val code = AS608Protocol.getConfirmationCode(resp)
        if (code != 0x00 || resp == null || resp.size < 42) {
            withContext(Dispatchers.Main) { onDone(SDKResult.fail(code, "IndexPage fail: ${AS608Protocol.confirmationMessage(code)}")) }
            return@launch
        }
        val indexData = resp.copyOfRange(10, 42)
        val ids = mutableListOf<Int>()
        indexData.forEachIndexed { idx, b ->
            val v = b.toInt() and 0xFF
            for (bit in 0..7) if (((v shr bit) and 1) == 1) ids += pageId * 256 + (idx * 8 + bit)
        }
        withContext(Dispatchers.Main) { onDone(SDKResult.ok(ids)) }
    }

    fun readIndexAll(onDone: (SDKResult<List<Int>>) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val allIds = mutableListOf<Int>()
                val results = mutableListOf<SDKResult<List<Int>>>()

                // 🔹 Leer la página 0
                val page0 = CompletableDeferred<SDKResult<List<Int>>>()
                readIndexPage(0) { res -> page0.complete(res) }
                val res0 = page0.await()
                results.add(res0)
                if (res0.success) allIds.addAll(res0.data ?: emptyList())
                Log.d("AS608", "Index Page 0 leída (${res0.data?.size ?: 0} huellas)")

                delay(100) // 🔸 Evita que la segunda lectura llegue demasiado rápido

                // 🔹 Leer la página 1
                val page1 = CompletableDeferred<SDKResult<List<Int>>>()
                readIndexPage(1) { res -> page1.complete(res) }
                val res1 = page1.await()
                results.add(res1)
                if (res1.success) allIds.addAll(res1.data ?: emptyList())
                Log.d("AS608", "Index Page 1 leída (${res1.data?.size ?: 0} huellas)")

                // 🔹 Combinar resultados
                val success = results.all { it.success }
                val total = allIds.size
                allIds.sort()

                withContext(Dispatchers.Main) {
                    if (success) {
                        onDone(SDKResult.ok(allIds, "📊 Total: $total huellas\n🆔 IDs: $allIds"))
                    } else {
                        onDone(SDKResult.fail(message = "⚠️ Error parcial al leer índices"))
                    }
                }

            } catch (e: Exception) {
                Log.e("AS608", "Error en readIndexAll: ${e.message}")
                withContext(Dispatchers.Main) {
                    onDone(SDKResult.fail(message = "❌ Error en lectura de índices: ${e.message}"))
                }
            }
        }
    }

    fun captureAndConvert(
        bufferId: Int,
        onDone: (Boolean, Int, String) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Paso 1: Capturar imagen
                pacedSend(AS608Protocol.genImg())
                val respImg = readResponse(3000)
                val codeImg = if (respImg != null) AS608Protocol.getConfirmationCode(respImg) else -1

                if (codeImg != 0x00) {
                    val msg = when (codeImg) {
                        0x02 -> "⚠️ No hay dedo en el sensor"
                        0x03 -> "⚠️ Imagen defectuosa"
                        0x06 -> "⚠️ Imagen demasiado desordenada"
                        else -> "❌ Error al capturar imagen (code=$codeImg)"
                    }
                    withContext(Dispatchers.Main) {
                        onStatus?.invoke(msg)
                        onDone(false, codeImg, msg)
                    }
                    return@launch
                }

                // Paso 2: Convertir imagen a características
                pacedSend(AS608Protocol.img2Tz(bufferId))
                val respTz = readResponse(3000)
                val codeTz = if (respTz != null) AS608Protocol.getConfirmationCode(respTz) else -1

                val msgTz = if (codeTz == 0x00) {
                    "✅ Imagen convertida y almacenada en buffer $bufferId"
                } else {
                    "❌ Error al convertir imagen (code=$codeTz)"
                }

                withContext(Dispatchers.Main) {
                    onStatus?.invoke(msgTz)
                    onDone(codeTz == 0x00, codeTz, msgTz)
                }

            } catch (e: Exception) {
                Log.e("AS608", "Error en captureAndConvert: ${e.message}")
                withContext(Dispatchers.Main) {
                    onStatus?.invoke("❌ Error inesperado: ${e.message}")
                    onDone(false, -99, "❌ Error inesperado: ${e.message}")
                }
            }
        }
    }

    fun registerModel(
        onDone: (Boolean, Int, String) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                pacedSend(AS608Protocol.regModel())
                val resp = readResponse(3000)
                val code = AS608Protocol.getConfirmationCode(resp)

                withContext(Dispatchers.Main) {
                    if (code == 0x00) {
                        onDone(true, code, "✅ Buffers 1 y 2 combinados correctamente (modelo en RAM)")
                    } else {
                        onDone(false, code, "❌ Error combinando buffers (code=$code)")
                    }
                }
            } catch (e: Exception) {
                Log.e("AS608", "Error en registerModel: ${e.message}")
                withContext(Dispatchers.Main) {
                    onDone(false, -1, "❌ Error: ${e.message}")
                }
            }
        }
    }

    fun storeTemplateAtId(
        pageId: Int,
        bufferId: Int = 1,
        onDone: (Boolean, Int, String) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Enviar comando STORE
                pacedSend(AS608Protocol.store(bufferId, pageId))
                val resp = readResponse(4000)
                val code = if (resp != null) AS608Protocol.getConfirmationCode(resp) else -1

                val msg = when (code) {
                    0x00 -> "💾 Template guardado exitosamente en ID $pageId"
                    0x0B -> "⚠️ No hay template en RAM para guardar"
                    0x0D -> "⚠️ ID fuera de rango"
                    0x18 -> "⚠️ Error al escribir en flash"
                    else -> "❌ Error al guardar en ID $pageId (code=$code)"
                }

                withContext(Dispatchers.Main) {
                    onStatus?.invoke(msg)
                    onDone(code == 0x00, code, msg)
                }

            } catch (e: Exception) {
                Log.e("AS608", "Error en storeTemplateAtId: ${e.message}")
                withContext(Dispatchers.Main) {
                    onStatus?.invoke("❌ Error inesperado: ${e.message}")
                    onDone(false, -99, "❌ Error inesperado: ${e.message}")
                }
            }
        }
    }

    fun downloadRam(
        bufferId: Int = 1,
        onDone: (SDKResult<String>) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                purgeBoth()
                Log.d("AS608", "📥 Solicitando template desde buffer $bufferId")
                pacedSend(AS608Protocol.upChar(bufferId), postDelayMs = 10)

                val tplBuffer = ArrayList<Byte>()
                val rawBuffer = ArrayList<Byte>(2048)
                val temp = ByteArray(512)

                var ackOk = false
                var lastPacketSeen = false
                val timeout = System.currentTimeMillis() + 8000
                var lastDataTs = System.currentTimeMillis()

                while (System.currentTimeMillis() < timeout) {
                    val n = try { serial?.read(temp, 200) ?: 0 } catch (_: Exception) { 0 }
                    if (n > 0) {
                        for (i in 0 until n) rawBuffer.add(temp[i])

                        // Analiza flujo en tiempo real
                        while (rawBuffer.size >= 9) {
                            if (!(rawBuffer[0] == 0xEF.toByte() && rawBuffer[1] == 0x01.toByte())) {
                                rawBuffer.removeAt(0)
                                continue
                            }

                            val pid = rawBuffer[6].toInt() and 0xFF
                            val len = ((rawBuffer[7].toInt() and 0xFF) shl 8) or (rawBuffer[8].toInt() and 0xFF)
                            val total = 9 + len
                            if (rawBuffer.size < total) break

                            if (pid == 0x07) ackOk = true
                            if (pid == 0x02 || pid == 0x08) {
                                val payloadLen = len - 2
                                val payloadStart = 9
                                val payloadEnd = payloadStart + payloadLen
                                for (i in payloadStart until payloadEnd) tplBuffer.add(rawBuffer[i])
                                if (pid == 0x08) lastPacketSeen = true
                            }
                            repeat(total) { rawBuffer.removeAt(0) }
                        }

                        lastDataTs = System.currentTimeMillis()
                        if (lastPacketSeen && tplBuffer.size >= 768) break
                    } else if (System.currentTimeMillis() - lastDataTs > 2500) break
                }

                if (tplBuffer.isNotEmpty()) {
                    val tplBytes = tplBuffer.toByteArray()
                    val base64 = AS608Protocol.encodeTemplateToBase64(tplBytes)
                    withContext(Dispatchers.Main) {
                        onDone(SDKResult.ok(base64, "✅ Template descargado (${tplBytes.size} bytes)"))
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        onDone(SDKResult.fail(-1, "⚠️ No se recibió template desde el buffer"))
                    }
                }

            } catch (e: Exception) {
                Log.e("AS608_RAM", "Error descargando template: ${e.message}")
                withContext(Dispatchers.Main) {
                    onDone(SDKResult.fail(-1, "❌ Error: ${e.message}"))
                }
            }
        }
    }

    fun uploadRam(
        base64: String,
        bufferId: Int = 1,
        onDone: (SDKResult<Unit>) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val tpl = AS608Protocol.decodeTemplateFromBase64(base64)
                if (tpl == null || tpl.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        onDone(SDKResult.fail(-1, "⚠️ Base64 inválido o vacío"))
                    }
                    return@launch
                }

                purgeBoth() // 🔹 Limpieza previa
                Log.d("AS608", "⬇️ Iniciando subida de template (${tpl.size} bytes) al buffer $bufferId")

                // 1️⃣ Enviar comando DownChar
                pacedSend(AS608Protocol.downChar(bufferId), postDelayMs = 10)

                // 2️⃣ Esperar ACK inicial
                val ack = readResponse(3000)
                val ackCode = if (ack != null) AS608Protocol.getConfirmationCode(ack) else -1
                Log.d("AS608", "⬇️ DownChar ACK inicial code=$ackCode")
                if (ackCode != 0x00) {
                    withContext(Dispatchers.Main) {
                        onDone(SDKResult.fail(ackCode, "⚠️ Rechazado al iniciar transferencia (code=$ackCode)"))
                    }
                    return@launch
                }

                // 3️⃣ Enviar datos sin purgar, sin leer intermedio
                val chunkSize = 128
                var offset = 0
                val total = tpl.size
                while (offset < total) {
                    val end = minOf(offset + chunkSize, total)
                    val chunk = tpl.copyOfRange(offset, end)
                    val isLast = end == total
                    val pkt = AS608Protocol.buildDataPacket(chunk, isLast)
                    serial?.write(pkt, 1000)
                    offset = end
                    delay(1) // 🕐 Pequeño respirito para FTDI
                }

                // 4️⃣ ACK final opcional (algunos firmwares no lo envían)
                val finalAck = readResponse(1500)
                val finalCode = if (finalAck != null) AS608Protocol.getConfirmationCode(finalAck) else -1
                Log.d("AS608", "⬇️ DownChar ACK final code=$finalCode")

                val success = (finalCode == 0x00) || (finalCode == -1)
                withContext(Dispatchers.Main) {
                    if (success) {
                        onDone(SDKResult.ok(message = "✅ Template subido correctamente"))
                    } else {
                        onDone(SDKResult.fail(finalCode, "⚠️ Falló ACK final (code=$finalCode)"))
                    }
                }

            } catch (e: Exception) {
                Log.e("AS608_RAM", "Error al subir template: ${e.message}")
                withContext(Dispatchers.Main) {
                    onDone(SDKResult.fail(-1, "❌ Error al subir template: ${e.message}"))
                }
            }
        }
    }

    fun downloadTemplateFromId(
        pageId: Int,
        bufferId: Int = 1,
        onDone: (SDKResult<String>) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1️⃣ Cargar el template desde la base de datos del sensor al buffer
                pacedSend(AS608Protocol.loadChar(bufferId, pageId))
                val resp = readResponse(3000)
                val code = if (resp != null) AS608Protocol.getConfirmationCode(resp) else -1
                if (code != 0x00) {
                    withContext(Dispatchers.Main) {
                        onDone(SDKResult.fail(code, "⚠️ No se pudo cargar ID=$pageId al buffer (code=$code)"))
                    }
                    return@launch
                }

                // 2️⃣ Descargar el contenido del buffer como template
                purgeBoth()
                delay(50)
                pacedSend(AS608Protocol.upChar(bufferId))

                val tplBuffer = ArrayList<Byte>()
                val rawBuffer = ArrayList<Byte>(2048)
                val temp = ByteArray(512)
                var lastDataTs = System.currentTimeMillis()
                val timeout = System.currentTimeMillis() + 8000
                var lastPacketSeen = false

                while (System.currentTimeMillis() < timeout) {
                    val n = try { serial?.read(temp, 200) ?: 0 } catch (_: Exception) { 0 }
                    if (n > 0) {
                        for (i in 0 until n) rawBuffer.add(temp[i])
                        while (rawBuffer.size >= 9) {
                            if (!(rawBuffer[0] == 0xEF.toByte() && rawBuffer[1] == 0x01.toByte())) {
                                rawBuffer.removeAt(0); continue
                            }
                            val pid = rawBuffer[6].toInt() and 0xFF
                            val len = ((rawBuffer[7].toInt() and 0xFF) shl 8) or (rawBuffer[8].toInt() and 0xFF)
                            val total = 9 + len
                            if (rawBuffer.size < total) break

                            if (pid == 0x02 || pid == 0x08) {
                                val payloadLen = len - 2
                                val payloadStart = 9
                                val payloadEnd = payloadStart + payloadLen
                                for (i in payloadStart until payloadEnd) tplBuffer.add(rawBuffer[i])
                                if (pid == 0x08) lastPacketSeen = true
                            }
                            repeat(total) { rawBuffer.removeAt(0) }
                        }

                        lastDataTs = System.currentTimeMillis()
                        if (lastPacketSeen && tplBuffer.size >= 768) break
                    } else if (System.currentTimeMillis() - lastDataTs > 3000) break
                }

                if (tplBuffer.isNotEmpty()) {
                    val tplBytes = tplBuffer.toByteArray()
                    val base64 = AS608Protocol.encodeTemplateToBase64(tplBytes)
                    withContext(Dispatchers.Main) {
                        onDone(SDKResult.ok(base64, "✅ Template ID=$pageId descargado (${tplBytes.size} bytes)"))
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        onDone(SDKResult.fail(-1, "⚠️ No se recibió template desde ID=$pageId"))
                    }
                }

            } catch (e: Exception) {
                Log.e("AS608", "Error en downloadTemplateFromId: ${e.message}")
                withContext(Dispatchers.Main) {
                    onDone(SDKResult.fail(-1, "❌ Error: ${e.message}"))
                }
            }
        }
    }

    fun uploadTemplateToId(
        base64: String,
        pageId: Int,
        bufferId: Int = 1,
        onDone: (SDKResult<Unit>) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val tplBytes = AS608Protocol.decodeTemplateFromBase64(base64)
                if (tplBytes == null || tplBytes.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        onDone(SDKResult.fail(-1, "⚠️ Template vacío o Base64 inválido"))
                    }
                    return@launch
                }

                // 🧹 Limpieza y subida a RAM
                purgeBoth()
                Log.d("AS608", "📤 Subiendo template del servidor (${tplBytes.size} bytes) al buffer $bufferId")

                // 1️⃣ Subir a RAM
                pacedSend(AS608Protocol.downChar(bufferId), postDelayMs = 10)
                val ack = readResponse(3000)
                val ackCode = if (ack != null) AS608Protocol.getConfirmationCode(ack) else -1
                if (ackCode != 0x00) {
                    withContext(Dispatchers.Main) {
                        onDone(SDKResult.fail(ackCode, "⚠️ Falló al iniciar carga al buffer (code=$ackCode)"))
                    }
                    return@launch
                }

                val chunkSize = 128
                var offset = 0
                while (offset < tplBytes.size) {
                    val end = minOf(offset + chunkSize, tplBytes.size)
                    val chunk = tplBytes.copyOfRange(offset, end)
                    val isLast = end == tplBytes.size
                    val pkt = AS608Protocol.buildDataPacket(chunk, isLast)
                    serial?.write(pkt, 1000)
                    offset = end
                    delay(1)
                }

                val finalAck = readResponse(1500)
                val finalCode = if (finalAck != null) AS608Protocol.getConfirmationCode(finalAck) else -1
                val uploaded = (finalCode == 0x00 || finalCode == -1)
                if (!uploaded) {
                    withContext(Dispatchers.Main) {
                        onDone(SDKResult.fail(finalCode, "⚠️ Falló la carga del template a RAM (code=$finalCode)"))
                    }
                    return@launch
                }

                // 2️⃣ Guardar en el ID
                pacedSend(AS608Protocol.store(bufferId, pageId))
                val resp = readResponse(3000)
                val code = if (resp != null) AS608Protocol.getConfirmationCode(resp) else -1
                withContext(Dispatchers.Main) {
                    if (code == 0x00) {
                        onDone(SDKResult.ok(message = "✅ Template cargado del servidor y guardado en ID=$pageId"))
                    } else {
                        onDone(SDKResult.fail(code, "⚠️ Falló al guardar en ID=$pageId (code=$code)"))
                    }
                }

            } catch (e: Exception) {
                Log.e("AS608", "Error al subir template del servidor: ${e.message}")
                withContext(Dispatchers.Main) {
                    onDone(SDKResult.fail(-1, "❌ Error: ${e.message}"))
                }
            }
        }
    }

    fun startPass(
        password: UInt = 0x12340000u,
        baudrate: Int = 57600,
        onStatus: (String) -> Unit,
        onImage: (Bitmap) -> Unit
    ) {
        // abre puerto, setea params, handshake básico
        start(onStatus, onImage)

        CoroutineScope(Dispatchers.IO).launch {
            delay(800) // pequeño boot time

            // Intenta directamente verifyPassword en la dirección actual (por si ya quedó fijada)
            verifyPassword(password) { res ->
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                if (res.success) {
                    handler.post { onStatus("🔓 Lector desbloqueado") }
                } else {
                    // Si falla, intenta recuperación por broadcast
                    recoverSensor(password) { rec ->
                        if (rec.success) {
                            handler.post { onStatus("🔓 Desbloqueado vía broadcast (${rec.message})") }
                        } else {
                            handler.post {
                                onStatus("🔒 No se pudo desbloquear: ${rec.message}")
                                stop()
                            }
                        }
                    }
                }
            }
        }
    }

    fun recoverSensor(
        password: UInt = 0x00000000u, // ajusta si tu módulo ya tiene password
        onDone: (SDKResult<Unit>) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1) Forzar broadcast
                AS608Protocol.setTargetAddress(0xFFFFFFFFu)
                purgeBoth()

                // 2) VerifyPassword con broadcast
                pacedSend(AS608Protocol.verifyPassword(
                    byteArrayOf(
                        ((password shr 24) and 0xFFu).toByte(),
                        ((password shr 16) and 0xFFu).toByte(),
                        ((password shr 8) and 0xFFu).toByte(),
                        (password and 0xFFu).toByte()
                    )
                ), postDelayMs = 10)

                val vResp = readResponse(3000)
                val vCode = if (vResp != null) AS608Protocol.getConfirmationCode(vResp) else -1
                if (vCode != 0x00) {
                    withContext(Dispatchers.Main) {
                        onStatus?.invoke("🔒 VerifyPassword falló en broadcast (code=$vCode)")
                        onDone(SDKResult.fail(vCode, "VerifyPassword (broadcast) falló"))
                    }
                    return@launch
                }

                // 3) ReadSysParams para conocer la dirección real (viene en el HEADER)
                pacedSend(AS608Protocol.readSysParams(), postDelayMs = 10)
                val sResp = readResponse(3000)
                if (sResp == null) {
                    withContext(Dispatchers.Main) {
                        onStatus?.invoke("⚠️ ReadSysParams sin respuesta")
                        onDone(SDKResult.fail(-1, "Sin respuesta en ReadSysParams"))
                    }
                    return@launch
                }

                val realAddr = AS608Protocol.addressFromHeader(sResp)
                if (realAddr == null || realAddr == 0xFFFFFFFFu) {
                    withContext(Dispatchers.Main) {
                        onStatus?.invoke("⚠️ No pude deducir dirección real")
                        onDone(SDKResult.fail(-1, "No se pudo deducir dirección real"))
                    }
                    return@launch
                }

                // 4) Fijar dirección real y devolver OK
                AS608Protocol.setTargetAddress(realAddr)
                withContext(Dispatchers.Main) {
                    onStatus?.invoke("✅ Dirección del módulo: %08X".format(realAddr.toLong()))
                    onDone(SDKResult.ok(message = "Recover OK — addr=%08X".format(realAddr.toLong())))
                }
            } catch (e: Exception) {
                Log.e("AS608", "recoverSensor error: ${e.message}")
                withContext(Dispatchers.Main) {
                    onDone(SDKResult.fail(-1, "Error en recoverSensor: ${e.message}"))
                }
            }
        }
    }


    fun getImage(onDone: (SDKResult<Bitmap>) -> Unit) = launch {
        if (isReading.get()) { withContext(Dispatchers.Main){ onDone(SDKResult.fail(message = "Lectura en curso")) }; return@launch }
        isReading.set(true)
        try {
            purgeBoth(); pacedSend(AS608Protocol.genImg())
            val r0 = readResponse(2500); val c0 = AS608Protocol.getConfirmationCode(r0)
            if (c0 != 0x00) { withContext(Dispatchers.Main){ onDone(SDKResult.fail(c0, "No se detectó huella")) }; return@launch }

            pacedSend(AS608Protocol.upImage(), 2)

            val raw = ArrayList<Byte>(64*1024)
            val img = ArrayList<Byte>(128*288)
            val tmp = ByteArray(4096)
            var sawLast = false
            val end = System.currentTimeMillis() + 12_000
            var lastTs = System.currentTimeMillis()
            while (System.currentTimeMillis() < end) {
                val n = try { serial?.read(tmp, 300) ?: 0 } catch (_: Exception) { 0 }
                if (n > 0) {
                    for (i in 0 until n) raw.add(tmp[i])
                    lastTs = System.currentTimeMillis()
                    while (raw.size >= 9) {
                        if (!(raw[0]==0xEF.toByte() && raw[1]==0x01.toByte())) { raw.removeAt(0); continue }
                        if (raw.size < 9) break
                        val pid = raw[6].toInt() and 0xFF
                        val len = ((raw[7].toInt() and 0xFF) shl 8) or (raw[8].toInt() and 0xFF)
                        val total = 9 + len
                        if (raw.size < total) break
                        val payLen = len - 2
                        val start = 9
                        val endIdx = start + payLen
                        if (pid == (AS608Protocol.PID_DATA.toInt() and 0xFF) || pid == (AS608Protocol.PID_DATA_END.toInt() and 0xFF)) {
                            for (i in start until endIdx) img.add(raw[i])
                            if (pid == (AS608Protocol.PID_DATA_END.toInt() and 0xFF)) sawLast = true
                        }
                        repeat(total) { raw.removeAt(0) }
                    }
                    if (sawLast) break
                } else if (System.currentTimeMillis() - lastTs > 1800) break
            }
            val expected = 128*288
            val final = when {
                img.size >= expected -> img.subList(0, expected).toByteArray()
                else -> {
                    val out = ByteArray(expected)
                    for (i in img.indices) out[i]=img[i]
                    val pad = if (img.isNotEmpty()) img.last() else 0
                    for (i in img.size until expected) out[i]=pad
                    out
                }
            }
            val bmp = toBitmap(final)
            withContext(Dispatchers.Main){ onDone(SDKResult.ok(bmp, "Imagen lista")) }
        } catch (e: Exception) {
            withContext(Dispatchers.Main){ onDone(SDKResult.fail(message="Error: ${e.message}")) }
        } finally {
            isReading.set(false)
        }
    }

    private fun toBitmap(gray: ByteArray): Bitmap {
        val w=128; val h=288; val total=w*h
        val data = if (gray.size>=total) gray else {
            val out=ByteArray(total); System.arraycopy(gray,0,out,0,gray.size)
            val pad = if (gray.isNotEmpty()) gray.last() else 0
            for (i in gray.size until total) out[i]=pad
            out
        }
        val bmp = Bitmap.createBitmap(w,h, Bitmap.Config.ARGB_8888)
        var i=0
        for (y in 0 until h) for (x in 0 until w) {
            val g = data[i++].toInt() and 0xFF
            val p = 0xFF shl 24 or (g shl 16) or (g shl 8) or g
            bmp.setPixel(x,y,p)
        }
        return bmp
    }
}
