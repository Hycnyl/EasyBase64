package com.orbis.easybase64.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import java.util.Random
import java.util.concurrent.CancellationException

class Base64CodecTest {
    private val decode = CodecOptions(operation = Operation.DECODE)
    private fun convert(bytes: ByteArray, options: CodecOptions = CodecOptions()): ByteArray = ByteArrayOutputStream().also {
        Base64Codec.transform(ByteArrayInputStream(bytes), it, options)
    }.toByteArray()
    private fun decode(text: String, options: CodecOptions = decode): ByteArray = convert(text.toByteArray(Charsets.UTF_8), options)
    private fun rejects(text: String, options: CodecOptions = decode, code: String? = null) {
        try { decode(text, options); fail("Accepted invalid input: ${text.take(100)}") }
        catch (error: CodecException) { if (code != null) assertEquals(code, error.code) }
    }

    @Test fun rfc4648Vectors() {
        val vectors = mapOf("" to "", "f" to "Zg==", "fo" to "Zm8=", "foo" to "Zm9v", "foob" to "Zm9vYg==", "fooba" to "Zm9vYmE=", "foobar" to "Zm9vYmFy")
        for ((plain, encoded) in vectors) {
            assertEquals(encoded, convert(plain.toByteArray()).toString(Charsets.US_ASCII))
            assertEquals(plain, decode(encoded).toString(Charsets.US_ASCII))
        }
        assertEquals("+/8=", convert(byteArrayOf(-5, -1)).toString(Charsets.US_ASCII))
        assertEquals("-_8=", convert(byteArrayOf(-5, -1), CodecOptions(alphabet = Alphabet.URL_SAFE)).toString(Charsets.US_ASCII))
    }

    @Test fun allEncodingOptionsMatchIndependentReference() {
        val random = Random(4648)
        for (size in listOf(0, 1, 2, 3, 47, 48, 49, 56, 57, 58, 127, 65535, 65536, 65537)) {
            val source = ByteArray(size).also(random::nextBytes)
            for (alphabet in Alphabet.entries) for (padding in listOf(false, true))
                for (width in listOf(0, 64, 76)) for (ending in LineEnding.entries) for (finalNewline in listOf(false, true)) {
                    var encoder = if (alphabet == Alphabet.STANDARD) Base64.getEncoder() else Base64.getUrlEncoder()
                    if (!padding) encoder = encoder.withoutPadding()
                    val raw = encoder.encodeToString(source)
                    val separator = if (ending == LineEnding.LF) "\n" else "\r\n"
                    val expected = (if (width == 0) raw else raw.chunked(width).joinToString(separator)) +
                        if (finalNewline && raw.isNotEmpty()) separator else ""
                    val options = CodecOptions(alphabet = alphabet, padding = padding, wrapWidth = width, lineEnding = ending, finalNewline = finalNewline)
                    val actual = convert(source, options)
                    assertEquals("size=$size options=$options", expected, actual.toString(Charsets.US_ASCII))
                    assertArrayEquals(source, convert(actual, decode.copy(alphabet = alphabet, allowOmittedPadding = !padding)))
                }
        }
    }

    @Test fun certificateEncodingIsExactAndForcesContract() {
        val forced = CodecOptions(certificate = true, alphabet = Alphabet.URL_SAFE, padding = false, wrapWidth = 76, lineEnding = LineEnding.LF, finalNewline = false)
        assertEquals("-----BEGIN CERTIFICATE-----\r\n-----END CERTIFICATE-----\r\n", convert(byteArrayOf(), forced).toString(Charsets.US_ASCII))
        assertEquals("-----BEGIN CERTIFICATE-----\r\nZg==\r\n-----END CERTIFICATE-----\r\n", convert("f".toByteArray(), forced).toString(Charsets.US_ASCII))
        for (size in listOf(48, 49, 96, 65536, 65537)) {
            val bytes = ByteArray(size) { (it * 13).toByte() }
            val body = Base64.getEncoder().encodeToString(bytes).chunked(64).joinToString("\r\n")
            val actual = convert(bytes, forced)
            assertEquals("-----BEGIN CERTIFICATE-----\r\n$body\r\n-----END CERTIFICATE-----\r\n", actual.toString(Charsets.US_ASCII))
            assertArrayEquals(bytes, convert(actual, decode))
        }
    }

