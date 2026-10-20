package com.orbis.easybase64

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.orbis.easybase64.core.Alphabet
import com.orbis.easybase64.core.Operation
import com.orbis.easybase64.core.Base64Codec
import com.orbis.easybase64.core.CodecOptions
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64

/** Android 12's streaming encoder emits scalar writes; these must not become SAF file syscalls. */
@RunWith(AndroidJUnit4::class)
class PlatformCodecIntegrationTest {
    @Test fun platformEncoderUsesBoundedBulkWritesWithExactOutput() {
        val payload = ByteArray(256 * 1024) { ((it * 37 + 19) and 255).toByte() }
        // Independent non-streaming platform implementation is a bounded test oracle only.
        val expected = Base64.getEncoder().encode(payload)
        val expectedDigest = MessageDigest.getInstance("SHA-256").digest(expected)
        val sink = CountingDigestSink()

        Base64Codec.transform(ByteArrayInputStream(payload), sink, CodecOptions())

        assertEquals(expected.size.toLong(), sink.byteCount)
        assertArrayEquals(expectedDigest, sink.digest.digest())
        assertEquals("Each encoded character must not trigger a downstream write", 0, sink.scalarWrites)
        assertTrue("Output must be flushed in a bounded number of bulk writes", sink.bulkWrites in 1..16)
        assertTrue("Each write remains bounded", sink.maxBulkLength <= 128 * 1024)
        assertFalse("The caller retains ownership of its output stream", sink.closed)
    }

    @Test fun platformDecoderPreservesExactBytesAtQuantumAndBufferBoundaries() {
        val lengths = listOf(0, 1, 2, 3, 4, 5, 65_534, 65_535, 65_536, 65_537, 65_538, 65_539,
            131_069, 131_070, 131_071, 131_072, 131_073)
        for (length in lengths) for (alphabet in Alphabet.entries) for (padding in listOf(true, false)) {
            val payload = ByteArray(length) { ((it * 37 + 11) and 255).toByte() }
            var encoder = if (alphabet == Alphabet.STANDARD) Base64.getEncoder() else Base64.getUrlEncoder()
            if (!padding) encoder = encoder.withoutPadding()
            val encoded = encoder.encode(payload)
            val sink = CountingDigestSink()
            var bytesRead = -1L
            var bytesWritten = -1L
            Base64Codec.transform(ByteArrayInputStream(encoded), sink,
                CodecOptions(operation = Operation.DECODE, alphabet = alphabet, allowOmittedPadding = !padding),
                progress = { read, written -> bytesRead = read; bytesWritten = written })
            val context = "length=$length alphabet=$alphabet padding=$padding"
            assertEquals(context, length.toLong(), sink.byteCount)
            assertArrayEquals(context, MessageDigest.getInstance("SHA-256").digest(payload), sink.digest.digest())
            assertEquals(context, encoded.size.toLong(), bytesRead)
            assertEquals(context, length.toLong(), bytesWritten)
            assertFalse(context, sink.closed)
        }
    }

    private class CountingDigestSink : OutputStream() {
        val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
        var byteCount = 0L
        var scalarWrites = 0
        var bulkWrites = 0
        var maxBulkLength = 0
        var closed = false

        override fun write(value: Int) {
            scalarWrites++
            byteCount++
            digest.update(value.toByte())
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (length == 0) return
            bulkWrites++
            byteCount += length
            maxBulkLength = maxOf(maxBulkLength, length)
            digest.update(bytes, offset, length)
        }

        override fun close() { closed = true }
    }
}
