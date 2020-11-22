package org.equeim.bencode

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
import java.nio.charset.Charset
import kotlin.math.min

@Suppress("SpellCheckingInspection")
object Bencode {
    fun <T> decode(inputStream: InputStream, deserializer: DeserializationStrategy<T>, stringCharset: Charset = Charsets.UTF_8): T {
        return Decoder(inputStream, stringCharset).decodeSerializableValue(deserializer)
    }

    fun <T> encode(value: T, outputStream: OutputStream, serializer: SerializationStrategy<T>, stringCharset: Charset = Charsets.UTF_8) {
        Encoder(outputStream, stringCharset).encodeSerializableValue(serializer, value)
    }

    fun <T> encode(value: T, serializer: SerializationStrategy<T>, stringCharset: Charset = Charsets.UTF_8): ByteArray {
        val outputStream = ByteArrayOutputStream()
        Encoder(outputStream, stringCharset).encodeSerializableValue(serializer, value)
        return outputStream.toByteArray()
    }

    inline fun <reified T> decode(inputStream: InputStream, stringCharset: Charset = Charsets.UTF_8): T = decode(inputStream, serializer(), stringCharset)
    inline fun <reified T> encode(value: T, outputStream: OutputStream, stringCharset: Charset = Charsets.UTF_8) = encode(value, outputStream, serializer(), stringCharset)
    inline fun <reified T> encode(value: T, stringCharset: Charset = Charsets.UTF_8) = encode(value, serializer(), stringCharset)
}

private val byteArraySerializer = serializer<ByteArray>()

private open class Decoder(protected val inputStream: PushbackInputStream,
                           private val stringCharset: Charset) : AbstractDecoder() {
    constructor(inputStream: InputStream, stringCharset: Charset) : this(PushbackInputStream(inputStream, 1), stringCharset)
    constructor(other: Decoder) : this(other.inputStream, other.stringCharset)

    override val serializersModule: SerializersModule = EmptySerializersModule
    override fun decodeSequentially(): Boolean = true

    private var elementIndex = 0
    private val stringBuilder = StringBuilder()

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
        return if (deserializer === byteArraySerializer) {
            decodeByteArray() as T
        } else {
            super.decodeSerializableValue(deserializer, previousValue)
        }
    }

    fun decodeByteArray(): ByteArray {
        val size = readIntegerUntil(':')
        if (size == 0L) return byteArrayOf()
        if (size < 0) throw SerializationException("Byte string length must not be negative")

        val value = ByteArray(size.toInt())
        var off = 0
        while (off < value.size) {
            val n = inputStream.read(value, off, value.size - off)
            if (n == -1) throw EOFException()
            off += n
        }
        return value
    }

    override fun decodeString(): String {
        return decodeByteArray().toString(stringCharset)
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

    protected fun unreadChar(char: Char) = inputStream.unread(char.toInt())
}

private abstract class CollectionDecoder(other: Decoder) : Decoder(other) {
    private var elementIndex = 0

    override fun decodeSequentially(): Boolean = false

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
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
    private companion object {
        const val SKIP_BUFFER_SIZE = 8192
    }

    private var validKeysCount = 0
    private val skipBuffer by lazy(LazyThreadSafetyMode.NONE) { ByteArray(SKIP_BUFFER_SIZE) }

    init {
        if (readChar() != 'd') {
            throw SerializationException("Dictionary does not start with 'd'")
        }
    }

    override fun decodeSequentially(): Boolean = false

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        while (true) {
            if (validKeysCount == descriptor.elementsCount) {
                // Found all elements in the class, skip until end
                skipUntilEnd()
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

    private fun skipUntilEnd() {
        while (true) {
            val char = readChar()
            if (char == 'e') {
                break
            }
            unreadChar(char)
            decodeByteArray()
            skipValue()
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
        if (size == 0) return
        if (size < 0) throw SerializationException("Byte string length must not be negative")
        var remaining = size
        while (remaining > 0) {
            val n = inputStream.read(skipBuffer, 0, min(SKIP_BUFFER_SIZE, remaining))
            if (n == -1) throw EOFException()
            remaining -= n
        }
    }

    // Start byte must already be read
    private fun skipList() {
        while (true) {
            val char = readChar()
            if (char == 'e') break
            unreadChar(char)
            skipValue()
        }
    }

    // Start byte must already be read
    private fun skipDictionary() {
        while (true) {
            val char = readChar()
            if (char == 'e') break
            unreadChar(char)
            decodeByteArray()
            skipValue()
        }
    }
}

private class Encoder(private val outputStream: OutputStream,
                      private val stringCharset: Charset) : AbstractEncoder() {
    override val serializersModule: SerializersModule = EmptySerializersModule

    override fun encodeLong(value: Long) {
        outputStream.write("i${value}e".toByteArray(Charsets.US_ASCII))
    }

    private fun encodeByteArray(value: ByteArray) {
        outputStream.write("${value.size}:".toByteArray(Charsets.US_ASCII))
        outputStream.write(value)
    }

    override fun encodeString(value: String) {
        encodeByteArray(value.toByteArray(stringCharset))
    }

    override fun <T> encodeSerializableValue(serializer: SerializationStrategy<T>, value: T) {
        if (serializer === byteArraySerializer) {
            encodeByteArray(value as ByteArray)
        } else {
            super.encodeSerializableValue(serializer, value)
        }
    }

    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
        when (descriptor.kind) {
            StructureKind.CLASS -> outputStream.write('d'.toInt())
            StructureKind.MAP -> {
                if (!validateMapKeyType(descriptor)) {
                    throw SerializationException("Only maps with String or ByteArray keys are supported")
                }
                outputStream.write('d'.toInt())
            }
            StructureKind.LIST -> outputStream.write('l'.toInt())
            else -> throw SerializationException("Unsupported StructureKind")
        }
        return this
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        outputStream.write('e'.toInt())
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
