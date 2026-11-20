package com.hycnyl.easybase64.core

import java.io.ByteArrayOutputStream
import java.nio.charset.CharacterCodingException

/** Converts UTF-8 text using the same format defaults and validation as file conversion. */
object TextCodec {
    fun transform(input: String, operation: Operation, cancelled: () -> Boolean = { false }): String {
        val bytes = try {
            input.encodeToByteArray(throwOnInvalidSequence = true)
        } catch (e: CharacterCodingException) {
            throw CodecException("INVALID_UNICODE", "Input contains an unpaired surrogate")
        }
        val output = ByteArrayOutputStream()
        Base64Codec.transform(bytes.inputStream(), output, CodecOptions(operation = operation), cancelled)
        if (operation == Operation.ENCODE) return output.toString("US-ASCII")
        return try {
            output.toByteArray().decodeToString(throwOnInvalidSequence = true)
        } catch (e: CharacterCodingException) {
            throw CodecException("INVALID_UTF8", "Decoded bytes are not valid UTF-8 text")
        }
    }
}
