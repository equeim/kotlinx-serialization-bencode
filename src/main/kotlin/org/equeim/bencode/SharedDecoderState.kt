package org.equeim.bencode

import kotlinx.serialization.serializer
import java.nio.ByteBuffer
import java.nio.charset.Charset

private const val TEMP_BYTE_BUFFER_SIZE = 8192
private const val STRINGS_CACHE_SIZE = 1 * 1024 * 1024

internal class SharedDecoderState(stringCharset: Charset) {
    var readOffset = 0
    var byteRange: ByteRange? = null

    val byteArraySerializer by lazy(LazyThreadSafetyMode.PUBLICATION) { serializer<ByteArray>() }

    val tempByteBuffer: ByteBuffer = ByteBuffer.allocate(TEMP_BYTE_BUFFER_SIZE)

    val stringsCache = StringsCache(stringCharset)

    inner class StringsCache(private val stringCharset: Charset) : LruCache<ByteBuffer, String>(STRINGS_CACHE_SIZE) {
        override fun sizeOf(key: ByteBuffer, value: String): Int {
            // Approximation
            return key.capacity() + value.length * Char.SIZE_BYTES
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
