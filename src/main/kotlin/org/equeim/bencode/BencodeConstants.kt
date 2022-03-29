package org.equeim.bencode

import kotlinx.serialization.SerializationException
import kotlin.math.log10

internal const val BYTE_ARRAY_LENGTH_VALUE_SEPARATOR = ':'

internal const val INTEGER_PREFIX = 'i'
internal const val INTEGER_NEGATIVE_SIGN = '-'
internal const val INTEGER_TERMINATOR = 'e'
internal val INTEGER_MAX_DIGITS = (Long.SIZE_BITS * log10(2.0)).toInt()
internal fun Char.toAsciiDigit(): Int {
    if (this in '0'..'9') return this - '0'
    throw SerializationException("Character '$this' is not an ASCII digit")
}

internal const val LIST_PREFIX = 'l'
internal const val LIST_TERMINATOR = 'e'

internal const val DICTIONARY_PREFIX = 'd'
internal const val DICTIONARY_TERMINATOR = 'e'


