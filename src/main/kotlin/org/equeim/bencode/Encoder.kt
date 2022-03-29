package org.equeim.bencode

import kotlinx.coroutines.ensureActive
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer
import java.io.OutputStream
import java.nio.charset.Charset
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalSerializationApi::class)
internal class Encoder(private val outputStream: OutputStream,
                      private val stringCharset: Charset,
                      private val coroutineContext: CoroutineContext
) : AbstractEncoder() {
    private val byteArraySerializer by lazy(LazyThreadSafetyMode.PUBLICATION) { serializer<ByteArray>() }
    override val serializersModule: SerializersModule = EmptySerializersModule

    override fun encodeLong(value: Long) {
        coroutineContext.ensureActive()
        outputStream.write("${INTEGER_PREFIX}${value}${INTEGER_TERMINATOR}".toByteArray(Charsets.US_ASCII))
    }

    private fun encodeByteArray(value: ByteArray) {
        coroutineContext.ensureActive()
        outputStream.write("${value.size}${BYTE_ARRAY_LENGTH_VALUE_SEPARATOR}".toByteArray(Charsets.US_ASCII))
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
        val prefix = when (descriptor.kind) {
            StructureKind.CLASS -> DICTIONARY_PREFIX
            StructureKind.MAP -> {
                if (!validateMapKeyType(descriptor)) {
                    throw SerializationException("Only maps with String or ByteArray keys are supported")
                }
                DICTIONARY_PREFIX
            }
            StructureKind.LIST -> LIST_PREFIX
            else -> throw SerializationException("Unsupported StructureKind")
        }
        outputStream.write(prefix.code)
        return this
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        val terminator = when (descriptor.kind) {
            StructureKind.CLASS, StructureKind.MAP -> DICTIONARY_TERMINATOR
            StructureKind.LIST -> LIST_TERMINATOR
            else -> throw SerializationException("Unsupported StructureKind")
        }
        outputStream.write(terminator.code)
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
