package io.ymusic.desktop.media

import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

@JvmInline
internal value class ObjectPath(val path: String)

/** A D-Bus variant: [value] marshalled as the single complete type [signature]. */
internal data class Variant(val signature: String, val value: Any?)

internal class DBusMessage(
    val type: Int,
    val flags: Int,
    val serial: Int,
    val path: String?,
    val iface: String?,
    val member: String?,
    val errorName: String?,
    val replySerial: Int?,
    val sender: String?,
    val signature: String,
    val body: List<Any?>,
) {
    val expectsReply: Boolean get() = type == METHOD_CALL && flags and NO_REPLY_EXPECTED == 0

    companion object {
        const val METHOD_CALL = 1
        const val METHOD_RETURN = 2
        const val ERROR = 3
        const val SIGNAL = 4
        const val NO_REPLY_EXPECTED = 0x1
    }
}

/**
 * A minimal D-Bus client over the JDK's native Unix domain sockets: SASL EXTERNAL auth, the message
 * wire format, and one receive thread. Only what serving an exported object needs — no fd passing,
 * no TCP transports, no abstract-namespace sockets (the JDK cannot connect to those).
 *
 * Value mapping, both directions: y/n/q/i/h → Int, u/x/t → Long, b → Boolean, d → Double,
 * s/g → String, o → [ObjectPath] (String accepted when writing), v → [Variant], arrays → List,
 * dicts → Map, structs → List.
 */
internal class DBusConnection private constructor(private val channel: SocketChannel) : Closeable {
    private val serials = AtomicInteger(0)

    @Volatile
    private var closed = false

    private var receiver: Thread? = null

    /** The bus-assigned unique name (`:1.42`). */
    lateinit var uniqueName: String
        private set

    /** Synchronous call; only valid before [startReceiving] — afterwards the receive thread owns reads. */
    fun callBlocking(
        destination: String,
        path: String,
        iface: String,
        member: String,
        signature: String = "",
        args: List<Any?> = emptyList(),
    ): DBusMessage {
        check(receiver == null) { "callBlocking after startReceiving" }
        val serial = send(
            DBusMessage.METHOD_CALL, 0, path = path, iface = iface, member = member,
            destination = destination, signature = signature, args = args,
        )
        while (true) {
            val message = readMessage()
            if (message.replySerial != serial) continue
            if (message.type == DBusMessage.ERROR) {
                throw IOException("$iface.$member failed: ${message.errorName} ${message.body.firstOrNull() ?: ""}")
            }
            return message
        }
    }

    fun reply(to: DBusMessage, signature: String = "", args: List<Any?> = emptyList()) {
        if (!to.expectsReply) return
        send(
            DBusMessage.METHOD_RETURN, DBusMessage.NO_REPLY_EXPECTED, replySerial = to.serial,
            destination = to.sender, signature = signature, args = args,
        )
    }

    fun replyError(to: DBusMessage, errorName: String, text: String) {
        if (!to.expectsReply) return
        send(
            DBusMessage.ERROR, DBusMessage.NO_REPLY_EXPECTED, errorName = errorName, replySerial = to.serial,
            destination = to.sender, signature = "s", args = listOf(text),
        )
    }

    fun signal(path: String, iface: String, member: String, signature: String, args: List<Any?>) {
        send(
            DBusMessage.SIGNAL, DBusMessage.NO_REPLY_EXPECTED, path = path, iface = iface, member = member,
            signature = signature, args = args,
        )
    }

    /**
     * Hands every incoming method call to [onMethodCall] on a dedicated daemon thread. [onClosed]
     * runs once when the connection drops, with the cause — null when [close] ended it.
     */
    fun startReceiving(onMethodCall: (DBusMessage) -> Unit, onClosed: (Throwable?) -> Unit) {
        check(receiver == null)
        receiver = Thread({
            val cause = try {
                receiveLoop(onMethodCall)
            } catch (error: Throwable) {
                error.takeUnless { closed }
            }
            onClosed(cause)
        }, "dbus-receive").apply {
            isDaemon = true
            start()
        }
    }

    private fun receiveLoop(onMethodCall: (DBusMessage) -> Unit): Nothing {
        while (true) {
            val message = readMessage()
            if (message.type == DBusMessage.METHOD_CALL) onMethodCall(message)
        }
    }

