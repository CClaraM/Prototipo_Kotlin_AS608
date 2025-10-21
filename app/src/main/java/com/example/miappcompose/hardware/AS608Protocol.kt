package com.example.miappcompose.hardware

object AS608Protocol {
    @Volatile private var targetAddress: UInt = 0xFFFFFFFFu
    private val HEADER = byteArrayOf(0xEF.toByte(), 0x01.toByte())
    private val ADDRESS = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
    const val CMD_SET_ADDR: Byte = 0x15
    const val PID_COMMAND: Byte = 0x01
    const val PID_DATA: Byte = 0x02
    const val PID_ACK: Byte = 0x07
    const val PID_DATA_END: Byte = 0x08
    const val CMD_HANDSHAKE: Byte = 0x17
    const val CMD_READ_SYS_PARA: Byte = 0x0F
    const val CMD_GEN_IMAGE: Byte = 0x01
    const val CMD_IMG2TZ: Byte = 0x02
    const val CMD_SEARCH: Byte = 0x04
    const val CMD_REG_MODEL: Byte = 0x05
    const val CMD_STORE: Byte = 0x06
    const val CMD_EMPTY: Byte = 0x0D
    const val CMD_UP_IMAGE: Byte = 0x0A
    const val CMD_CANCEL: Byte = 0x30
    const val CMD_UP_CHAR: Byte = 0x08
    const val CMD_DOWN_CHAR: Byte = 0x09
    const val CMD_READ_INDEX_TABLE: Byte = 0x1F
    const val CMD_DELETE_TEMPLATE: Byte = 0x0C
    const val CMD_SET_PASSWORD: Byte = 0x12
    const val CMD_VERIFY_PASSWORD: Byte = 0x13
    const val CMD_SET_SYS_PARAM: Byte = 0x0E

    fun buildCommand(cmd: Byte, payload: ByteArray = byteArrayOf()): ByteArray {
        val length = payload.size + 3
        val out = ArrayList<Byte>()
        out += HEADER.toList()
        out += ADDRESS.toList()
        out += PID_COMMAND
        out += ((length shr 8) and 0xFF).toByte()
        out += (length and 0xFF).toByte()
        out += cmd
        out += payload.toList()

        var sum = (PID_COMMAND.toInt() and 0xFF) + (length shr 8) + (length and 0xFF) + (cmd.toInt() and 0xFF)
        payload.forEach { sum += (it.toInt() and 0xFF) }
        val chk = sum and 0xFFFF
        out += ((chk shr 8) and 0xFF).toByte()
        out += (chk and 0xFF).toByte()
        return out.toByteArray()
    }
    fun buildDataPacket(payload: ByteArray, isLast: Boolean): ByteArray {
        val pid = if (isLast) PID_DATA_END else PID_DATA
        val length = payload.size + 2
        val out = ArrayList<Byte>()
        out += HEADER.toList()
        out += ADDRESS.toList()
        out += pid
        out += ((length shr 8) and 0xFF).toByte()
        out += (length and 0xFF).toByte()
        out += payload.toList()

        var sum = (pid.toInt() and 0xFF) + (length shr 8) + (length and 0xFF)
        payload.forEach { sum += (it.toInt() and 0xFF) }
        val chk = sum and 0xFFFF
        out += ((chk shr 8) and 0xFF).toByte()
        out += (chk and 0xFF).toByte()
        return out.toByteArray()
    }
    fun getConfirmationCode(data: ByteArray?): Int {
        if (data == null || data.size < 12) return -1
        if (data[0] != 0xEF.toByte() || data[1] != 0x01.toByte()) return -1
        if (data[6] != PID_ACK) return -1
        return data[9].toInt() and 0xFF
    }
    fun confirmationMessage(code: Int): String = when (code) {
        0x00 -> ""
        0x01 -> "Error de paquete"
        0x02 -> "No se detectó huella"
        0x03 -> "Imagen muy desordenada"
        0x06 -> "Imagen demasiado corta"
        0x07 -> "Imagen demasiado larga"
        0x09 -> "No coinciden"
        0x0A -> "No encontrado"
        0x10 -> "Argumento inválido / Fuera de rango"
        else -> "Código desconocido: 0x${code.toString(16)}"
    }
    fun handshake(): ByteArray = buildCommand(CMD_HANDSHAKE)

    fun setTargetAddress(addr: UInt) {
        targetAddress = addr
    }

    private fun uIntToBytesBE(u: UInt): ByteArray =
        byteArrayOf(
            ((u shr 24) and 0xFFu).toByte(),
            ((u shr 16) and 0xFFu).toByte(),
            ((u shr 8) and 0xFFu).toByte(),
            (u and 0xFFu).toByte()
        )

    fun getTargetAddress(): UInt = targetAddress

    fun setModuleAddress(newAddr: UInt): ByteArray =
        buildCommand(CMD_SET_ADDR, uIntToBytesBE(newAddr))

