package com.orbis.easybase64.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** Opt-in, real disk-backed stream benchmark; never part of the ordinary unit test run. */
object LargeFileBenchmark {
    @JvmStatic fun main(args: Array<String>) {
        val folder = File(args.firstOrNull() ?: "build/large-file-benchmark").also { it.mkdirs() }
        val sizes = args.getOrNull(1)?.split(',')?.map(String::toLong) ?: listOf(16L * 1024 * 1024, 1024L * 1024 * 1024)
        println("JVM=${System.getProperty("java.version")} OS=${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
        println("processors=${Runtime.getRuntime().availableProcessors()} heapMax=${Runtime.getRuntime().maxMemory()} bufferBytes=${Base64Codec.BUFFER_SIZE}")
        println("sizeBytes,sourceSha256,recoveredSha256,encodeSeconds,decodeSeconds,peakSampledHeapBytes,heapCommittedBytes")
        for (size in sizes) runSize(folder, size)
    }

    private fun runSize(folder: File, size: Long) {
        val source = File(folder, "source-$size.bin")
        val encoded = File(folder, "encoded-$size.b64")
        val recovered = File(folder, "recovered-$size.bin")
        val bytes = ByteArray(Base64Codec.BUFFER_SIZE) { ((it * 73 + (it ushr 8) * 19 + 31) and 255).toByte() }
        BufferedOutputStream(source.outputStream(), Base64Codec.BUFFER_SIZE).use { output ->
            var left = size
            while (left > 0) { val n = minOf(left, bytes.size.toLong()).toInt(); output.write(bytes, 0, n); left -= n }
        }
        val originalHash = sha256(source)
        System.gc()
        ManagementFactory.getMemoryPoolMXBeans().forEach { it.resetPeakUsage() }
        val peak = AtomicLong(0)
        val monitoring = AtomicBoolean(true)
        val sampler = thread(name = "bounded-memory-sampler", isDaemon = true) {
            while (monitoring.get()) {
                val runtime = Runtime.getRuntime()
                val used = runtime.totalMemory() - runtime.freeMemory()
                peak.accumulateAndGet(used, ::maxOf)
                Thread.sleep(50)
            }
        }
        fun convert(from: File, to: File, options: CodecOptions): Double {
            val start = System.nanoTime()
            BufferedInputStream(from.inputStream(), Base64Codec.BUFFER_SIZE).use { input ->
                BufferedOutputStream(to.outputStream(), Base64Codec.BUFFER_SIZE).use { output ->
                    Base64Codec.transform(input, output, options)
                }
            }
            return (System.nanoTime() - start) / 1e9
        }
        val encodeSeconds = convert(source, encoded, CodecOptions())
        val decodeSeconds = convert(encoded, recovered, CodecOptions(operation = Operation.DECODE))
        monitoring.set(false)
        sampler.join()
        val recoveredHash = sha256(recovered)
        check(source.length() == size && recovered.length() == size)
        check(originalHash == recoveredHash) { "SHA-256 mismatch" }
        println("$size,$originalHash,$recoveredHash,$encodeSeconds,$decodeSeconds,${peak.get()},${Runtime.getRuntime().totalMemory()}")
        check(source.delete() && encoded.delete() && recovered.delete()) { "Could not clean benchmark files" }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(Base64Codec.BUFFER_SIZE)
        file.inputStream().use { input ->
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