    @Test fun whitespaceAndEnvelopeControlsAreIndependent() {
        val wrapped = "-----BEGIN CERTIFICATE-----\nZm9v\n-----END CERTIFICATE-----"
        for (whitespace in listOf(false, true)) for (envelope in listOf(false, true)) {
            val options = decode.copy(ignoreWhitespace = whitespace, recognizeCertificate = envelope)
            assertEquals("foo", decode("Zm9v", options).toString(Charsets.US_ASCII))
            if (whitespace) assertEquals("foo", decode(" \tZ m\r9v\n", options).toString(Charsets.US_ASCII))
            else rejects(" \tZ m\r9v\n", options, "INVALID_CHARACTER")
            if (whitespace && envelope) assertEquals("foo", decode(wrapped, options).toString(Charsets.US_ASCII))
            else rejects(wrapped, options)
        }
        assertArrayEquals(byteArrayOf(), decode("\t -----BEGIN CERTIFICATE----- \t\r\n\t-----END CERTIFICATE----- \t", decode.copy(ignoreWhitespace = false)))
        rejects(wrapped, decode.copy(alphabet = Alphabet.URL_SAFE), "CONFIGURATION_MISMATCH")
        for (c in listOf('\u000b', '\u000c')) rejects("Z${c}g==", code = "INVALID_CHARACTER")
    }

    @Test fun bomOnlyAtPhysicalStartAndSupportedEncoding() {
        assertEquals("f", decode("\uFEFFZg==", decode.copy(ignoreWhitespace = false)).toString(Charsets.US_ASCII))
        assertEquals("f", decode("\uFEFF \n-----BEGIN CERTIFICATE-----\r\nZg==\r\n-----END CERTIFICATE-----\n\t").toString(Charsets.US_ASCII))
        for (bad in listOf("Z\uFEFFg==", " \uFEFFZg==", "\uFEFF\uFEFFZg==", "Zg==\uFEFF", "Zé==", "Z\u0000g\u0000=\u0000=\u0000"))
            rejects(bad, code = "UNSUPPORTED_TEXT_ENCODING")
        for (bytes in listOf(byteArrayOf(-1, -2, 90, 0), byteArrayOf(-2, -1, 0, 90), byteArrayOf(-17), byteArrayOf(-17, -69))) {
            try { convert(bytes, decode); fail("Unsupported encoding accepted") } catch (e: CodecException) { assertEquals("UNSUPPORTED_TEXT_ENCODING", e.code) }
        }
    }

    @Test fun malformedOrMultipleBlocksRejected() {
        val begin = "-----BEGIN CERTIFICATE-----"
        val end = "-----END CERTIFICATE-----"
        for (bad in listOf(
            "$begin\nZg==\n", "$begin\nZg==\n-----END PUBLIC KEY-----", "$begin\n$begin\nZg==\n$end\n$end",
            "$begin\nZg==\n$end\n$begin\nZg==\n$end", "description\n$begin\nZg==\n$end",
            "$begin\nZg==\n$end\ncomment", "$begin\nZg==$end", "$end", "$begin", "$begin\rZg==\r$end",
            "-----BEGIN PRIVATE KEY-----\nZg==\n-----END PRIVATE KEY-----", "$begin extra\nZg==\n$end",
            "$begin\nZg==\n$end trailing", "$begin\nZg==\n$end\r", "$begin\nZg==\n" + "-".repeat(257)
        )) rejects(bad)
        assertEquals("f", decode(" \t\r\n $begin\t \nZg==\n\t $end \t\r\n\t \r\n").toString(Charsets.US_ASCII))
        rejects("-----BEGIN CERTIFICATE-----" + " ".repeat(300) + "\nZg==\n$end", code = "ENVELOPE_MISMATCH")
    }

