package org.equeim.bencode

import kotlinx.coroutines.ensureActive
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.math.min

@Suppress("SpellCheckingInspection")
object Bencode {
    suspend fun <T> decode(inputStream: InputStream, deserializer: DeserializationStrategy<T>, stringCharset: Charset = Charsets.UTF_8): T {
        return Decoder(inputStream, SharedState(stringCharset), coroutineContext).decodeSerializableValue(deserializer)
    }

    suspend fun <T> encode(value: T, outputStream: OutputStream, serializer: SerializationStrategy<T>, stringCharset: Charset = Charsets.UTF_8) {
        Encoder(outputStream, stringCharset, coroutineContext).encodeSerializableValue(serializer, value)
    }

    suspend fun <T> encode(value: T, serializer: SerializationStrategy<T>, stringCharset: Charset = Charsets.UTF_8): ByteArray {
        val outputStream = ByteArrayOutputStream()
        Encoder(outputStream, stringCharset, coroutineContext).encodeSerializableValue(serializer, value)
        return outputStream.toByteArray()
    }

    suspend inline fun <reified T> decode(inputStream: InputStream, stringCharset: Charset = Charsets.UTF_8): T = decode(inputStream, serializer(), stringCharset)
    suspend inline fun <reified T> encode(value: T, outputStream: OutputStream, stringCharset: Charset = Charsets.UTF_8) = encode(value, outputStream, serializer(), stringCharset)
    suspend inline fun <reified T> encode(value: T, stringCharset: Charset = Charsets.UTF_8) = encode(value, serializer(), stringCharset)
}

private class SharedState(stringCharset: Charset) {
    val tempByteBuffer by lazy(LazyThreadSafetyMode.PUBLICATION) {
        ByteBuffer.allocate(TEMP_BYTE_BUFFER_SIZE)
    }

    val stringsCache by lazy(LazyThreadSafetyMode.PUBLICATION) { StringsCache(stringCharset) }

    inner class StringsCache(private val stringCharset: Charset) : LruCache<ByteBuffer, String>(STRINGS_CACHE_SIZE) {
        override fun sizeOf(key: ByteBuffer, value: String): Int {
            // Approximation
            return key.capacity() + value.length * 2;
        }

        // We can't override create() instead because we want to call get() with temp byte buffer,
        // and if cache wasn't hit then we want to call put with unique buffer
        fun get(byteBuffer: ByteBuffer, isTempByteBuffer: Boolean): String {
            return get(byteBuffer) ?: run {
                val key = if (isTempByteBuffer) {
                    ByteBuffer.wrap(byteBuffer.array().copyOf(byteBuffer.limit()))
                } else {
                    byteBuffer
                }
                val value = String(byteBuffer.array(), 0, byteBuffer.limit(), stringCharset)
                put(key, value)
                value
            }
        }
    }
}

private const val STRINGS_CACHE_SIZE = 1 * 1024 * 1024
private const val TEMP_BYTE_BUFFER_SIZE = 8192


private val byteArraySerializer by lazy(LazyThreadSafetyMode.PUBLICATION) { serializer<ByteArray>() }

private open class Decoder(protected val inputStream: PushbackInputStream,
                           protected val sharedState: SharedState,
                           protected val coroutineContext: CoroutineContext) : AbstractDecoder() {
    constructor(inputStream: InputStream, sharedState: SharedState, coroutineContext: CoroutineContext) : this(PushbackInputStream(inputStream, 1), sharedState, coroutineContext)
    constructor(other: Decoder) : this(other.inputStream, other.sharedState, other.coroutineContext)

    override val serializersModule: SerializersModule = EmptySerializersModule
    override fun decodeSequentially(): Boolean = true

    private var elementIndex = 0
    private val stringBuilder = StringBuilder()

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        println("decodeElementIndex() called with: descriptor = $descriptor")
        if (elementIndex == descriptor.elementsCount) return CompositeDecoder.DECODE_DONE
        return elementIndex++
    }

    override fun decodeLong(): Long {
        if (readChar() != 'i') throw SerializationException("Integer does not begin with 'i'")
        return readIntegerUntil('e')
    }

    override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>, previousValue: T?): T {
        @Suppress("UNCHECKED_CAST")
        return if (deserializer === byteArraySerializer) {
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
        val buffer = if (size <= TEMP_BYTE_BUFFER_SIZE) {
            isTempByteBuffer = true
            sharedState.tempByteBuffer.limit(size)
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
        while (true) {
            val char = readChar()
            if (char == terminator) {
                return try {
                    stringBuilder.toString().toLong().also { stringBuilder.setLength(0) }
                } catch (e: NumberFormatException) {
                    throw SerializationException("Failed to parse Long from String", e)
                }
            }
            stringBuilder.append(char)
        }
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
                min(TEMP_BYTE_BUFFER_SIZE, remaining)
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

private class Encoder(private val outputStream: OutputStream,
                      private val stringCharset: Charset,
                      private val coroutineContext: CoroutineContext) : AbstractEncoder() {
    override val serializersModule: SerializersModule = EmptySerializersModule

    override fun encodeLong(value: Long) {
        coroutineContext.ensureActive()
        outputStream.write("i${value}e".toByteArray(Charsets.US_ASCII))
    }

    private fun encodeByteArray(value: ByteArray) {
        coroutineContext.ensureActive()
        outputStream.write("${value.size}:".toByteArray(Charsets.US_ASCII))
        outputStream.write(value)
    }

    override fun encodeString(value: String) {
        coroutineContext.ensureActive()
        encodeByteArray(value.toByteArray(stringCharset))
    }

    override fun <T> encodeSerializableValue(serializer: SerializationStrategy<T>, value: T) {
        coroutineContext.ensureActive()
        if (serializer === byteArraySerializer) {
            encodeByteArray(value as ByteArray)
        } else {
            super.encodeSerializableValue(serializer, value)
        }
    }

    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
        coroutineContext.ensureActive()
        when (descriptor.kind) {
            StructureKind.CLASS -> outputStream.write('d'.code)
            StructureKind.MAP -> {
                if (!validateMapKeyType(descriptor)) {
                    throw SerializationException("Only maps with String or ByteArray keys are supported")
                }
                outputStream.write('d'.code)
            }
            StructureKind.LIST -> outputStream.write('l'.code)
            else -> throw SerializationException("Unsupported StructureKind")
        }
        return this
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        outputStream.write('e'.code)
    }

    private fun validateMapKeyType(descriptor: SerialDescriptor): Boolean {
        val keyDescriptor = descriptor.getElementDescriptor(0)
        return when (keyDescriptor.kind) {
            PrimitiveKind.STRING -> true
            StructureKind.LIST -> keyDescriptor.getElementDescriptor(0).kind == PrimitiveKind.BYTE
            else -> false
        }
    }
}
