package org.equeim.bencode

import kotlinx.coroutines.ensureActive
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.modules.EmptySerializersModule
import java.io.EOFException
import java.io.InputStream
import java.io.PushbackInputStream
import java.nio.Buffer
import java.nio.ByteBuffer
import kotlin.coroutines.CoroutineContext
import kotlin.math.min

@OptIn(ExperimentalSerializationApi::class)
internal open class Decoder(protected val inputStream: PushbackInputStream,
                           protected val sharedState: SharedDecoderState,
                           protected val coroutineContext: CoroutineContext
) : AbstractDecoder() {
    constructor(inputStream: InputStream, sharedState: SharedDecoderState, coroutineContext: CoroutineContext) : this(
        PushbackInputStream(inputStream, 1), sharedState, coroutineContext)
    constructor(other: Decoder) : this(other.inputStream, other.sharedState, other.coroutineContext)

    override val serializersModule = EmptySerializersModule()
    override fun decodeSequentially(): Boolean = true

    private var elementIndex = 0

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        if (elementIndex == descriptor.elementsCount) return CompositeDecoder.DECODE_DONE
        return elementIndex++
    }

    override fun decodeLong(): Long {
        if (readChar() != INTEGER_PREFIX) throw SerializationException("Integer does not begin with '${INTEGER_PREFIX}'")
        return readIntegerUntil(INTEGER_TERMINATOR)
    }

    override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>, previousValue: T?): T {
        @Suppress("UNCHECKED_CAST")
        return if (deserializer === sharedState.byteArraySerializer) {
            decodeByteArray() as T
        } else {
            super.decodeSerializableValue(deserializer, previousValue)
        }
    }

    override fun <T> decodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        deserializer: DeserializationStrategy<T>,
        previousValue: T?
    ): T {
        val startOffset = sharedState.readOffset
        val value = super.decodeSerializableElement(descriptor, index, deserializer, previousValue)
        if (descriptor.getElementAnnotations(index).any { it is ReportByteRange }) {
            if (sharedState.byteRange != null) {
                throw SerializationException("There can be only one property with ReportByteRange annotation")
            }
            sharedState.byteRange = ByteRange(startOffset, sharedState.readOffset - startOffset)
        }
        return value
    }

    private fun decodeByteArray(): ByteArray {
        val size = readIntegerUntil(BYTE_ARRAY_LENGTH_VALUE_SEPARATOR).toInt()
        if (size < 0) throw SerializationException("Byte array length $size must not be negative")
        if (size == 0) return byteArrayOf()

        val value = ByteArray(size)
        var off = 0
        while (off < size) {
            val n = inputStream.read(value, off, size - off)
            if (n == -1) throw EOFException()
            off += n
        }
        sharedState.readOffset += size
        return value
    }

    override fun decodeString(): String {
        val size = readIntegerUntil(BYTE_ARRAY_LENGTH_VALUE_SEPARATOR).toInt()
        if (size < 0) throw SerializationException("Byte array length $size must not be negative")

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
        val array = buffer.array()

        var off = 0
        while (off < size) {
            val n = inputStream.read(array, off, size - off)
            if (n == -1) throw EOFException()
            off += n
        }

        sharedState.readOffset += size

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
            INTEGER_NEGATIVE_SIGN -> {
                char = readChar()
                true
            }
            else -> false
        }

        var result = 0L
        var n = 0
        while (char != terminator) {
            if (n == INTEGER_MAX_DIGITS) {
                throw SerializationException("Reached maximum length $INTEGER_MAX_DIGITS when reading integer")
            }

            result *= 10
            result -= char.toAsciiDigit()
            ++n

            char = readChar()
        }

        return if (negative) result else -result
    }

    protected fun readChar(): Char {
        val byte = inputStream.read()
        if (byte == -1) throw EOFException()
        sharedState.readOffset += 1
        return byte.toChar()
    }

    protected fun unreadChar(char: Char) {
        inputStream.unread(char.code)
        sharedState.readOffset -= 1
    }
}

private abstract class CollectionDecoder(other: Decoder, private val terminator: Char) : Decoder(other) {
    private var elementIndex = 0

    override fun decodeSequentially(): Boolean = false

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        coroutineContext.ensureActive()

        val char = readChar()
        if (char == terminator) {
            return CompositeDecoder.DECODE_DONE
        }
        unreadChar(char)
        return elementIndex++
    }
}

private class ListDecoder(other: Decoder) : CollectionDecoder(other, LIST_TERMINATOR) {
    init {
        if (readChar() != LIST_PREFIX) {
            throw SerializationException("List does not start with '$LIST_PREFIX'")
        }
    }
}

private class DictionaryDecoderForMap(other: Decoder) : CollectionDecoder(other, DICTIONARY_TERMINATOR) {
    init {
        if (readChar() != DICTIONARY_PREFIX) {
            throw SerializationException("Dictionary does not start with '$DICTIONARY_PREFIX'")
        }
    }
}

private class DictionaryDecoderForClass(other: Decoder) : Decoder(other) {
    private var validKeysCount = 0

    init {
        if (readChar() != DICTIONARY_PREFIX) {
            throw SerializationException("Dictionary does not start with '$DICTIONARY_PREFIX'")
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
            if (char == DICTIONARY_TERMINATOR) {
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
            INTEGER_PREFIX -> skipInteger()
            LIST_PREFIX -> skipList()
            DICTIONARY_PREFIX -> skipDictionary()
            else -> {
                unreadChar(char)
                skipByteArray()
            }
        }
    }

    // Start byte must already be read
    private fun skipInteger() {
        while (readChar() != INTEGER_TERMINATOR) {
            // Just loop
        }
    }

    private fun skipByteArray() {
        val size = readIntegerUntil(BYTE_ARRAY_LENGTH_VALUE_SEPARATOR).toInt()
        if (size < 0) throw SerializationException("Byte string length must not be negative")
        if (size == 0) return
        var remaining = size
        val array = sharedState.tempByteBuffer.array()
        while (remaining > 0) {
            val len = min(sharedState.tempByteBuffer.capacity(), remaining)
            val n = inputStream.read(
                array,
                0,
                len
            )
            if (n == -1) throw EOFException()
            remaining -= n
        }
        sharedState.readOffset += size
    }

    // Start byte must already be read
    private fun skipList() {
        while (true) {
            coroutineContext.ensureActive()

            val char = readChar()
            if (char == LIST_TERMINATOR) break
            unreadChar(char)
            skipValue()
        }
    }

    // Start byte must already be read
    private fun skipDictionary() {
        while (true) {
            coroutineContext.ensureActive()

            val char = readChar()
            if (char == DICTIONARY_TERMINATOR) break
            unreadChar(char)
            skipByteArray()
            skipValue()
        }
    }
}