    override fun close() {
        closed = true
        channel.close()
    }

    // --- framing ---

    @Synchronized
    private fun send(
        type: Int,
        flags: Int,
        path: String? = null,
        iface: String? = null,
        member: String? = null,
        errorName: String? = null,
        replySerial: Int? = null,
        destination: String? = null,
        signature: String = "",
        args: List<Any?> = emptyList(),
    ): Int {
        val body = WireWriter()
        body.writeAll(signature, args)
        val serial = serials.incrementAndGet()

        val fields = buildList {
            path?.let { add(listOf(1, Variant("o", ObjectPath(it)))) }
            iface?.let { add(listOf(2, Variant("s", it))) }
            member?.let { add(listOf(3, Variant("s", it))) }
            errorName?.let { add(listOf(4, Variant("s", it))) }
            replySerial?.let { add(listOf(5, Variant("u", it.toLong() and 0xFFFFFFFFL))) }
            destination?.let { add(listOf(6, Variant("s", it))) }
            if (signature.isNotEmpty()) add(listOf(8, Variant("g", signature)))
        }
        val header = WireWriter()
        header.u8('l'.code)
        header.u8(type)
        header.u8(flags)
        header.u8(1)
        header.u32(body.size.toLong())
        header.u32(serial.toLong())
        header.write("a(yv)", fields)
        header.pad(8)

        val frame = ByteBuffer.allocate(header.size + body.size)
        frame.put(header.bytes, 0, header.size).put(body.bytes, 0, body.size).flip()
        while (frame.hasRemaining()) channel.write(frame)
        return serial
    }

    private fun readMessage(): DBusMessage {
        val fixed = readFully(16)
        val order = when (fixed[0].toInt().toChar()) {
            'l' -> ByteOrder.LITTLE_ENDIAN
            'B' -> ByteOrder.BIG_ENDIAN
            else -> throw IOException("bad endianness byte ${fixed[0]}")
        }
        val head = ByteBuffer.wrap(fixed).order(order)
        val bodyLength = head.getInt(4).toLong() and 0xFFFFFFFFL
        val fieldsLength = head.getInt(12).toLong() and 0xFFFFFFFFL
        if (bodyLength > MAX_MESSAGE || fieldsLength > MAX_MESSAGE) throw IOException("message too large")
        val headerLength = (16 + fieldsLength + 7) and 7L.inv()
        val rest = readFully((headerLength - 16 + bodyLength).toInt())
        val whole = fixed + rest

        val reader = WireReader(whole, 0, headerLength.toInt(), order)
        reader.pos = 12
        @Suppress("UNCHECKED_CAST")
        val fields = (reader.read("a(yv)") as List<List<Any?>>).associate { (code, variant) ->
            code as Int to (variant as Variant).value
        }
        val signature = fields[8] as String? ?: ""
        val body = WireReader(whole, headerLength.toInt(), whole.size, order).readAll(signature)
        return DBusMessage(
            type = fixed[1].toInt() and 0xFF,
            flags = fixed[2].toInt() and 0xFF,
            serial = head.getInt(8),
            path = (fields[1] as ObjectPath?)?.path,
            iface = fields[2] as String?,
            member = fields[3] as String?,
            errorName = fields[4] as String?,
            replySerial = (fields[5] as Long?)?.toInt(),
            sender = fields[7] as String?,
            signature = signature,
            body = body,
        )
    }