    @Test fun canonicalPaddingAndOmittedPadding() {
        for (allowed in listOf(false, true)) {
            val options = decode.copy(allowOmittedPadding = allowed)
            for (valid in listOf("", "AAAA", "AA==", "AAA=", "Zm9v", "Zg==", "Zm8=")) decode(valid, options)
            for (bad in listOf("A", "AAAAA", "=AAA", "A===", "====", "AA=", "AAA==", "AA===", "AAAA=", "AA=A", "AB==", "AAB=", "Zh==", "Zm9=", "Zg==Zg==", "Zg==!", "Zg===")) rejects(bad, options)
            if (allowed) {
                assertEquals("f", decode("Zg", options).toString(Charsets.US_ASCII))
                assertEquals("fo", decode("Zm8", options).toString(Charsets.US_ASCII))
            } else { rejects("Zg", options, "INVALID_PADDING"); rejects("Zm8", options, "INVALID_PADDING") }
            rejects("Zh", options, "INVALID_PADDING")
            rejects("Zm9", options, "INVALID_PADDING")
        }
    }

    @Test fun neverMixAlphabets() {
        rejects("-_8=", code = "INVALID_CHARACTER")
        rejects("+/8=", decode.copy(alphabet = Alphabet.URL_SAFE), "INVALID_CHARACTER")
        rejects("+-8=", decode.copy(alphabet = Alphabet.URL_SAFE), "INVALID_CHARACTER")
        assertArrayEquals(byteArrayOf(-5, -1), decode("-_8=", decode.copy(alphabet = Alphabet.URL_SAFE)))
        // Hyphens at a line start are valid URL-safe data unless they form a boundary marker.
        for (text in listOf("----", "-----AAA", "----\n----", "-----BEGINAA")) {
            assertArrayEquals(Base64.getUrlDecoder().decode(text.replace("\n", "")), decode(text, decode.copy(alphabet = Alphabet.URL_SAFE)))
        }
    }

