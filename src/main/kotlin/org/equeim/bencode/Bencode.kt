package org.equeim.bencode

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialInfo
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.serializer
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import kotlin.coroutines.coroutineContext

@Suppress("SpellCheckingInspection", "unused")
object Bencode {
    suspend fun <T> decode(inputStream: InputStream, deserializer: DeserializationStrategy<T>, stringCharset: Charset = DEFAULT_CHARSET): DecodeResult<T> {
        val sharedState = SharedDecoderState(stringCharset)
        val value = Decoder(inputStream, sharedState, coroutineContext).decodeSerializableValue(deserializer)
        return DecodeResult(value, sharedState.byteRange)
    }

    suspend fun <T> encode(value: T, outputStream: OutputStream, serializer: SerializationStrategy<T>, stringCharset: Charset = DEFAULT_CHARSET) {
        Encoder(outputStream, stringCharset, coroutineContext).encodeSerializableValue(serializer, value)
    }

    suspend fun <T> encode(value: T, serializer: SerializationStrategy<T>, stringCharset: Charset = DEFAULT_CHARSET): ByteArray {
        val outputStream = ByteArrayOutputStream()
        Encoder(outputStream, stringCharset, coroutineContext).encodeSerializableValue(serializer, value)
        return outputStream.toByteArray()
    }

    suspend inline fun <reified T> decode(inputStream: InputStream, stringCharset: Charset = DEFAULT_CHARSET): DecodeResult<T> = decode(inputStream, serializer(), stringCharset)
    suspend inline fun <reified T> encode(value: T, outputStream: OutputStream, stringCharset: Charset = DEFAULT_CHARSET) = encode(value, outputStream, serializer(), stringCharset)
    suspend inline fun <reified T> encode(value: T, stringCharset: Charset = DEFAULT_CHARSET) = encode(value, serializer(), stringCharset)

    val DEFAULT_CHARSET: Charset = StandardCharsets.UTF_8
}

@OptIn(ExperimentalSerializationApi::class)
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class ReportByteRange

data class ByteRange(
    val offset: Int,
    val length: Int
)

data class DecodeResult<T>(
    val value: T,
    val byteRange: ByteRange?
)
