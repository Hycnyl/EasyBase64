package com.hycnyl.easybase64.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import java.util.concurrent.CancellationException

enum class Operation { ENCODE, DECODE }
enum class Alphabet { STANDARD, URL_SAFE }
enum class LineEnding { LF, CRLF }

data class CodecOptions(
    val operation: Operation = Operation.ENCODE,
    val alphabet: Alphabet = Alphabet.STANDARD,
    val padding: Boolean = true,
    val wrapWidth: Int = 0,
    val lineEnding: LineEnding = LineEnding.LF,
    val finalNewline: Boolean = false,
    val certificate: Boolean = false,
    val ignoreWhitespace: Boolean = true,
    val recognizeCertificate: Boolean = true,
    val allowOmittedPadding: Boolean = false,
) {
    init { require(wrapWidth in listOf(0, 64, 76)) { "Wrap width must be 0, 64 or 76" } }
}

class CodecException(val code: String, message: String, val offset: Long? = null) : IOException(message)

/** Bounded-memory conversion. Callers own both streams and must check their close failures. */
object Base64Codec {
    const val BUFFER_SIZE = 64 * 1024

    fun transform(
        input: InputStream,
        output: OutputStream,
        options: CodecOptions,
        cancelled: () -> Boolean = { false },
        progress: (read: Long, written: Long) -> Unit = { _, _ -> },
    ) {
        val meter = Meter(cancelled, progress)
        val source = object : FilterInputStream(input) {
            override fun read(): Int {
                meter.check()
                return `in`.read().also { if (it >= 0) meter.read++ }
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                meter.check()
                return `in`.read(b, off, len).also { if (it > 0) meter.read += it }
            }
            override fun close() { /* The SAF owner closes the original stream. */ }
        }
        val meteredSink = object : FilterOutputStream(output) {
            override fun write(b: Int) { out.write(b); meter.written++ }
            override fun write(b: ByteArray, off: Int, len: Int) {
                meter.check()
                out.write(b, off, len)
                meter.written += len
                meter.report()
            }
            override fun close() { flush() }
        }
        // Android libcore's encoder emits the body with write(int), including every Base64
        // character on Android 12. Batch those writes before they reach a SAF provider.
        // Count only completed writes to the caller, and never flush pending bytes on failure.
        val sink = BufferedOutputStream(meteredSink, BUFFER_SIZE)
        try {
            meter.check()
            if (options.operation == Operation.ENCODE) encode(source, sink, options, meter)
            else decode(source, sink, options, meter)
            sink.flush()
            meter.check()
        } catch (failure: Throwable) {
            // Small failures can happen before the next throttled update. Preserve their byte counts.
            try { meter.report(force = true) }
            catch (reportFailure: Throwable) {
                if (reportFailure !== failure) failure.addSuppressed(reportFailure)
            }
            throw failure
        }
        meter.report(force = true)
    }

    private fun encode(input: InputStream, output: OutputStream, options: CodecOptions, meter: Meter) {
        val certificate = options.certificate
        val width = if (certificate) 64 else options.wrapWidth
        val separator = if (certificate || options.lineEnding == LineEnding.CRLF) CRLF else LF
        if (certificate) output.write((BEGIN + "\r\n").toByteArray(Charsets.US_ASCII))
        val formatter = FormattingOutput(output, width, separator)
        var encoder = if (certificate || options.alphabet == Alphabet.STANDARD) Base64.getEncoder()
            else Base64.getUrlEncoder()
        if (!certificate && !options.padding) encoder = encoder.withoutPadding()
        val wrapped = encoder.wrap(formatter)
        copy(input, wrapped, meter)
        // Encoder.close writes its final quantum; formatter.close deliberately retains output ownership.
        wrapped.close()
        if (formatter.hasBody && (certificate || options.finalNewline)) output.write(separator)
        if (certificate) output.write((END + "\r\n").toByteArray(Charsets.US_ASCII))
    }