    fun addressFromHeader(data: ByteArray): UInt? {
        if (data.size < 7 || data[0] != 0xEF.toByte() || data[1] != 0x01.toByte()) return null
        val b2 = (data[2].toInt() and 0xFF)
        val b3 = (data[3].toInt() and 0xFF)
        val b4 = (data[4].toInt() and 0xFF)
        val b5 = (data[5].toInt() and 0xFF)
        return ((b2 shl 24) or (b3 shl 16) or (b4 shl 8) or b5).toUInt()
    }

    fun readSysParams(): ByteArray = buildCommand(CMD_READ_SYS_PARA)
    fun setSysParam(paramNo: Int, value: Int): ByteArray {
        return buildCommand(
            CMD_SET_SYS_PARAM,
            byteArrayOf(paramNo.toByte(), value.toByte())
        )
    }
    fun genImg(): ByteArray = buildCommand(CMD_GEN_IMAGE)
    fun img2Tz(bufferId: Int): ByteArray = buildCommand(CMD_IMG2TZ, byteArrayOf(bufferId.toByte()))
    fun empty(): ByteArray = buildCommand(CMD_EMPTY)
    fun regModel(): ByteArray = buildCommand(CMD_REG_MODEL)
    fun store(bufferId: Int, pageId: Int): ByteArray =
        buildCommand(CMD_STORE, byteArrayOf(bufferId.toByte(), ((pageId shr 8) and 0xFF).toByte(), (pageId and 0xFF).toByte()))
    fun search(bufferId: Int, startPage: Int, pageNum: Int): ByteArray =
        buildCommand(CMD_SEARCH, byteArrayOf(
            bufferId.toByte(),
            ((startPage shr 8) and 0xFF).toByte(), (startPage and 0xFF).toByte(),
            ((pageNum shr 8) and 0xFF).toByte(), (pageNum and 0xFF).toByte()
        ))
    fun upImage(): ByteArray = buildCommand(CMD_UP_IMAGE)
    fun cancel(): ByteArray = buildCommand(CMD_CANCEL)
    fun upChar(bufferId: Int): ByteArray = buildCommand(CMD_UP_CHAR, byteArrayOf(bufferId.toByte()))
    fun downChar(bufferId: Int): ByteArray = buildCommand(CMD_DOWN_CHAR, byteArrayOf(bufferId.toByte()))
    fun loadChar(bufferId: Int = 1, pageId: Int): ByteArray {
        val pidHigh = (pageId shr 8).toByte()
        val pidLow = (pageId and 0xFF).toByte()
        return buildCommand(0x07, byteArrayOf(bufferId.toByte(), pidHigh, pidLow))
    }
    fun readIndexTable(pageId: Int): ByteArray = buildCommand(CMD_READ_INDEX_TABLE, byteArrayOf(pageId.toByte()))
    fun deleteTemplate(pageId: Int, count: Int = 1): ByteArray {
        require(pageId in 0..299) { "pageId fuera de rango (0–299)" }
        require(count in 1..(300 - pageId)) { "count fuera de rango válido" }
        val payload = byteArrayOf(
            ((pageId shr 8) and 0xFF).toByte(),  // ID alto
            (pageId and 0xFF).toByte(),          // ID bajo
            ((count shr 8) and 0xFF).toByte(),   // Num alto (normalmente 0x00)
            (count and 0xFF).toByte()            // Num bajo (normalmente 0x01)
        )
        return buildCommand(CMD_DELETE_TEMPLATE, payload)
    }
    fun setPassword(bytes4: ByteArray): ByteArray = buildCommand(CMD_SET_PASSWORD, bytes4)
    fun verifyPassword(bytes4: ByteArray): ByteArray = buildCommand(CMD_VERIFY_PASSWORD, bytes4)
    fun parseSysParams(data: ByteArray): String {
        if (data.size < 28) return "Respuesta inválida (${data.size} bytes)"
        val cap = ((data[17].toInt() and 0xFF) shl 8) or (data[18].toInt() and 0xFF)
        val pktCode = data[23].toInt() and 0xFF
        val pktSize = when (pktCode) { 0 -> 32; 1 -> 64; 2 -> 128; 3 -> 256; else -> -1 }
        val sec = data[24].toInt() and 0xFF
        val addr = data.slice(2..5).joinToString(" ") { "%02X".format(it) }
        return "Dirección: $addr Capacidad: $cap Tamaño de paquete: $pktSize Nivel de seguridad: $sec Bytes: ${data.size}"
    }
    fun encodeTemplateToBase64(tpl: ByteArray): String =
        android.util.Base64.encodeToString(tpl, android.util.Base64.NO_WRAP)
    fun decodeTemplateFromBase64(b64: String): ByteArray =
        android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
    fun printHex(data: ByteArray): String = data.joinToString(" ") { "%02X".format(it) }
}
