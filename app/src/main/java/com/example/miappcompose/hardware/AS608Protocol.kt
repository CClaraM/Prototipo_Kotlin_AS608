package com.example.miappcompose.hardware

object AS608Protocol {

    // =======================================================
    // 🔹 1. Encabezado, direcciones y códigos base
    // =======================================================
    private const val HEADER: Long = 0xEF01
    private val ADDRESS = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())

    const val PACKAGE_COMMAND: Byte = 0x01
    const val PACKAGE_ACK: Byte = 0x07
    const val PACKAGE_DATA: Byte = 0x02
    const val PACKAGE_END: Byte = 0x08


    // =======================================================
    // 🔹 2. Códigos de comandos
    // =======================================================
    const val CMD_HANDSHAKE: Byte = 0x17
    const val CMD_GEN_IMAGE: Byte = 0x01
    const val CMD_IMG2TZ: Byte = 0x02
    const val CMD_SEARCH: Byte = 0x04
    const val CMD_REG_MODEL: Byte = 0x05
    const val CMD_STORE: Byte = 0x06
    const val CMD_EMPTY: Byte = 0x0D
    const val CMD_UP_IMAGE: Byte = 0x0A
    const val CMD_CANCEL: Byte = 0x30  // Cancelar transmisión de imagen o proceso actual
    const val CMD_UP_CHAR: Byte = 0x08   // descarga template desde módulo (host lee)
    const val CMD_DOWN_CHAR: Byte = 0x09 // sube template hacia el módulo (host envía)

    // =======================================================
    // 🔹 3. Builder de paquetes
    // =======================================================
    fun buildCommand(cmd: Byte, payload: ByteArray = byteArrayOf()): ByteArray {
        val length = (payload.size + 3).toShort()
        val packet = mutableListOf<Byte>()

        // Encabezado y dirección
        packet.addAll(byteArrayOf(0xEF.toByte(), 0x01).toList())
        packet.addAll(ADDRESS.toList())
        packet.add(PACKAGE_COMMAND) // Paquete de comando
        packet.add((length.toInt() shr 8).toByte())
        packet.add((length.toInt() and 0xFF).toByte())
        packet.add(cmd)
        packet.addAll(payload.toList())

        // Checksum
        val checksum =
            (cmd + payload.sumOf { it.toInt() and 0xFF } + PACKAGE_COMMAND +
                    (length.toInt() shr 8) + (length.toInt() and 0xFF)).toShort()
        packet.add((checksum.toInt() shr 8).toByte())
        packet.add((checksum.toInt() and 0xFF).toByte())

        return packet.toByteArray()
    }

    // Formato: EF 01 | addr[4] | pid | lenH lenL | payload | checksumH checksumL
    // pid: 0x02 (DATA) o 0x08 (LAST DATA)
    fun buildDataPacket(payload: ByteArray, isLast: Boolean): ByteArray {
        val pid: Byte = if (isLast) 0x08 else 0x02
        val length = payload.size + 2 // incluye checksum
        val packet = mutableListOf<Byte>()

        // Header y dirección
        packet.addAll(byteArrayOf(0xEF.toByte(), 0x01.toByte()).toList())
        packet.addAll(ADDRESS.toList())

        // PID, longitud
        packet.add(pid)
        packet.add(((length shr 8) and 0xFF).toByte())
        packet.add((length and 0xFF).toByte())

        // Payload
        packet.addAll(payload.toList())

        // Checksum = pid + lenH + lenL + sum(payload)
        var sum = (pid.toInt() and 0xFF) + ((length shr 8) and 0xFF) + (length and 0xFF)
        payload.forEach { b -> sum += (b.toInt() and 0xFF) }
        val chk = sum and 0xFFFF
        packet.add(((chk shr 8) and 0xFF).toByte())
        packet.add((chk and 0xFF).toByte())

        return packet.toByteArray()
    }

    fun getConfirmationCode(data: ByteArray): Int {
        // Validación mínima de paquete ACK: EF 01 | addr[4] | 0x07 | lenH lenL | code | chkH chkL
        if (data.size < 12) return -1
        if (data[0] != 0xEF.toByte() || data[1] != 0x01.toByte()) return -1
        if (data[6] != 0x07.toByte()) return -1
        return data[9].toInt() and 0xFF
    }

    // =======================================================
    // 🔹 4. Comandos de alto nivel
    // =======================================================
    fun handshake(): ByteArray = buildCommand(CMD_HANDSHAKE)
    fun genImg(): ByteArray = buildCommand(CMD_GEN_IMAGE)
    fun img2Tz(bufferId: Int = 1): ByteArray = buildCommand(CMD_IMG2TZ, byteArrayOf(bufferId.toByte()))
    fun regModel(): ByteArray = buildCommand(CMD_REG_MODEL)
    fun store(bufferId: Int = 1, pageId: Int = 1): ByteArray {
        val payload = byteArrayOf(
            bufferId.toByte(),
            ((pageId shr 8) and 0xFF).toByte(),
            (pageId and 0xFF).toByte()
        )
        return buildCommand(CMD_STORE, payload)
    }
    fun search(bufferId: Int = 1, startPage: Int = 0, pageNum: Int = 0x00A3): ByteArray {
        val payload = byteArrayOf(
            bufferId.toByte(),
            ((startPage shr 8) and 0xFF).toByte(),
            (startPage and 0xFF).toByte(),
            ((pageNum shr 8) and 0xFF).toByte(),
            (pageNum and 0xFF).toByte()
        )
        return buildCommand(CMD_SEARCH, payload)
    }
    fun empty(): ByteArray = buildCommand(CMD_EMPTY)
    fun upImage(): ByteArray = buildCommand(CMD_UP_IMAGE)
    fun cancel(): ByteArray = buildCommand(CMD_CANCEL)
    fun upChar(bufferId: Int =1): ByteArray = buildCommand(CMD_UP_CHAR, byteArrayOf(bufferId.toByte()))
    fun downChar(bufferId: Int = 1): ByteArray = buildCommand(CMD_DOWN_CHAR, byteArrayOf(bufferId.toByte()))

    // =======================================================
    // 🔹 5. Parsing de respuestas
    // =======================================================
    fun parseResponse(data: ByteArray): String? {
        val code = getConfirmationCode(data)
        return if (code >= 0) {
            val msg = confirmationMessage(code)
            if (msg.isEmpty()) null else msg
        } else null
    }

    // --- Utilidades de ACK / respuesta corta ---
    fun isValidAck(data: ByteArray): Boolean {
        return data.size >= 12 &&
                data[0] == 0xEF.toByte() && data[1] == 0x01.toByte() && // header
                data[6] == PACKAGE_ACK                                // paquete de respuesta
    }

    /** Devuelve el código de confirmación (0x00 éxito, 0x02 sin huella, etc.)
     *  Retorna -1 si el paquete no es válido. */
    /** fun getConfirmationCode(data: ByteArray): Int {
        if (data.size < 12) return -1  // 👈 evita lecturas cortas falsas

        if (data[0] == 0xEF.toByte() && data[1] == 0x01.toByte() && data[6] == 0x07.toByte()) {
            return data[9].toInt() and 0xFF
        }
        return -1
    } */

    /** (Opcional) Traducción a texto del código numérico. */
    fun confirmationMessage(code: Int): String = when (code) {
        0x00 -> "✅ Huella detectada" // ⬅️ No mostrar nada en éxito
        0x01 -> "❌ Error de paquete"
        0x02 -> "⚠️ No se detectó huella"
        0x03 -> "❌ Imagen muy desordenada"
        0x06 -> "❌ Imagen demasiado corta"
        0x07 -> "❌ Imagen demasiado larga"
        0x09 -> "❌ No coinciden"
        else -> "⚠️ Código desconocido: 0x${code.toString(16).uppercase()}"
    }

    // =======================================================
    // 🔹 6. (Opcional) utilidades extra
    // =======================================================
    fun printHex(data: ByteArray): String =
        data.joinToString(" ") { "%02X".format(it) }
}
