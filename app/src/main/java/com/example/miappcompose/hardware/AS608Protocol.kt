package com.example.miappcompose.hardware

object AS608Protocol {

    // =======================================================
    // 🧭 1. Definiciones base: encabezados, direcciones y PID
    // =======================================================
    private const val HEADER: Long = 0xEF01
    private val ADDRESS = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())

    const val PACKAGE_COMMAND: Byte = 0x01  // Paquete de comando
    const val PACKAGE_ACK: Byte = 0x07      // Paquete de respuesta corta (ACK)
    const val PACKAGE_DATA: Byte = 0x02     // Paquete de datos intermedio
    const val PACKAGE_END: Byte = 0x08      // Paquete de datos final


    // =======================================================
    // 🧰 2. Códigos de comandos del lector AS608
    // =======================================================
    // 🔹 Comunicación básica
    const val CMD_HANDSHAKE: Byte = 0x17
    const val CMD_CANCEL: Byte = 0x30

    // 🔹 Información del sistema
    const val CMD_READ_SYS_PARA: Byte = 0x0F

    // 🔹 Imagen
    const val CMD_GEN_IMAGE: Byte = 0x01
    const val CMD_UP_IMAGE: Byte = 0x0A

    // 🔹 Template (huellas)
    const val CMD_IMG2TZ: Byte = 0x02
    const val CMD_REG_MODEL: Byte = 0x05
    const val CMD_STORE: Byte = 0x06
    const val CMD_UP_CHAR: Byte = 0x08
    const val CMD_DOWN_CHAR: Byte = 0x09

    // 🔹 Búsqueda y administración
    const val CMD_SEARCH: Byte = 0x04
    const val CMD_EMPTY: Byte = 0x0D


    // =======================================================
    // 🏗️ 3. Construcción de paquetes (Commands & Data)
    // =======================================================

    /**
     * 🧾 Construye un paquete de comando estándar.
     *
     * Formato:
     * EF 01 | Addr(4) | 0x01 | lenH lenL | CMD | payload | chkH chkL
     */
    fun buildCommand(cmd: Byte, payload: ByteArray = byteArrayOf()): ByteArray {
        val length = (payload.size + 3).toShort()
        val packet = mutableListOf<Byte>()

        // Encabezado + dirección
        packet.addAll(byteArrayOf(0xEF.toByte(), 0x01).toList())
        packet.addAll(ADDRESS.toList())

        // PID + Longitud + Comando + Payload
        packet.add(PACKAGE_COMMAND)
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

    /**
     * 📦 Construye un paquete de datos (para upload/download de templates).
     *
     * Formato:
     * EF 01 | Addr(4) | PID | lenH lenL | payload | chkH chkL
     */
    fun buildDataPacket(payload: ByteArray, isLast: Boolean): ByteArray {
        val pid: Byte = if (isLast) PACKAGE_END else PACKAGE_DATA
        val length = payload.size + 2 // checksum
        val packet = mutableListOf<Byte>()

        packet.addAll(byteArrayOf(0xEF.toByte(), 0x01.toByte()).toList())
        packet.addAll(ADDRESS.toList())

        packet.add(pid)
        packet.add(((length shr 8) and 0xFF).toByte())
        packet.add((length and 0xFF).toByte())
        packet.addAll(payload.toList())

        var sum = (pid.toInt() and 0xFF) + ((length shr 8) and 0xFF) + (length and 0xFF)
        payload.forEach { b -> sum += (b.toInt() and 0xFF) }
        val chk = sum and 0xFFFF
        packet.add(((chk shr 8) and 0xFF).toByte())
        packet.add((chk and 0xFF).toByte())

        return packet.toByteArray()
    }


    // =======================================================
    // 🧠 4. Comandos de alto nivel
    // =======================================================

    // --- Comunicación ---
    fun handshake(): ByteArray = buildCommand(CMD_HANDSHAKE)
    fun cancel(): ByteArray = buildCommand(CMD_CANCEL)

    // --- Información ---
    fun readSysParams(): ByteArray = buildCommand(CMD_READ_SYS_PARA)

    // --- Imagen ---
    fun genImg(): ByteArray = buildCommand(CMD_GEN_IMAGE)
    fun upImage(): ByteArray = buildCommand(CMD_UP_IMAGE)

    // --- Template ---
    fun img2Tz(bufferId: Int = 1): ByteArray =
        buildCommand(CMD_IMG2TZ, byteArrayOf(bufferId.toByte()))

    fun regModel(): ByteArray = buildCommand(CMD_REG_MODEL)

    fun store(bufferId: Int = 1, pageId: Int = 1): ByteArray {
        val payload = byteArrayOf(
            bufferId.toByte(),
            ((pageId shr 8) and 0xFF).toByte(),
            (pageId and 0xFF).toByte()
        )
        return buildCommand(CMD_STORE, payload)
    }

    fun upChar(bufferId: Int = 1): ByteArray =
        buildCommand(CMD_UP_CHAR, byteArrayOf(bufferId.toByte()))

    fun downChar(bufferId: Int = 1): ByteArray =
        buildCommand(CMD_DOWN_CHAR, byteArrayOf(bufferId.toByte()))

    // --- Búsqueda y administración ---
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


    // =======================================================
    // 🧾 5. Parsing de respuestas y ACK
    // =======================================================

    /**
     * 📡 Extrae el código de confirmación de un paquete ACK.
     * Retorna -1 si el paquete no es válido.
     */
    fun getConfirmationCode(data: ByteArray): Int {
        if (data.size < 12) return -1
        if (data[0] != 0xEF.toByte() || data[1] != 0x01.toByte()) return -1
        if (data[6] != PACKAGE_ACK) return -1
        return data[9].toInt() and 0xFF
    }

    fun isValidAck(data: ByteArray): Boolean =
        data.size >= 12 &&
                data[0] == 0xEF.toByte() &&
                data[1] == 0x01.toByte() &&
                data[6] == PACKAGE_ACK

    /**
     * 📝 Traduce código numérico de confirmación a mensaje legible.
     */
    fun confirmationMessage(code: Int): String = when (code) {
        0x00 -> "" // Éxito silencioso
        0x01 -> "❌ Error de paquete"
        0x02 -> "⚠️ No se detectó huella"
        0x03 -> "❌ Imagen muy desordenada"
        0x06 -> "❌ Imagen demasiado corta"
        0x07 -> "❌ Imagen demasiado larga"
        0x09 -> "❌ No coinciden"
        else -> "⚠️ Código desconocido: 0x${code.toString(16).uppercase()}"
    }

    /**
     * 🧠 Parsea la respuesta del comando ReadSysParams (0x0F)
     */
    fun parseSysParams(data: ByteArray): String {
        if (data.size < 28) return "❌ Respuesta inválida (${data.size} bytes)"

        val capacity = ((data[17].toInt() and 0xFF) shl 8) or (data[18].toInt() and 0xFF)
        val packetSizeCode = data[23].toInt() and 0xFF
        val packetSize = when (packetSizeCode) {
            0 -> 32
            1 -> 64
            2 -> 128
            3 -> 256
            else -> -1
        }
        val securityLevel = data[24].toInt() and 0xFF
        val deviceAddress = data.slice(2..5).joinToString(" ") { "%02X".format(it) }

        return """
            📡 Parámetros del lector:
            • Dirección: $deviceAddress
            • Capacidad: $capacity huellas
            • Tamaño de paquete: $packetSize bytes
            • Nivel de seguridad: $securityLevel
            • Bytes recibidos: ${data.size}
        """.trimIndent()
    }

    fun parseResponse(data: ByteArray): String? {
        val code = getConfirmationCode(data)
        return if (code >= 0) {
            val msg = confirmationMessage(code)
            if (msg.isEmpty()) null else msg
        } else null
    }


    // =======================================================
    // 🧪 6. Utilidades auxiliares para depuración
    // =======================================================
    fun printHex(data: ByteArray): String =
        data.joinToString(" ") { "%02X".format(it) }
}
