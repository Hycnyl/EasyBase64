package com.hycnyl.easybase64.data

import com.hycnyl.easybase64.core.CodecOptions
import com.hycnyl.easybase64.core.Operation
import java.util.UUID

enum class TaskStatus { QUEUED, RUNNING, COMMITTING, SUCCEEDED, FAILED, CANCELLED, INTERRUPTED }
enum class BatchStatus { IDLE, READY, RUNNING, SUCCEEDED, PARTIAL, CANCELLED, INTERRUPTED }

data class TaskAttempt(
    val id: String = UUID.randomUUID().toString(),
    val status: TaskStatus = TaskStatus.RUNNING,
    val tempUri: String? = null,
    val finalUri: String? = null,
    val phase: String = "STARTING",
    val parentUri: String? = null,
    val plannedName: String? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val endedAt: Long? = null,
    val residualUris: List<String> = emptyList(),
    val errorCode: String? = null,
    val error: String? = null,
)

data class FileTask(
    val id: String = UUID.randomUUID().toString(),
    val sourceUri: String,
    val name: String,
    val relativePath: List<String> = emptyList(),
    val size: Long? = null,
    val status: TaskStatus = TaskStatus.QUEUED,
    val readBytes: Long = 0L,
    val writtenBytes: Long = 0L,
    val outputUri: String? = null,
    val outputName: String? = null,
    val errorCode: String? = null,
    val error: String? = null,
    val attempts: List<TaskAttempt> = emptyList(),
)

data class BatchState(
    val id: String = UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(),
    val tasks: List<FileTask> = emptyList(),
    val options: CodecOptions = CodecOptions(),
    val outputRoot: String? = null,
    val outputBatch: String? = null,
    val outputDirectories: Map<List<String>, String> = emptyMap(),
    val status: BatchStatus = BatchStatus.IDLE,
    val scanning: Boolean = false,
    val cancelling: Boolean = false,
    val warning: String? = null,
    val sourceTree: String? = null,
    val recursive: Boolean = false,
    val loaded: Boolean = false,
) {
    val busy: Boolean get() = scanning || status == BatchStatus.RUNNING
    val succeeded: Int get() = tasks.count { it.status == TaskStatus.SUCCEEDED }
    val failed: Int get() = tasks.count { it.status == TaskStatus.FAILED || it.status == TaskStatus.INTERRUPTED }
    val residualCount: Int get() = tasks.sumOf { t -> t.attempts.sumOf { it.residualUris.size } }
}

/** All names are single path components; SAF controls actual hierarchy. */
object StorageNames {
    fun safe(name: String): String = name.replace('/', '_').replace('\\', '_')
        .filter { it.code >= 32 && it.code != 127 }.let {
            if (it.isBlank() || it == "." || it == "..") "unnamed" else it
        }

    fun output(name: String, operation: Operation): String {
        val safe = safe(name)
        return if (operation == Operation.ENCODE) "$safe.b64"
        else if (safe.endsWith(".b64", ignoreCase = true)) safe.dropLast(4).ifEmpty { "decoded.bin" }
        else "$safe.decoded"
    }

    fun unique(requested: String, existing: Set<String>): String {
        val clean = safe(requested)
        if (clean !in existing) return clean
        val dot = clean.lastIndexOf('.').takeIf { it > 0 } ?: clean.length
        val stem = clean.substring(0, dot)
        val extension = clean.substring(dot)
        var i = 1
        while ("$stem ($i)$extension" in existing) i++
        return "$stem ($i)$extension"
    }
}