    @Test fun fullEofValidatedAfterPadding() {
        for (tail in listOf("A", "!", "=", "Zg==", "-----BEGIN CERTIFICATE-----\nZg==\n-----END CERTIFICATE-----")) {
            rejects("Zg== \r\n$tail")
            rejects("-----BEGIN CERTIFICATE-----\nZg==\n-----END CERTIFICATE-----\n$tail")
        }
        val source = object : InputStream() {
            val content = "Zg==".toByteArray()
            var position = 0
            override fun read(): Int = if (position < content.size) content[position++].toInt() else throw IOException("tail disconnected")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (position == content.size) throw IOException("tail disconnected")
                b[off] = content[position++]
                return 1
            }
        }
        try { Base64Codec.transform(source, ByteArrayOutputStream(), decode); fail("Ignored tail read failure") }
        catch (e: IOException) { assertEquals("tail disconnected", e.message) }
    }

    @Test fun oneByteAndIrregularShortReadsCrossEveryBoundary() {
        val bytes = ByteArray(131_083).also(Random(23)::nextBytes)
        for (step in listOf(1, 3, 7, 127, 65535)) {
            for (certificate in listOf(false, true)) {
                val encoded = convert(bytes, CodecOptions(certificate = certificate, wrapWidth = 76, lineEnding = LineEnding.CRLF))
                val source = ShortReads(byteArrayOf(-17, -69, -65) + encoded, step)
                val output = ByteArrayOutputStream()
                Base64Codec.transform(source, output, decode)
                assertArrayEquals(bytes, output.toByteArray())
            }
        }
    }

    @Test fun arbitraryUnwrappedLineHasNoLineLengthLimit() {
        val bytes = ByteArray(3 * 1024 * 1024 + 2).also(Random(42)::nextBytes)
        val encoded = Base64.getEncoder().encode(bytes)
        assertArrayEquals(bytes, convert(encoded, decode))
        val leadingWhitespace = object : InputStream() {
            var whitespace = 1_000_000
            val rest = ByteArrayInputStream("-----BEGIN CERTIFICATE-----\nZg==\n-----END CERTIFICATE-----".toByteArray())
            override fun read() = if (whitespace-- > 0) ' '.code else rest.read()
        }
        val output = ByteArrayOutputStream()
        Base64Codec.transform(leadingWhitespace, output, decode)
        assertEquals("f", output.toString("US-ASCII"))
    }

    @Test fun failuresAndCancellationPropagateAndOwnershipIsPreserved() {
        val source = object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
            var closed = false
            override fun close() { closed = true; throw IOException("owner close") }
        }
        val sink = object : ByteArrayOutputStream() {
            var closed = false
            override fun close() { closed = true; throw IOException("owner close") }
        }
        Base64Codec.transform(source, sink, CodecOptions())
        assertFalse(source.closed)
        assertFalse(sink.closed)
        for (operation in Operation.entries) {
            val content = if (operation == Operation.ENCODE) ByteArray(131072) else ByteArray(131072) { 'A'.code.toByte() }
            val options = CodecOptions(operation = operation)
            val readFailure = object : InputStream() { override fun read(): Int = throw IOException("read failure") }
            try { Base64Codec.transform(readFailure, ByteArrayOutputStream(), options); fail("Read failure swallowed") }
            catch (e: IOException) { assertEquals("read failure", e.message) }
            for (flushFailure in listOf(false, true)) {
                val failure = object : OutputStream() {
                    override fun write(b: Int) { if (!flushFailure) throw IOException("write failure") }
                    override fun flush() { if (flushFailure) throw IOException("flush failure") }
                }
                try { Base64Codec.transform(ByteArrayInputStream(content), failure, options); fail("Write failure swallowed") }
                catch (e: IOException) { assertEquals(if (flushFailure) "flush failure" else "write failure", e.message) }
            }
            var checks = 0
            try { Base64Codec.transform(ByteArrayInputStream(content), ByteArrayOutputStream(), options, { ++checks > 3 }); fail("Cancellation ignored") }
            catch (_: CancellationException) { assertTrue(checks > 3) }
        }
    }

    @Test fun progressUsesRawBytesAndFinalOutputLength() {
        for (options in listOf(CodecOptions(certificate = true), decode)) {
            val source = if (options.operation == Operation.ENCODE) ByteArray(100_000) else " \nZg==\r\n".toByteArray()
            val sink = ByteArrayOutputStream()
            var read = -1L
            var written = -1L
            Base64Codec.transform(ByteArrayInputStream(source), sink, options, progress = { r, w ->
                assertTrue(r >= read); assertTrue(w >= written); read = r; written = w
            })
            assertEquals(source.size.toLong(), read)
            assertEquals(sink.size().toLong(), written)
        }
    }

    @Test fun decodedTailsAroundThreeByteAlignedReadBoundariesMatchArrayOracle() {
        for (size in listOf(0, 1, 2, 3, 4, 65_534, 65_535, 65_536, 65_537, 65_538,
            131_069, 131_070, 131_071, 131_072, 131_073)) {
            val source = ByteArray(size).also(Random(size.toLong())::nextBytes)
            for (alphabet in Alphabet.entries) for (padding in listOf(false, true)) {
                var encoder = if (alphabet == Alphabet.STANDARD) Base64.getEncoder() else Base64.getUrlEncoder()
                if (!padding) encoder = encoder.withoutPadding()
                val encoded = encoder.encode(source)
                val arrayDecoder = if (alphabet == Alphabet.STANDARD) Base64.getDecoder() else Base64.getUrlDecoder()
                assertArrayEquals(arrayDecoder.decode(encoded), convert(encoded,
                    decode.copy(alphabet = alphabet, allowOmittedPadding = !padding)))
            }
        }
    }

    @Test fun providerWritesAreBufferedIncludingScalarTailAndCertificateFormatting() {
        for (size in listOf(1, 2, 3, 65_537)) {
            val source = ByteArray(size) { (it * 71).toByte() }
            for (options in listOf(
                CodecOptions(), CodecOptions(padding = false),
                CodecOptions(wrapWidth = 64, lineEnding = LineEnding.CRLF, finalNewline = true),
                CodecOptions(certificate = true),
            )) {
                val sink = BulkOnlySink()
                Base64Codec.transform(ByteArrayInputStream(source), sink, options)
                assertArrayEquals(convert(source, options), sink.bytes.toByteArray())
                assertTrue(sink.bulkWrites > 0)
                assertTrue(sink.bulkWrites <= (sink.bytes.size() + Base64Codec.BUFFER_SIZE - 1) / Base64Codec.BUFFER_SIZE + 1)
                assertTrue(sink.flushes > 0)
                assertFalse(sink.closed)

                val decoded = BulkOnlySink()
                Base64Codec.transform(ByteArrayInputStream(sink.bytes.toByteArray()), decoded,
                    decode.copy(allowOmittedPadding = !options.padding))
                assertArrayEquals(source, decoded.bytes.toByteArray())
                assertFalse(decoded.closed)
            }
        }
    }

    @Test fun failuresReportFinalBytesWithoutMaskingTheOriginalError() {
        val bytes = "Zg==!".toByteArray(Charsets.US_ASCII)
        val output = ByteArrayOutputStream()
        var read = -1L
        var written = -1L
        try {
            Base64Codec.transform(ByteArrayInputStream(bytes), output, decode, progress = { r, w -> read = r; written = w })
            fail("Trailing data accepted")
        } catch (error: CodecException) { assertEquals("TRAILING_DATA", error.code) }
        assertEquals(bytes.size.toLong(), read)
        assertTrue(read > 0)
        assertEquals(output.size().toLong(), written)
        assertEquals(0L, written) // The pending partial buffer must not flush after validation fails.

        val largeOutput = ByteArrayOutputStream()
        val largeInput = ("AAAA".repeat(32_768) + "Zg==!").toByteArray(Charsets.US_ASCII)
        try {
            Base64Codec.transform(ByteArrayInputStream(largeInput), largeOutput, decode,
                progress = { r, w -> read = r; written = w })
            fail("Trailing data accepted")
        } catch (error: CodecException) { assertEquals("TRAILING_DATA", error.code) }
        assertEquals(largeInput.size.toLong(), read)
        assertEquals(largeOutput.size().toLong(), written)
        assertTrue(written > 0)

        val callbackFailure = IllegalStateException("Progress callback failed")
        var failedRead = -1L
        try {
            Base64Codec.transform(ByteArrayInputStream(byteArrayOf('!'.code.toByte())), ByteArrayOutputStream(), decode,
                progress = { r, _ ->
                    failedRead = r
                    if (r > 0) throw callbackFailure
                })
            fail("Invalid character accepted")
        } catch (error: CodecException) {
            assertEquals("INVALID_CHARACTER", error.code)
            assertArrayEquals(arrayOf(callbackFailure), error.suppressed)
        }
        assertEquals(1L, failedRead)
    }

    @Test fun characterErrorsReportPhysicalByteOffsets() {
        for ((text, expected) in listOf("-AAA" to 0L, "\uFEFF!AAA" to 3L, "Zg==!" to 4L)) {
            try { decode(text); fail("Invalid character accepted") }
            catch (error: CodecException) { assertEquals(expected, error.offset) }
        }
        try { decode("  Zm9v", decode.copy(ignoreWhitespace = false)); fail("Whitespace accepted") }
        catch (error: CodecException) { assertEquals(0L, error.offset) }
    }

    private class BulkOnlySink : OutputStream() {
        val bytes = ByteArrayOutputStream()
        var bulkWrites = 0
        var flushes = 0
        var closed = false
        override fun write(b: Int): Unit = throw AssertionError("Scalar write reached the document provider")
        override fun write(b: ByteArray, off: Int, len: Int) { bulkWrites++; bytes.write(b, off, len) }
        override fun flush() { flushes++ }
        override fun close() { closed = true }
    }

    private class ShortReads(bytes: ByteArray, private val step: Int) : ByteArrayInputStream(bytes) {
        private var calls = 0
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, 1 + (calls++ % step)))
    }
}
