package kmphttp.json

import okio.Buffer
import okio.ByteString.Companion.encodeUtf8

/**
 * JSON encoder — the write-side mirror of [JsonReader]. Writes compact JSON into [buffer] and stops
 * there: draining is the caller's business, through the [Sync.flush] / [Async.flush] it supplied.
 *
 * Nothing here suspends, so an encoder is usable from any context and a document that fits in memory
 * needs no coroutine at all. The two variants exist only because DRAINING might: [Sync] for a caller
 * whose sink is in-memory or blocking, [Async] for one writing to an [kmpcommon.io.AsyncSink].
 *
 * Shape is checked as it is written: [name] only inside an object, each object [name] followed by
 * exactly one value, one root value per document, matched begin/end. Not thread-safe; drive it from
 * one place.
 */
sealed class JsonWriter {

    /** Everything written so far. Drain it whenever — the writer never touches it behind your back. */
    val buffer = Buffer()

    /** Drains with a plain call: an in-memory sink, or a blocking one the caller may block on. */
    class Sync(private val onFlush: (JsonWriter) -> Unit = {}) : JsonWriter() {
        fun flush() = onFlush(this)
    }

    /** Drains into something that suspends, e.g. an [kmpcommon.io.AsyncSink]. */
    class Async(private val onFlush: suspend (JsonWriter) -> Unit = {}) : JsonWriter() {
        suspend fun flush() = onFlush(this)
    }

    private val stack = ArrayDeque<Scope>()
    private var rootWritten = false

    private class Scope(val isObject: Boolean) {
        var count = 0 // pairs (object) / elements (array) written so far
        var pendingName = false // object: a name is written, its value is due next
    }

    fun beginObject(): JsonWriter = open(BRACE_OPEN, isObject = true)

    fun endObject(): JsonWriter {
        val scope = stack.removeLastOrNull() ?: error("endObject() without beginObject()")
        check(scope.isObject) { "endObject() closing an array" }
        check(!scope.pendingName) { "endObject() after name() with no value" }
        buffer.writeByte(BRACE_CLOSE)
        return this
    }

    fun beginArray(): JsonWriter = open(BRACKET_OPEN, isObject = false)

    fun endArray(): JsonWriter {
        val scope = stack.removeLastOrNull() ?: error("endArray() without beginArray()")
        check(!scope.isObject) { "endArray() closing an object" }
        buffer.writeByte(BRACKET_CLOSE)
        return this
    }

    /** Write an object key and its ':'; the next call must write the value. */
    fun name(name: String): JsonWriter {
        val scope = stack.lastOrNull()
        check(scope != null && scope.isObject) { "name() only inside an object" }
        check(!scope.pendingName) { "name() twice without a value" }
        if (scope.count > 0) buffer.writeByte(COMMA)
        scope.count++
        scope.pendingName = true
        writeString(name)
        buffer.writeByte(COLON)
        return this
    }

    fun value(value: String?): JsonWriter {
        beforeValue()
        if (value == null) buffer.write(NULL) else writeString(value)
        return this
    }

    fun value(value: Long): JsonWriter {
        beforeValue()
        // writeDecimalLong formats digits straight into the buffer's segments — no String, and no
        // second pass to UTF-8 encode one.
        buffer.writeDecimalLong(value)
        return this
    }

    fun value(value: Int): JsonWriter = value(value.toLong())

    /** JSON has no NaN or Infinity, so a non-finite [value] throws. */
    fun value(value: Double): JsonWriter {
        require(value.isFinite()) { "JSON numbers must be finite: $value" }
        beforeValue()
        buffer.writeUtf8(value.toString())
        return this
    }

    fun value(value: Boolean): JsonWriter {
        beforeValue()
        buffer.write(if (value) TRUE else FALSE)
        return this
    }

    fun nullValue(): JsonWriter {
        beforeValue()
        buffer.write(NULL)
        return this
    }

    private fun open(brace: Int, isObject: Boolean): JsonWriter {
        beforeValue()
        buffer.writeByte(brace)
        stack.addLast(Scope(isObject))
        return this
    }

    // Emit the separator a value needs in the current context, and advance the book-keeping. Top
    // level accepts exactly one value.
    private fun beforeValue() {
        val scope = stack.lastOrNull()
        if (scope == null) {
            check(!rootWritten) { "a JSON document has a single root value" }
            rootWritten = true
            return
        }
        if (scope.isObject) {
            check(scope.pendingName) { "a value in an object must follow name()" }
            scope.pendingName = false
        } else {
            if (scope.count > 0) buffer.writeByte(COMMA)
            scope.count++
        }
    }

    // Escape only what JSON requires: the quote, the backslash, and control chars < 0x20 (short forms
    // for \b \t \n \f \r, \u00XX otherwise). Everything else — non-ASCII and astral included — is raw
    // UTF-8 (okio's writeUtf8 encodes surrogate pairs, and a run never splits a pair since every
    // escaped char is BMP).
    //
    // Unescaped characters go out in runs and the escapes as bare bytes, so the common case — a
    // string with nothing to escape — is one writeUtf8 over the whole thing and allocates nothing.
    private fun writeString(s: String) {
        buffer.writeByte(QUOTE)
        var runStart = 0
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            if (c == '"' || c == '\\' || c.code < 0x20) {
                if (i > runStart) buffer.writeUtf8(s, runStart, i)
                buffer.writeByte(BACKSLASH)
                when (c) {
                    '"' -> buffer.writeByte(QUOTE)
                    '\\' -> buffer.writeByte(BACKSLASH)
                    '\n' -> buffer.writeByte(LOWER_N)
                    '\r' -> buffer.writeByte(LOWER_R)
                    '\t' -> buffer.writeByte(LOWER_T)
                    '\b' -> buffer.writeByte(LOWER_B)
                    '' -> buffer.writeByte(LOWER_F)
                    else -> {
                        // Always < 0x20 here, so both high nibbles are 0.
                        buffer.writeByte(LOWER_U)
                        buffer.writeByte(ZERO)
                        buffer.writeByte(ZERO)
                        buffer.writeByte(HEX[(c.code shr 4) and 0xF].code)
                        buffer.writeByte(HEX[c.code and 0xF].code)
                    }
                }
                runStart = i + 1
            }
            i++
        }
        if (n > runStart) buffer.writeUtf8(s, runStart, n)
        buffer.writeByte(QUOTE)
    }

    private companion object {
        private const val HEX = "0123456789abcdef"

        private const val QUOTE = '"'.code
        private const val BACKSLASH = '\\'.code
        private const val COMMA = ','.code
        private const val COLON = ':'.code
        private const val BRACE_OPEN = '{'.code
        private const val BRACE_CLOSE = '}'.code
        private const val BRACKET_OPEN = '['.code
        private const val BRACKET_CLOSE = ']'.code
        private const val ZERO = '0'.code
        private const val LOWER_N = 'n'.code
        private const val LOWER_R = 'r'.code
        private const val LOWER_T = 't'.code
        private const val LOWER_B = 'b'.code
        private const val LOWER_F = 'f'.code
        private const val LOWER_U = 'u'.code

        // Written as bytes: a literal costs a segment copy rather than a UTF-8 encoding pass.
        private val NULL = "null".encodeUtf8()
        private val TRUE = "true".encodeUtf8()
        private val FALSE = "false".encodeUtf8()
    }
}