    private fun readFully(count: Int): ByteArray {
        val buffer = ByteBuffer.allocate(count)
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) throw EOFException("bus closed the connection")
        }
        return buffer.array()
    }

    private fun authenticate() {
        val uid = Files.getAttribute(Path.of("/proc/self"), "unix:uid").toString()
        val hexUid = uid.toByteArray(Charsets.US_ASCII).joinToString("") { "%02x".format(it) }
        writeLine("\u0000AUTH EXTERNAL $hexUid")
        val answer = readLine()
        if (!answer.startsWith("OK ")) throw IOException("D-Bus auth rejected: $answer")
        writeLine("BEGIN")
    }

    private fun writeLine(line: String) {
        val buffer = ByteBuffer.wrap("$line\r\n".toByteArray(Charsets.US_ASCII))
        while (buffer.hasRemaining()) channel.write(buffer)
    }

    /** Byte at a time: nothing past the line may be consumed before BEGIN. */
    private fun readLine(): String {
        val line = StringBuilder()
        val one = ByteBuffer.allocate(1)
        while (true) {
            one.clear()
            if (channel.read(one) < 0) throw EOFException("bus closed during auth")
            val char = one.get(0).toInt().toChar()
            if (char == '\n' && line.endsWith('\r')) return line.dropLast(1).toString()
            line.append(char)
            if (line.length > 512) throw IOException("D-Bus auth line too long")
        }
    }

    companion object {
        private const val MAX_MESSAGE = 1L shl 27

        /** Connects, authenticates, and says Hello to the session bus. */
        fun openSession(): DBusConnection {
            val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
            try {
                channel.connect(UnixDomainSocketAddress.of(sessionBusPath()))
                val connection = DBusConnection(channel)
                connection.authenticate()
                connection.uniqueName = connection.callBlocking(
                    "org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "Hello",
                ).body.single() as String
                return connection
            } catch (error: Throwable) {
                channel.close()
                throw error
            }
        }

        private fun sessionBusPath(): String {
            val address = System.getenv("DBUS_SESSION_BUS_ADDRESS").orEmpty()
            address.split(';').forEach { transport ->
                if (!transport.startsWith("unix:")) return@forEach
                transport.removePrefix("unix:").split(',').forEach { pair ->
                    if (pair.startsWith("path=")) return unescapeAddress(pair.removePrefix("path="))
                }
            }
            val runtimeDir = System.getenv("XDG_RUNTIME_DIR")
            val fallback = runtimeDir?.let { File(it, "bus") }
            if (fallback != null && fallback.exists()) return fallback.path
            throw IOException("no usable session bus in DBUS_SESSION_BUS_ADDRESS='$address'")
        }

        private fun unescapeAddress(value: String): String {
            val out = java.io.ByteArrayOutputStream()
            var index = 0
            while (index < value.length) {
                val char = value[index]
                if (char == '%' && index + 2 < value.length) {
                    out.write(value.substring(index + 1, index + 3).toInt(16))
                    index += 3
                } else {
                    out.write(char.code)
                    index++
                }
            }
            return out.toString(Charsets.UTF_8)
        }
    }
}

/** End index (exclusive) of the single complete type starting at [start]. */
private fun completeTypeEnd(signature: String, start: Int): Int = when (signature[start]) {
    'a' -> completeTypeEnd(signature, start + 1)
    '(', '{' -> {
        val close = if (signature[start] == '(') ')' else '}'
        var index = start + 1
        while (signature[index] != close) index = completeTypeEnd(signature, index)
        index + 1
    }
    else -> start + 1
}

private fun splitTypes(signature: String): List<String> {
    val types = mutableListOf<String>()
    var index = 0
    while (index < signature.length) {
        val end = completeTypeEnd(signature, index)
        types += signature.substring(index, end)
        index = end
    }
    return types
}

private fun alignmentOf(type: Char): Int = when (type) {
    'y', 'g', 'v' -> 1
    'n', 'q' -> 2
    'x', 't', 'd', '(', '{' -> 8
    else -> 4
}

