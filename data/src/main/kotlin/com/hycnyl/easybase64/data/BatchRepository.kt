package com.hycnyl.easybase64.data

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import androidx.room.Room
import com.hycnyl.easybase64.core.Base64Codec
import com.hycnyl.easybase64.core.CodecException
import com.hycnyl.easybase64.core.CodecOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Single owner for queue execution. UI and Service share one application-scoped instance. */
class BatchRepository(context: Context, databaseName: String = "easybase64.db") : Closeable {
    private val resolver = context.applicationContext.contentResolver
    private val database = Room.databaseBuilder(context.applicationContext, BatchDatabase::class.java, databaseName).build()
    private val dao = database.snapshots()
    private val saf = SafStore(resolver)
    private val mutableState = MutableStateFlow(BatchState())
    val state: StateFlow<BatchState> = mutableState.asStateFlow()
    private val loadMutex = Mutex()
    private val operationMutex = Mutex()
    private var loaded = false
    private val cancelled = AtomicBoolean(false)
    private val interrupted = AtomicBoolean(false)
    private val activeStreams = CopyOnWriteArrayList<Closeable>()
    private val activeQuery = AtomicReference<CancellationSignal?>()
    private val closer = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "EasyBase64-cancel").apply { isDaemon = true } }

    suspend fun load() = withContext(Dispatchers.IO) {
        loadMutex.withLock {
            if (loaded) return@withLock
            val stored = dao.read()?.let { SnapshotJson.decode(it.json) }
            if (stored == null) {
                mutableState.value = BatchState(loaded = true)
            } else {
                val wasActive = stored.status == BatchStatus.RUNNING
                val recovered = stored.tasks.map { task ->
                    if (task.status == TaskStatus.RUNNING || task.status == TaskStatus.COMMITTING || (wasActive && task.status == TaskStatus.QUEUED)) {
                        task.copy(
                            status = TaskStatus.INTERRUPTED,
                            errorCode = "INTERRUPTED",
                            error = "The previous attempt did not finish. Review residual files and retry manually.",
                            attempts = task.attempts.map { attempt ->
                                if (attempt.status == TaskStatus.RUNNING || attempt.status == TaskStatus.COMMITTING) {
                                    val ownershipTransferred = attempt.finalUri != null && attempt.phase == "RENAMED"
                                    val owned = if (ownershipTransferred) listOfNotNull(attempt.finalUri) else listOfNotNull(attempt.tempUri, attempt.finalUri)
                                    val knownResiduals = if (ownershipTransferred) attempt.residualUris.filterNot { it == attempt.tempUri } else attempt.residualUris
                                    val candidates = (knownResiduals + owned).distinct()
                                    val residual = candidates.filter { uri -> try { saf.exists(Uri.parse(uri)) } catch (_: Exception) { true } }
                                    attempt.copy(
                                        tempUri = if (ownershipTransferred) null else attempt.tempUri,
                                        status = TaskStatus.INTERRUPTED, endedAt = System.currentTimeMillis(), residualUris = residual,
                                        errorCode = "INTERRUPTED",
                                        error = if (attempt.phase == "RENAMING") "Rename was interrupted. Review ${attempt.plannedName} in ${attempt.parentUri}; SAF cannot guarantee atomic rename."
                                            else "Attempt interrupted; completion was not verified.",
                                    )
                                } else attempt
                            },
                        )
                    } else task
                }
                persist(stored.copy(
                    tasks = if (stored.scanning) emptyList() else recovered,
                    status = if (wasActive) BatchStatus.INTERRUPTED else if (stored.scanning) BatchStatus.IDLE else stored.status,
                    scanning = false, cancelling = false, loaded = true,
                    warning = when { wasActive -> "INTERRUPTED"; stored.scanning -> "SCAN_INTERRUPTED"; else -> stored.warning },
                ))
            }
            loaded = true
        }
    }

    /** Caller takes persistable permissions using the flags actually returned by the system picker. */
    suspend fun setFiles(uris: List<Uri>, persistable: Boolean = true) = withContext(Dispatchers.IO) {
        load()
        operationMutex.withLock {
            if (state.value.busy || uris.isEmpty()) return@withLock
            val old = state.value
            if (old.status != BatchStatus.READY && hasUnresolvedCleanup(old)) {
                persist(old.copy(warning = "CLEANUP_FAILED")); return@withLock
            }
            var next = if (old.status == BatchStatus.READY && old.sourceTree == null) old else BatchState(outputRoot = old.outputRoot, loaded = true)
            val tasks = next.tasks.toMutableList()
            var warning: String? = null
            for (uri in uris.distinct()) {
                if (tasks.any { it.sourceUri == uri.toString() }) continue
                try {
                    val info = saf.info(uri)
                    if (!info.directory) tasks += FileTask(sourceUri = uri.toString(), name = info.name, size = info.size)
                    if (persistable && !hasPersisted(uri, write = false)) warning = "PERMISSION_NOT_PERSISTED"
                } catch (e: Exception) { warning = errorCode(e, "SOURCE_UNAVAILABLE") }
            }
            next = next.copy(tasks = tasks, status = if (tasks.isEmpty()) BatchStatus.IDLE else BatchStatus.READY, warning = warning)
            persist(next)
        }
    }

    suspend fun setOutput(uri: Uri) = withContext(Dispatchers.IO) {
        load()
        operationMutex.withLock {
            if (state.value.busy) return@withLock
            val current = state.value
            if (current.status !in setOf(BatchStatus.IDLE, BatchStatus.READY) && hasUnresolvedCleanup(current)) {
                persist(current.copy(warning = "CLEANUP_FAILED")); return@withLock
            }
            if (current.outputRoot == uri.toString()) return@withLock
            val base = if (current.status !in setOf(BatchStatus.IDLE, BatchStatus.READY)) BatchState(loaded = true) else current
            val requiresScan = base.sourceTree != null
            persist(base.copy(
                outputRoot = uri.toString(), outputBatch = null, outputDirectories = emptyMap(),
                tasks = if (requiresScan) emptyList() else base.tasks,
                status = if (requiresScan || base.tasks.isEmpty()) BatchStatus.IDLE else BatchStatus.READY,
                warning = if (requiresScan) "RESCAN_REQUIRED" else if (!hasPersisted(uri, write = true)) "PERMISSION_NOT_PERSISTED" else null,
            ))
        }
    }

    suspend fun removeTask(id: String) = withContext(Dispatchers.IO) {
        load()
        operationMutex.withLock {
            val s = state.value
            if (s.busy || s.status !in setOf(BatchStatus.IDLE, BatchStatus.READY)) return@withLock
            val tasks = s.tasks.filterNot { it.id == id }
            persist(s.copy(tasks = tasks, status = if (tasks.isEmpty()) BatchStatus.IDLE else BatchStatus.READY))
        }
    }

    /** Clears metadata only. Source documents and completed outputs are never deleted here. */
    suspend fun clear() = withContext(Dispatchers.IO) {
        load()
        operationMutex.withLock {
            if (!state.value.busy) persist(BatchState(outputRoot = state.value.outputRoot, loaded = true))
        }
    }

    suspend fun scanTree(inputTree: Uri, outputTree: Uri, recursive: Boolean) = withContext(Dispatchers.IO) {
        load()
        if (!operationMutex.tryLock()) return@withContext
        try {
            if (state.value.busy) return@withContext
            if (hasUnresolvedCleanup(state.value)) {
                persist(state.value.copy(warning = "CLEANUP_FAILED")); return@withContext
            }
            cancelled.set(false)
            interrupted.set(false)
            persist(BatchState(outputRoot = outputTree.toString(), sourceTree = inputTree.toString(), recursive = recursive, scanning = true, loaded = true))
            val tasks = ArrayList<FileTask>()
            try {
                val excluded = saf.excludedOutputId(inputTree, outputTree)
                val root = saf.treeDocument(inputTree)
                if (!saf.info(root).directory) throw StorageException("SOURCE_UNAVAILABLE", "The input is not a directory.")
                val pending = ArrayDeque<Pair<Uri, List<String>>>()
                val visited = HashSet<String>()
                pending.add(root to emptyList())
                var lastUpdate = 0L
                while (pending.isNotEmpty()) {
                    checkCancellation()
                    val (directory, path) = pending.removeFirst()
                    val directoryId = DocumentsContract.getDocumentId(directory)
                    if (!visited.add(directoryId)) continue
                    val signal = CancellationSignal()
                    activeQuery.set(signal)
                    try {
                        checkCancellation()
                        saf.children(directory, signal) { info ->
                            checkCancellation()
                            if (info.id != excluded) {
                                if (info.directory) {
                                    if (recursive) pending.add(info.uri to (path + info.name))
                                } else {
                                    tasks += FileTask(sourceUri = info.uri.toString(), name = info.name, relativePath = path, size = info.size)
                                    val now = System.currentTimeMillis()
                                    if (now - lastUpdate >= 200L) {
                                        mutableState.value = state.value.copy(tasks = tasks.toList())
                                        lastUpdate = now
                                    }
                                }
                            }
                        }
                    } finally { activeQuery.compareAndSet(signal, null) }
                }
                checkCancellation()
                persist(state.value.copy(
                    tasks = tasks.toList(), scanning = false, cancelling = false,
                    status = if (tasks.isEmpty()) BatchStatus.IDLE else BatchStatus.READY,
                    warning = when {
                        tasks.isEmpty() -> "EMPTY_FOLDER"
                        !hasPersisted(inputTree, false) || !hasPersisted(outputTree, true) -> "PERMISSION_NOT_PERSISTED"
                        else -> null
                    },
                ))
            } catch (e: Exception) {
                val wasCancelled = cancelled.get() || e is CancellationException
                persist(state.value.copy(tasks = emptyList(), scanning = false, cancelling = false, status = BatchStatus.IDLE,
                    warning = if (wasCancelled) "SCAN_CANCELLED" else errorCode(e, "SCAN_FAILED")))
            }
        } finally { activeQuery.set(null); operationMutex.unlock() }
    }

    /** Execute from a user-started foreground service. Re-entry is ignored. */
    suspend fun run(options: CodecOptions, retryOnly: Boolean = false, startCancelled: () -> Boolean = { false }) = withContext(Dispatchers.IO) {
        load()
        if (!operationMutex.tryLock()) return@withContext
        try {
            val before = state.value
            if (before.busy || before.tasks.isEmpty() || before.outputRoot == null) return@withContext
            if (!retryOnly && before.status != BatchStatus.READY) return@withContext
            val eligible = if (retryOnly) before.tasks.filter { it.status in setOf(TaskStatus.FAILED, TaskStatus.INTERRUPTED, TaskStatus.CANCELLED) }
                else before.tasks.filter { it.status == TaskStatus.QUEUED }
            if (eligible.isEmpty()) return@withContext
            val ids = eligible.map { it.id }.toSet()
            val frozen = if (retryOnly) before.options else options
            cancelled.set(false)
            interrupted.set(false)
            if (startCancelled()) return@withContext
            persist(before.copy(options = frozen, status = BatchStatus.RUNNING, cancelling = false, warning = null,
                tasks = before.tasks.map { if (it.id in ids) it.copy(status = TaskStatus.QUEUED) else it }))
            try {
                var batch = state.value.outputBatch?.let(Uri::parse)
                if (batch == null) {
                    checkCancellation()
                    val root = saf.treeDocument(Uri.parse(before.outputRoot))
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
                    batch = saf.createUnique(root, "EasyBase64-$stamp-${before.id.take(8)}", DocumentsContract.Document.MIME_TYPE_DIR).uri
                    persist(state.value.copy(outputBatch = batch.toString()))
                } else if (!saf.info(batch).directory) {
                    throw StorageException("WRITE_FAILED", "Batch output folder is unavailable; select a new output folder for a new batch.")
                }
                val directories = state.value.outputDirectories.mapValues { Uri.parse(it.value) }.toMutableMap()
                directories[emptyList()] = batch
                for (taskId in eligible.map { it.id }) {
                    if (cancelled.get()) break
                    process(taskId, frozen, batch, directories)
                }
            } catch (e: Exception) {
                val wasCancelled = cancelled.get() || e is CancellationException
                persist(state.value.copy(tasks = state.value.tasks.map { task ->
                    if (task.id in ids && task.status == TaskStatus.QUEUED) task.copy(
                        status = if (interrupted.get()) TaskStatus.INTERRUPTED else if (wasCancelled) TaskStatus.CANCELLED else TaskStatus.FAILED,
                        errorCode = if (interrupted.get()) "INTERRUPTED" else if (wasCancelled) "CANCELLED" else errorCode(e, "WRITE_FAILED"), error = safeMessage(e),
                    ) else task
                }))
            } finally {
                // Also runs if the service coroutine is cancelled, so no false success leaks through.
                withContext(NonCancellable + Dispatchers.IO) {
                    val tasks = state.value.tasks.map { task ->
                        if (task.id in ids && task.status == TaskStatus.QUEUED) task.copy(status = if (interrupted.get()) TaskStatus.INTERRUPTED else TaskStatus.CANCELLED, errorCode = if (interrupted.get()) "INTERRUPTED" else "CANCELLED") else task
                    }
                    val summary = when {
                        tasks.all { it.status == TaskStatus.SUCCEEDED } -> BatchStatus.SUCCEEDED
                        interrupted.get() || tasks.any { it.status == TaskStatus.INTERRUPTED } -> BatchStatus.INTERRUPTED
                        cancelled.get() || tasks.any { it.status == TaskStatus.CANCELLED } -> BatchStatus.CANCELLED
                        else -> BatchStatus.PARTIAL
                    }
                    persist(state.value.copy(tasks = tasks, status = summary, cancelling = false,
                        warning = if (tasks.any { t -> t.attempts.any { it.residualUris.isNotEmpty() } }) "CLEANUP_FAILED" else state.value.warning))
                }
            }
        } finally { operationMutex.unlock() }
    }

    /** System stop/timeout: the next launch offers a manual retry, never automatic work. */
    fun interrupt() { interrupted.set(true); cancel() }

    fun cancel() {
        cancelled.set(true)
        if (state.value.busy) mutableState.value = state.value.copy(cancelling = true)
        activeQuery.get()?.cancel()
        // Providers can block in read/close. Do not block the main thread or promise immediate cancellation.
        val streams = activeStreams.toList()
        closer.execute { streams.forEach { try { it.close() } catch (_: Exception) { } } }
    }

    suspend fun cleanupResiduals() = withContext(Dispatchers.IO) {
        load()
        operationMutex.withLock {
            if (state.value.busy) return@withLock
            val tasks = state.value.tasks.map { task ->
                task.copy(attempts = task.attempts.map { attempt ->
                    val remaining = attempt.residualUris.filterNot { deleteOwned(Uri.parse(it)) }
                    attempt.copy(residualUris = remaining)
                })
            }
            persist(state.value.copy(tasks = tasks, warning = if (tasks.any { t -> t.attempts.any { it.residualUris.isNotEmpty() } }) "CLEANUP_FAILED" else null))
        }
    }

    private fun process(taskId: String, options: CodecOptions, batch: Uri, directories: MutableMap<List<String>, Uri>) {
        val task = state.value.tasks.first { it.id == taskId }
        var attempt = TaskAttempt()
        var temp: Uri? = null
        var final: Uri? = null
        var succeeded = false
        var lastProgress = 0L
        var read = 0L
        var written = 0L
        fun journal(transform: (TaskAttempt) -> TaskAttempt) {
            attempt = transform(attempt)
            updateTask(taskId) { current -> current.copy(attempts = current.attempts.dropLast(1) + attempt) }
        }
        updateTask(taskId) { it.copy(status = TaskStatus.RUNNING, readBytes = 0, writtenBytes = 0,
            outputUri = null, outputName = null, errorCode = null, error = null, attempts = it.attempts + attempt) }
        try {
            checkCancellation()
            val parent = outputDirectory(batch, task.relativePath, directories)
            val tempInfo = saf.createUnique(parent, ".easybase64-${attempt.id}.partial", "application/octet-stream")
            temp = tempInfo.uri
            journal { it.copy(tempUri = temp.toString(), parentUri = parent.toString(), phase = "WRITING") }
            checkCancellation()
            val input = track(sourceStream(Uri.parse(task.sourceUri)))
            try {
                input.use { source ->
                    checkCancellation()
                    val output = track(outputStream(tempInfo.uri))
                    try {
                        output.use { target ->
                            Base64Codec.transform(source, target, options, cancelled = { cancelled.get() }) { bytesRead, bytesWritten ->
                                read = bytesRead; written = bytesWritten
                                val now = System.currentTimeMillis()
                                if (now - lastProgress >= 200L) {
                                    updateTask(taskId, disk = false) { it.copy(readBytes = read, writtenBytes = written) }
                                    lastProgress = now
                                }
                            }
                            checkCancellation()
                            target.flush()
                        }
                    } finally { activeStreams.remove(output) }
                }
            } finally { activeStreams.remove(input) }
            checkCancellation()
            updateTask(taskId) { it.copy(status = TaskStatus.COMMITTING, readBytes = read, writtenBytes = written) }
            journal { it.copy(status = TaskStatus.COMMITTING, phase = "VALIDATED") }
            val wantedName = StorageNames.output(task.name, options.operation)
            val uniqueName = saf.finalName(parent, wantedName)
            if (tempInfo.flags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME.toLong() != 0L) {
                journal { it.copy(phase = "RENAMING", plannedName = uniqueName) }
                checkCancellation()
                final = try {
                    saf.rename(tempInfo.uri, uniqueName)
                        ?: throw StorageException("WRITE_FAILED", "The provider did not confirm the renamed document URI")
                } catch (e: UnsupportedOperationException) { null }
                if (final != null) {
                    // A successful rename transfers ownership to the returned URI. The provider may
                    // revoke the obsolete URI immediately; probing it is not a cleanup operation.
                    temp = null
                    journal { it.copy(tempUri = null, finalUri = final.toString(), phase = "RENAMED") }
                }
            }
            if (final == null) {
                checkCancellation()
                val created = saf.createUnique(parent, wantedName, "application/octet-stream")
                final = created.uri
                journal { it.copy(finalUri = final.toString(), plannedName = created.name, phase = "COPYING") }
                copyForCommit(tempInfo.uri, created.uri)
                checkCancellation()
                journal { it.copy(phase = "COPIED") }
            }
            // This single executor defines the commit point after physical EOF and all stream closes.
            checkCancellation()
            val committed = final ?: throw StorageException("WRITE_FAILED", "No committed output URI")
            val actualName = try { saf.info(committed).name } catch (_: Exception) { uniqueName }
            val remainingTemp = temp
            val residual = if (remainingTemp != null && !saf.sameDocument(remainingTemp, committed) && !deleteOwned(remainingTemp)) listOf(remainingTemp.toString()) else emptyList()
            attempt = attempt.copy(status = TaskStatus.SUCCEEDED, finalUri = committed.toString(), phase = "COMMITTED",
                endedAt = System.currentTimeMillis(), residualUris = residual,
                errorCode = if (residual.isNotEmpty()) "CLEANUP_FAILED" else null,
                error = if (residual.isNotEmpty()) "The converted file is complete, but the temporary file could not be removed." else null)
            updateTask(taskId) { it.copy(status = TaskStatus.SUCCEEDED, readBytes = read, writtenBytes = written,
                outputUri = committed.toString(), outputName = actualName, attempts = it.attempts.dropLast(1) + attempt) }
            succeeded = true
        } catch (e: Exception) {
            if (succeeded) throw e
            val wasCancelled = cancelled.get() || e is CancellationException
            val residue = listOfNotNull(final, temp).distinct().filterNot { deleteOwned(it) }.map { it.toString() }
            val code = if (interrupted.get()) "INTERRUPTED" else if (wasCancelled) "CANCELLED" else errorCode(e, "WRITE_FAILED")
            val status = if (interrupted.get()) TaskStatus.INTERRUPTED else if (wasCancelled) TaskStatus.CANCELLED else TaskStatus.FAILED
            val detail = if (attempt.phase == "RENAMING" && final == null) {
                "${safeMessage(e)}. Rename could not be verified; review ${attempt.plannedName} in ${attempt.parentUri}."
            } else safeMessage(e)
            attempt = attempt.copy(status = status, endedAt = System.currentTimeMillis(), residualUris = residue, errorCode = code, error = detail)
            updateTask(taskId) { it.copy(status = status, readBytes = read, writtenBytes = written, errorCode = code,
                error = detail, attempts = it.attempts.dropLast(1) + attempt) }
        }
    }

    private fun outputDirectory(batch: Uri, path: List<String>, cache: MutableMap<List<String>, Uri>): Uri {
        var parent = batch
        path.forEachIndexed { index, segment ->
            checkCancellation()
            val key = path.take(index + 1)
            parent = cache[key] ?: saf.createUnique(parent, StorageNames.safe(segment), DocumentsContract.Document.MIME_TYPE_DIR).uri.also { created ->
                // Persist owned directory identities so retry preserves the original relative hierarchy.
                persist(state.value.copy(outputDirectories = state.value.outputDirectories + (key to created.toString())))
                cache[key] = created
            }
        }
        return parent
    }

    private fun copyForCommit(source: Uri, target: Uri) {
        val input = track(sourceStream(source))
        try {
            input.use { from ->
                val output = track(outputStream(target))
                try {
                    output.use { to ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            checkCancellation()
                            val count = from.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            to.write(buffer, 0, count)
                        }
                        to.flush()
                    }
                } finally { activeStreams.remove(output) }
            }
        } finally { activeStreams.remove(input) }
    }

    private fun sourceStream(uri: Uri): InputStream {
        val source = try { resolver.openInputStream(uri) ?: throw IOException("Provider returned no input stream") }
            catch (e: SecurityException) { throw e }
            catch (e: IOException) { throw StorageException("SOURCE_UNAVAILABLE", "Cannot open source document", e) }
        return object : FilterInputStream(source) {
            override fun read(): Int = sourceCall { `in`.read() }
            override fun read(b: ByteArray, off: Int, len: Int): Int = sourceCall { `in`.read(b, off, len) }
            override fun close() = sourceCall { `in`.close() }
            private fun <T> sourceCall(action: () -> T): T = try { action() } catch (e: IOException) {
                throw StorageException("SOURCE_UNAVAILABLE", "Source read or close failed", e)
            }
        }
    }

    private fun outputStream(uri: Uri): OutputStream {
        val target = resolver.openOutputStream(uri, "w") ?: throw StorageException("WRITE_FAILED", "Provider returned no output stream")
        return object : FilterOutputStream(target) {
            override fun write(b: Int) = outputCall { out.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) = outputCall { out.write(b, off, len) }
            override fun flush() = outputCall { out.flush() }
            override fun close() = outputCall { out.close() }
            private fun <T> outputCall(action: () -> T): T = try { action() } catch (e: IOException) {
                throw StorageException("WRITE_FAILED", "Output write, flush or close failed", e)
            }
        }
    }

    private fun <T : Closeable> track(stream: T): T {
        activeStreams.add(stream)
        if (cancelled.get()) { try { stream.close() } finally { activeStreams.remove(stream) }; checkCancellation() }
        return stream
    }

    private fun deleteOwned(uri: Uri): Boolean = try {
        if (!saf.exists(uri)) true else saf.delete(uri)
    } catch (_: Exception) { false }

    private fun hasUnresolvedCleanup(s: BatchState): Boolean = s.residualCount > 0 || s.tasks.any { task ->
        task.attempts.any { it.phase == "RENAMING" && it.finalUri == null && it.status != TaskStatus.SUCCEEDED }
    }

    private fun checkCancellation() { if (cancelled.get()) throw CancellationException("Cancelled by user") }

    private fun persist(next: BatchState) {
        dao.write(SnapshotEntity(json = SnapshotJson.encode(next)))
        mutableState.value = next
    }

    private fun updateTask(id: String, disk: Boolean = true, transform: (FileTask) -> FileTask) {
        val next = state.value.copy(tasks = state.value.tasks.map { if (it.id == id) transform(it) else it }, cancelling = cancelled.get())
        if (disk) persist(next) else mutableState.value = next
    }

    private fun hasPersisted(uri: Uri, write: Boolean): Boolean = resolver.persistedUriPermissions.any { permission ->
        val permitted = if (write) permission.isWritePermission else permission.isReadPermission
        permitted && (permission.uri == uri || (permission.uri.authority == uri.authority &&
            DocumentsContract.isTreeUri(permission.uri) && DocumentsContract.isTreeUri(uri) &&
            DocumentsContract.getTreeDocumentId(permission.uri) == DocumentsContract.getTreeDocumentId(uri)))
    }

    private fun errorCode(error: Exception, fallback: String): String = when (error) {
        is CodecException -> error.code
        is StorageException -> error.code
        is SecurityException -> "PERMISSION_LOST"
        else -> fallback
    }

    private fun safeMessage(error: Exception): String = (error.message ?: error.javaClass.simpleName).take(500)

    override fun close() { cancel(); closer.shutdown(); database.close() }
}
