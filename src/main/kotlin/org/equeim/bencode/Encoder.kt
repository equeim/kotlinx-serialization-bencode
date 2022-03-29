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