private class WireWriter {
    var bytes = ByteArray(256)
        private set
    var size = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, size + extra))
    }

    fun pad(alignment: Int) {
        while (size % alignment != 0) u8(0)
    }

    fun u8(value: Int) {
        ensure(1)
        bytes[size++] = value.toByte()
    }

    private fun u16(value: Int) {
        pad(2)
        u8(value)
        u8(value shr 8)
    }

    fun u32(value: Long) {
        pad(4)
        for (shift in 0 until 32 step 8) u8((value shr shift).toInt())
    }

    private fun u64(value: Long) {
        pad(8)
        for (shift in 0 until 64 step 8) u8((value shr shift).toInt())
    }

    private fun putBytes(data: ByteArray) {
        ensure(data.size)
        data.copyInto(bytes, size)
        size += data.size
    }

    fun writeAll(signature: String, values: List<Any?>) {
        val types = splitTypes(signature)
        require(types.size == values.size) { "signature '$signature' vs ${values.size} values" }
        types.forEachIndexed { index, type -> write(type, values[index]) }
    }

    fun write(type: String, value: Any?) {
        when (type[0]) {
            'y' -> u8((value as Number).toInt())
            'b' -> u32(if (value as Boolean) 1 else 0)
            'n', 'q' -> u16((value as Number).toInt())
            'i', 'u', 'h' -> u32((value as Number).toLong())
            'x', 't' -> u64((value as Number).toLong())
            'd' -> u64((value as Number).toDouble().toRawBits())
            's', 'o' -> {
                val text = if (value is ObjectPath) value.path else value as String
                val utf8 = text.toByteArray(Charsets.UTF_8)
                u32(utf8.size.toLong())
                putBytes(utf8)
                u8(0)
            }
            'g' -> {
                val utf8 = (value as String).toByteArray(Charsets.US_ASCII)
                u8(utf8.size)
                putBytes(utf8)
                u8(0)
            }
            'v' -> {
                val variant = value as Variant
                write("g", variant.signature)
                write(variant.signature, variant.value)
            }
            '(' -> {
                pad(8)
                writeAll(type.substring(1, type.length - 1), value as List<*>)
            }
            'a' -> {
                val element = type.substring(1)
                u32(0)
                val lengthAt = size - 4
                pad(alignmentOf(element[0]))
                val start = size
                if (element[0] == '{') {
                    val (keyType, valueType) = splitTypes(element.substring(1, element.length - 1))
                    (value as Map<*, *>).forEach { (key, entry) ->
                        pad(8)
                        write(keyType, key)
                        write(valueType, entry)
                    }
                } else {
                    (value as List<*>).forEach { write(element, it) }
                }
                val length = size - start
                for (shift in 0 until 32 step 8) bytes[lengthAt + shift / 8] = (length shr shift).toByte()
            }
            else -> throw IllegalArgumentException("unsupported D-Bus type '$type'")
        }
    }
}

/** Reads [bytes] in `[start, end)`; alignment is relative to [start], which must be 8-aligned in the message. */
private class WireReader(private val bytes: ByteArray, private val start: Int, private val end: Int, order: ByteOrder) {
    private val buffer = ByteBuffer.wrap(bytes).order(order)
    var pos = 0

    private fun align(alignment: Int) {
        pos = (pos + alignment - 1) / alignment * alignment
    }

    private fun take(count: Int): Int {
        val at = start + pos
        if (at + count > end) throw IOException("truncated D-Bus message")
        pos += count
        return at
    }

    private fun u8(): Int = bytes[take(1)].toInt() and 0xFF

    private fun u32(): Long {
        align(4)
        return buffer.getInt(take(4)).toLong() and 0xFFFFFFFFL
    }

    fun readAll(signature: String): List<Any?> = splitTypes(signature).map { read(it) }

    fun read(type: String): Any? = when (type[0]) {
        'y' -> u8()
        'b' -> u32() != 0L
        'n' -> {
            align(2)
            buffer.getShort(take(2)).toInt()
        }
        'q' -> {
            align(2)
            buffer.getShort(take(2)).toInt() and 0xFFFF
        }
        'i', 'h' -> u32().toInt()
        'u' -> u32()
        'x', 't' -> {
            align(8)
            buffer.getLong(take(8))
        }
        'd' -> {
            align(8)
            buffer.getDouble(take(8))
        }
        's', 'o' -> {
            val length = u32().toInt()
            val at = take(length + 1)
            val text = String(bytes, at, length, Charsets.UTF_8)
            if (type[0] == 'o') ObjectPath(text) else text
        }
        'g' -> {
            val length = u8()
            String(bytes, take(length + 1), length, Charsets.US_ASCII)
        }
        'v' -> {
            val signature = read("g") as String
            Variant(signature, read(signature))
        }
        '(' -> {
            align(8)
            readAll(type.substring(1, type.length - 1))
        }
        'a' -> {
            val length = u32().toInt()
            val element = type.substring(1)
            align(alignmentOf(element[0]))
            val stop = pos + length
            if (element[0] == '{') {
                val (keyType, valueType) = splitTypes(element.substring(1, element.length - 1))
                val map = LinkedHashMap<Any?, Any?>()
                while (pos < stop) {
                    align(8)
                    map[read(keyType)] = read(valueType)
                }
                map
            } else {
                buildList { while (pos < stop) add(read(element)) }
            }
        }
        else -> throw IOException("unsupported D-Bus type '$type'")
    }
}