    private fun decode(input: InputStream, output: OutputStream, options: CodecOptions, meter: Meter) {
        val body = ValidatedBody(input, options, meter)
        val decoder = (if (options.alphabet == Alphabet.STANDARD) Base64.getDecoder()
            else Base64.getUrlDecoder()).wrap(body)
        // Android 12's libcore decoder can emit extra zero bytes when a two-byte
        // final group straddles a read: it drains nextout, then processes EOF again.
        // A decoded read length divisible by three never splits a Base64 quantum,
        // so the final one/two bytes always fit without entering that broken path.
        copy(decoder, output, meter, BUFFER_SIZE - BUFFER_SIZE % 3)
        // java.util.Base64 may stop at padding without reading EOF. Explicitly validate EVERYTHING.
        body.verifyPhysicalEnd()
    }

    private fun copy(input: InputStream, output: OutputStream, meter: Meter, bufferSize: Int = BUFFER_SIZE) {
        val buffer = ByteArray(bufferSize)
        while (true) {
            meter.check()
            val count = input.read(buffer)
            if (count < 0) return
            if (count == 0) {
                val byte = input.read()
                if (byte < 0) return
                output.write(byte)
            } else output.write(buffer, 0, count)
            meter.report()
        }
    }

    private class FormattingOutput(
        private val sink: OutputStream,
        private val width: Int,
        private val separator: ByteArray,
    ) : OutputStream() {
        private var column = 0
        var hasBody = false
            private set
        override fun write(b: Int) {
            if (width > 0 && column == width) { sink.write(separator); column = 0 }
            sink.write(b)
            column++
            hasBody = true
        }
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len == 0) return
            hasBody = true
            if (width == 0) { sink.write(b, off, len); return }
            var cursor = off
            val end = off + len
            while (cursor < end) {
                if (column == width) { sink.write(separator); column = 0 }
                val count = minOf(width - column, end - cursor)
                sink.write(b, cursor, count)
                cursor += count
                column += count
            }
        }
        override fun flush() = sink.flush()
        override fun close() = flush()
    }

    private class Meter(val cancelled: () -> Boolean, val progress: (Long, Long) -> Unit) {
        var read = 0L
        var written = 0L
        private var lastReport = 0L
        fun check() { if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("Cancelled") }
        fun report(force: Boolean = false) {
            val now = System.nanoTime()
            if (force || now - lastReport >= 150_000_000L) {
                progress(read, written)
                lastReport = now
            }
        }
    }

    /** Returns only validated Base64 symbols. Parsing buffers are fixed, independent of line length. */
    private class ValidatedBody(input: InputStream, private val options: CodecOptions, private val meter: Meter) : InputStream() {
        private val source = BufferedInputStream(input, BUFFER_SIZE)
        private var offset = 0L
        private var currentOffset = 0L
        private var initialized = false
        private var mode = Mode.PLAIN
        private var lineStart = true
        private var replay = ByteArray(0)
        private var replayIndex = 0
        private var replayOffset = 0L
        private var quantum = 0
        private var lastValue = 0
        private var needSecondPad = false
        private var paddedEnd = false
        private var verified = false

        override fun read(): Int {
            if (verified) return -1
            val next = nextSymbol()
            if (next == -1) { verifyQuantum(); verified = true; return -1 }
            if (next == '='.code) {
                if (paddedEnd) fail("INVALID_PADDING", "Too many padding characters")
                if (needSecondPad) {
                    needSecondPad = false
                    paddedEnd = true
                    quantum = 0
                } else when (quantum) {
                    2 -> {
                        if (lastValue and 15 != 0) fail("INVALID_PADDING", "Non-zero unused padding bits")
                        needSecondPad = true
                        quantum = 3
                    }
                    3 -> {
                        if (lastValue and 3 != 0) fail("INVALID_PADDING", "Non-zero unused padding bits")
                        paddedEnd = true
                        quantum = 0
                    }
                    else -> fail("INVALID_PADDING", "Padding is not valid at this position")
                }
                return next
            }
            if (paddedEnd) fail("TRAILING_DATA", "Data follows the final padded group")
            if (needSecondPad) fail("INVALID_PADDING", "A second padding character is required")
            lastValue = value(next)
            if (lastValue < 0) fail("INVALID_CHARACTER", "Character is not in the selected Base64 alphabet")
            quantum = (quantum + 1) and 3
            return next
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            require(off >= 0 && len >= 0 && off <= b.size - len)
            if (len == 0) return 0
            var count = 0
            while (count < len) {
                val c = read()
                if (c < 0) break
                b[off + count++] = c.toByte()
            }
            return if (count == 0) -1 else count
        }
        override fun close() { /* EOF validation is explicit, never an effect of close. */ }
        fun verifyPhysicalEnd() {
            while (read() != -1) { /* Reject or drain every trailing symbol and container byte. */ }
            check(verified)
        }

        private fun verifyQuantum() {
            if (needSecondPad) fail("INVALID_PADDING", "Incomplete padding")
            if (quantum == 1) fail("INVALID_PADDING", "A one-character final group cannot be decoded")
            if (quantum != 0) {
                if (!options.allowOmittedPadding) fail("INVALID_PADDING", "Padding is required for the final group")
                val mask = if (quantum == 2) 15 else 3
                if (lastValue and mask != 0) fail("INVALID_PADDING", "Non-zero unused padding bits")
            }
        }

        private fun nextSymbol(): Int {
            if (!initialized) initialize()
            while (true) {
                val c = when (mode) {
                    Mode.PLAIN -> nextPlain()
                    Mode.WRAPPED -> nextWrapped()
                    Mode.AFTER -> {
                        var tail: Int
                        do { tail = raw() } while (isWhitespace(tail))
                        if (tail >= 0) fail("TRAILING_DATA", "Only ASCII whitespace is allowed after the CERTIFICATE block")
                        mode = Mode.DONE
                        -1
                    }
                    Mode.DONE -> -1
                }
                if (c == INTERNAL_CONTINUE) continue
                if (c < 0) return -1
                if (isWhitespace(c)) {
                    if (!options.ignoreWhitespace) fail("INVALID_CHARACTER", "ASCII whitespace is disabled for the body")
                } else return c
            }
        }

        private fun initialize() {
            initialized = true
            var first = raw(allowBomByte = true)
            if (first == 0xEF) {
                if (raw(allowBomByte = true) != 0xBB || raw(allowBomByte = true) != 0xBF)
                    fail("UNSUPPORTED_TEXT_ENCODING", "Invalid UTF-8 BOM")
                first = raw()
            } else if (first > 127 || first == 0) {
                fail("UNSUPPORTED_TEXT_ENCODING", "Only ASCII / UTF-8 Base64 text is supported")
            }
            if (!options.recognizeCertificate) { replay = if (first < 0) ByteArray(0) else byteArrayOf(first.toByte()); replayOffset = currentOffset; return }
            var hadWhitespace = false
            val whitespaceOffset = currentOffset
            while (isWhitespace(first)) { hadWhitespace = true; first = raw() }
            if (first == '-'.code) {
                val marker = probeMarker(first)
                if (marker != null) {
                    val boundary = boundaryLine(marker)
                    if (boundary != BEGIN) fail("ENVELOPE_MISMATCH", "Expected a BEGIN CERTIFICATE boundary")
                    if (options.alphabet != Alphabet.STANDARD)
                        fail("CONFIGURATION_MISMATCH", "CERTIFICATE wrapping requires the Standard alphabet")
                    mode = Mode.WRAPPED
                    lineStart = true
                    return
                }
            } else {
                replay = if (first < 0) ByteArray(0) else byteArrayOf(first.toByte())
                replayOffset = currentOffset
            }
            if (hadWhitespace && !options.ignoreWhitespace) {
                currentOffset = whitespaceOffset
                fail("INVALID_CHARACTER", "ASCII whitespace is disabled for the body")
            }
        }

        private fun nextPlain(): Int {
            val c = if (replayIndex < replay.size) {
                currentOffset = replayOffset + replayIndex
                replay[replayIndex++].toInt() and 255
            } else {
                val current = raw()
                if (lineStart && current == '-'.code && options.recognizeCertificate) {
                    val marker = probeMarker(current)
                    if (marker != null) {
                        boundaryLine(marker)
                        fail("ENVELOPE_MISMATCH", "A CERTIFICATE block cannot follow plain body data")
                    }
                    return nextPlain()
                }
                current
            }
            if (c == '\n'.code) lineStart = true
            else if (c != ' '.code && c != '\t'.code && c != '\r'.code) lineStart = false
            return c
        }

        private fun nextWrapped(): Int {
            var c = raw()
            if (c < 0) fail("ENVELOPE_MISMATCH", "Missing END CERTIFICATE boundary")
            if (lineStart) {
                var indentation = false
                val indentationOffset = currentOffset
                while (c == ' '.code || c == '\t'.code) { indentation = true; c = raw() }
                if (c == '-'.code) {
                    val line = boundaryLine(byteArrayOf(c.toByte()))
                    if (line != END) fail("ENVELOPE_MISMATCH", "Nested, mismatched or malformed CERTIFICATE boundary")
                    mode = Mode.AFTER
                    return INTERNAL_CONTINUE
                }
                if (indentation && !options.ignoreWhitespace) {
                    currentOffset = indentationOffset
                    fail("INVALID_CHARACTER", "ASCII whitespace is disabled for the body")
                }
                if (c < 0) fail("ENVELOPE_MISMATCH", "Missing END CERTIFICATE boundary")
            }
            lineStart = c == '\n'.code
            return c
        }

        /** Probes only a fixed boundary prefix, replaying ordinary URL-safe characters on mismatch. */
        private fun probeMarker(first: Int): ByteArray? {
            val candidate = ByteArray(32)
            candidate[0] = first.toByte()
            var size = 1
            while (true) {
                val prefix = candidate.copyOf(size).toString(Charsets.US_ASCII)
                if (prefix == BEGIN_PREFIX || prefix == END_PREFIX) return candidate.copyOf(size)
                if (!BEGIN_PREFIX.startsWith(prefix) && !END_PREFIX.startsWith(prefix)) {
                    replay = candidate.copyOf(size)
                    replayOffset = offset - size
                    replayIndex = 0
                    return null
                }
                val c = raw()
                if (c < 0) {
                    replay = candidate.copyOf(size)
                    replayOffset = offset - size
                    replayIndex = 0
                    return null
                }
                candidate[size++] = c.toByte()
            }
        }

        private fun boundaryLine(prefix: ByteArray): String {
            val candidate = ByteArray(256)
            prefix.copyInto(candidate)
            var count = prefix.size
            var terminated = false
            while (true) {
                val c = raw()
                if (c < 0) break
                if (c == '\n'.code) { terminated = true; break }
                if (count == candidate.size) fail("ENVELOPE_MISMATCH", "Boundary line exceeds 256 bytes")
                candidate[count++] = c.toByte()
            }
            if (terminated && count > 0 && candidate[count - 1] == '\r'.code.toByte()) count--
            val text = String(candidate, 0, count, Charsets.US_ASCII).trim(' ', '\t')
            if (text.indexOf('\r') >= 0) fail("ENVELOPE_MISMATCH", "Boundary lines require LF or CRLF")
            if (text == BEGIN && !terminated) fail("ENVELOPE_MISMATCH", "BEGIN boundary must have a line ending and matching END")
            return text
        }

        private fun raw(allowBomByte: Boolean = false): Int {
            if (offset and 65535L == 0L) { meter.check(); meter.report() }
            val c = source.read()
            currentOffset = offset
            if (c >= 0) {
                offset++
                if (!allowBomByte && (c > 127 || c == 0))
                    fail("UNSUPPORTED_TEXT_ENCODING", "Only ASCII / UTF-8 Base64 text is supported; BOM is allowed only at the start")
            }
            return c
        }
        private fun value(c: Int): Int = when (c) {
            in 'A'.code..'Z'.code -> c - 'A'.code
            in 'a'.code..'z'.code -> c - 'a'.code + 26
            in '0'.code..'9'.code -> c - '0'.code + 52
            '+'.code -> if (options.alphabet == Alphabet.STANDARD) 62 else -1
            '/'.code -> if (options.alphabet == Alphabet.STANDARD) 63 else -1
            '-'.code -> if (options.alphabet == Alphabet.URL_SAFE) 62 else -1
            '_'.code -> if (options.alphabet == Alphabet.URL_SAFE) 63 else -1
            else -> -1
        }
        private fun fail(code: String, message: String): Nothing = throw CodecException(code, message, currentOffset)
        private enum class Mode { PLAIN, WRAPPED, AFTER, DONE }
    }

    private const val BEGIN = "-----BEGIN CERTIFICATE-----"
    private const val END = "-----END CERTIFICATE-----"
    private const val BEGIN_PREFIX = "-----BEGIN "
    private const val END_PREFIX = "-----END "
    private const val INTERNAL_CONTINUE = -2
    private val CRLF = byteArrayOf(13, 10)
    private val LF = byteArrayOf(10)
    private fun isWhitespace(c: Int) = c == 32 || c == 9 || c == 13 || c == 10
}
