package org.equeim.bencode

import kotlinx.coroutines.ensureActive
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import java.io.*
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.math.log10
import kotlin.math.min

@Suppress("SpellCheckingInspection", "unused")
object Bencode {
    suspend fun <T> decode(inputStream: InputStream, deserializer: DeserializationStrategy<T>, stringCharset: Charset = DEFAULT_CHARSET): T {
        return Decoder(inputStream, SharedDecoderState(stringCharset), coroutineContext).decodeSerializableValue(deserializer)
    }

    suspend fun <T> encode(value: T, outputStream: OutputStream, serializer: SerializationStrategy<T>, stringCharset: Charset = DEFAULT_CHARSET) {
        Encoder(outputStream, stringCharset, coroutineContext).encodeSerializableValue(serializer, value)
    }

    suspend fun <T> encode(value: T, serializer: SerializationStrategy<T>, stringCharset: Charset = DEFAULT_CHARSET): ByteArray {
        val outputStream = ByteArrayOutputStream()
        Encoder(outputStream, stringCharset, coroutineContext).encodeSerializableValue(serializer, value)
        return outputStream.toByteArray()
    }

    suspend inline fun <reified T> decode(inputStream: InputStream, stringCharset: Charset = DEFAULT_CHARSET): T = decode(inputStream, serializer(), stringCharset)
    suspend inline fun <reified T> encode(value: T, outputStream: OutputStream, stringCharset: Charset = DEFAULT_CHARSET) = encode(value, outputStream, serializer(), stringCharset)
    suspend inline fun <reified T> encode(value: T, stringCharset: Charset = DEFAULT_CHARSET) = encode(value, serializer(), stringCharset)

    val DEFAULT_CHARSET: Charset = StandardCharsets.UTF_8
}

private val LONG_MAX_DIGITS = (Long.SIZE_BITS * log10(2.0)).toInt()

@OptIn(ExperimentalSerializationApi::class)
private open class Decoder(protected val inputStream: PushbackInputStream,
                           protected val sharedState: SharedDecoderState,
                           protected val coroutineContext: CoroutineContext) : AbstractDecoder() {
    constructor(inputStream: InputStream, sharedState: SharedDecoderState, coroutineContext: CoroutineContext) : this(PushbackInputStream(inputStream, 1), sharedState, coroutineContext)
    constructor(other: Decoder) : this(other.inputStream, other.sharedState, other.coroutineContext)

    override val serializersModule: SerializersModule = EmptySerializersModule
    override fun decodeSequentially(): Boolean = true

    private var elementIndex = 0

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        if (elementIndex == descriptor.elementsCount) return CompositeDecoder.DECODE_DONE
        return elementIndex++
    }

    override fun decodeLong(): Long {
        if (readChar() != 'i') throw SerializationException("Integer does not begin with 'i'")
        return readIntegerUntil('e')
    }

    override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>, previousValue: T?): T {
        @Suppress("UNCHECKED_CAST")
        return if (deserializer === sharedState.byteArraySerializer) {
            decodeByteArray() as T
        } else {
            super.decodeSerializableValue(deserializer, previousValue)
        }
    }

    private fun decodeByteArray(): ByteArray {
        val size = readIntegerUntil(':').toInt()
        if (size < 0) throw SerializationException("Byte string length must not be negative")
        if (size == 0) return byteArrayOf()

        val value = ByteArray(size)
        var off = 0
        while (off < size) {
            val n = inputStream.read(value, off, size - off)
            if (n == -1) throw EOFException()
            off += n
        }
        return value
    }

    override fun decodeString(): String {
        val size = readIntegerUntil(':').toInt()
        if (size < 0) throw SerializationException("Byte string length must not be negative")

        if (size == 0) return ""

        val isTempByteBuffer: Boolean
        val buffer = if (size <= sharedState.tempByteBuffer.capacity()) {
            isTempByteBuffer = true
            sharedState.tempByteBuffer.apply {
                (this as Buffer).limit(size)
            }
        } else {
            isTempByteBuffer = false
            ByteBuffer.allocate(size)
        }

        var off = 0
        while (off < size) {
            val n = inputStream.read(buffer.array(), off, size - off)
            if (n == -1) throw EOFException()
            off += n
        }

        return sharedState.stringsCache.get(buffer, isTempByteBuffer)
    }

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
        return when (descriptor.kind) {
            StructureKind.CLASS -> DictionaryDecoderForClass(this)
            StructureKind.MAP -> DictionaryDecoderForMap(this)
            StructureKind.LIST -> ListDecoder(this)
            else -> throw SerializationException("Unsupported descriptor kind")
        }
    }

    protected fun readIntegerUntil(terminator: Char): Long {
        var char = readChar()
        val negative = when (char) {
            terminator -> return 0
            '-' -> {
                char = readChar()
                true
            }
            else -> false
        }

        var result = 0L
        var n = 0
        while (char != terminator) {
            if (n == LONG_MAX_DIGITS) {
                throw SerializationException("Didin't find terminator character when reading integer")
            }

            result *= 10
            result -= char.toAsciiDigit()
            ++n

            char = readChar()
        }

        return if (negative) result else -result
    }

    private fun Char.toAsciiDigit(): Int {
        if (this in '0'..'9') return this - '0'
        throw SerializationException("Character '$this' is not an ASCII digit")
    }

    protected fun readChar(): Char {
        val byte = inputStream.read()
        if (byte == -1) throw EOFException()
        return byte.toChar()
    }

    protected fun unreadChar(char: Char) = inputStream.unread(char.code)
}

private abstract class CollectionDecoder(other: Decoder) : Decoder(other) {
    private var elementIndex = 0

    override fun decodeSequentially(): Boolean = false

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        coroutineContext.ensureActive()

        val char = readChar()
        if (char == 'e') {
            return CompositeDecoder.DECODE_DONE
        }
        unreadChar(char)
        return elementIndex++
    }
}

private class ListDecoder(other: Decoder) : CollectionDecoder(other) {
    init {
        if (readChar() != 'l') {
            throw SerializationException("List does not start with 'l'")
        }
    }
}

private class DictionaryDecoderForMap(other: Decoder) : CollectionDecoder(other) {
    init {
        if (readChar() != 'd') {
            throw SerializationException("Dictionary does not start with 'd'")
        }
    }
}

private class DictionaryDecoderForClass(other: Decoder) : Decoder(other) {
    private var validKeysCount = 0

    init {
        if (readChar() != 'd') {
            throw SerializationException("Dictionary does not start with 'd'")
        }
    }

    override fun decodeSequentially(): Boolean = false

    @OptIn(ExperimentalSerializationApi::class)
    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        while (true) {
            coroutineContext.ensureActive()

            if (validKeysCount == descriptor.elementsCount) {
                // Found all elements in the class, skip until end
                skipDictionary()
                return CompositeDecoder.DECODE_DONE
            }

            val char = readChar()
            if (char == 'e') {
                return CompositeDecoder.DECODE_DONE
            }
            unreadChar(char)

            val key = decodeString()
            val index = descriptor.getElementIndex(key)
            if (index == CompositeDecoder.UNKNOWN_NAME) {
                // Key not found, skip value
                skipValue()
            } else {
                validKeysCount++
                return index
            }
        }
    }

    private fun skipValue() {
        when (val char = readChar()) {
            'i' -> skipInteger()
            'l' -> skipList()
            'd' -> skipDictionary()
            else -> {
                unreadChar(char)
                skipByteArray()
            }
        }
    }

    // Start byte must already be read
    private fun skipInteger() {
        while (readChar() != 'e') {
            // Just loop
        }
    }

    private fun skipByteArray() {
        val size = readIntegerUntil(':').toInt()
        if (size < 0) throw SerializationException("Byte string length must not be negative")
        if (size == 0) return
        var remaining = size
        while (remaining > 0) {
            val n = inputStream.read(
                sharedState.tempByteBuffer.array(),
                0,
                min(sharedState.tempByteBuffer.capacity(), remaining)
            )
            if (n == -1) throw EOFException()
            remaining -= n
        }
    }

    // Start byte must already be read
    private fun skipList() {
        while (true) {
            coroutineContext.ensureActive()

            val char = readChar()
            if (char == 'e') break
            unreadChar(char)
            skipValue()
        }
    }

    // Start byte must already be read
    private fun skipDictionary() {
        while (true) {
            coroutineContext.ensureActive()

            val char = readChar()
            if (char == 'e') break
            unreadChar(char)
            skipByteArray()
            skipValue()
        }
    }
}

