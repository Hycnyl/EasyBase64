package com.hycnyl.easybase64.data

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hycnyl.easybase64.core.CodecOptions
import com.hycnyl.easybase64.core.Operation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64
import java.util.UUID

/** Exercises the actual Android resolver, URI hierarchy, descriptors, Room and codec together. */
@RunWith(AndroidJUnit4::class)
class BatchRepositoryIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver: ContentResolver get() = context.contentResolver
    private lateinit var repository: BatchRepository
    private lateinit var databaseName: String
    private lateinit var input: Uri
    private lateinit var output: Uri

    @Before fun prepare() = runBlocking {
        // Bootstrap the provider grant like the system document picker does. All repository IO
        // below runs with ordinary app permissions after this short shell identity is dropped.
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity("android.permission.MANAGE_DOCUMENTS")
        try {
            resolver.call(FixtureDocumentsProvider.AUTHORITY, "reset", context.packageName, null)
        } finally {
            automation.dropShellPermissionIdentity()
        }
        databaseName = "saf-test-${UUID.randomUUID()}.db"
        repository = BatchRepository(context, databaseName)
        repository.load()
        input = directory(ROOT, "input")
        output = directory(ROOT, "output")
        repository.setOutput(tree(output))
    }

    @After fun dispose() {
        runCatching { repository.close() }
        if (::databaseName.isInitialized) context.deleteDatabase(databaseName)
    }

    @Test fun binaryRoundTripKeepsDuplicateNamesAndSourcesIntact() = runBlocking {
        val first = document(directory(input, "one"), "重复.bin", ByteArray(65_539) { (it * 31).toByte() })
        val second = document(directory(input, "two"), "重复.bin", byteArrayOf(0, -1, 10, 13, 32))
        val original = listOf(first, second).associate { it.toString() to bytes(it) }
        repository.setFiles(listOf(first, second), persistable = false)
        repository.run(CodecOptions())
        val encoded = repository.state.value.tasks
        assertEquals(2, repository.state.value.succeeded)
        assertEquals(2, encoded.map { it.outputName }.toSet().size)
        encoded.forEach { task ->
            assertArrayEquals(Base64.getEncoder().encode(original.getValue(task.sourceUri)), bytes(Uri.parse(task.outputUri)))
            assertArrayEquals(original.getValue(task.sourceUri), bytes(Uri.parse(task.sourceUri)))
        }
        assertNoPartialFiles(Uri.parse(repository.state.value.outputBatch))
        repository.clear()
        repository.setFiles(encoded.map { Uri.parse(it.outputUri) }, persistable = false)
        repository.run(CodecOptions(operation = Operation.DECODE))
        assertEquals(2, repository.state.value.succeeded)
        repository.state.value.tasks.forEach { task ->
            val sourceTask = encoded.single { it.outputUri == task.sourceUri }
            assertArrayEquals(original.getValue(sourceTask.sourceUri), bytes(Uri.parse(task.outputUri)))
        }
    }

    @Test fun malformedTailIsRolledBackWhileLaterFilesFinishAndResultsPersist() = runBlocking {
        val bad = document(input, "bad.b64", "Zg==AAAA".toByteArray())
        val good = document(input, "good.B64", "Zm9v".toByteArray())
        val empty = document(input, ".b64", byteArrayOf())
        repository.setFiles(listOf(bad, good, empty), persistable = false)
        repository.run(CodecOptions(operation = Operation.DECODE))
        val state = repository.state.value
        assertEquals(2, state.succeeded)
        assertEquals(1, state.failed)
        assertEquals(TaskStatus.FAILED, state.tasks.first().status)
        assertTrue(state.tasks.first().attempts.last().residualUris.isEmpty())
        assertArrayEquals("Zg==AAAA".toByteArray(), bytes(bad))
        assertEquals("good", state.tasks[1].outputName)
        assertEquals("decoded.bin", state.tasks[2].outputName)
        assertArrayEquals("foo".toByteArray(), bytes(Uri.parse(state.tasks[1].outputUri)))
        assertNoPartialFiles(Uri.parse(state.outputBatch))
        repository.close()
        repository = BatchRepository(context, databaseName)
        repository.load()
        assertEquals(state.tasks.map { it.status }, repository.state.value.tasks.map { it.status })
        assertEquals(state.tasks.map { it.outputUri }, repository.state.value.tasks.map { it.outputUri })
    }

    @Test fun providerWithoutRenameUsesCopyCommitAndRemovesTemporaryDocument() = runBlocking {
        configure("noRename" to true)
        val source = document(input, "copy.bin", ByteArray(262_147) { (it xor 0x5a).toByte() })
        repository.setFiles(listOf(source), persistable = false)
        repository.run(CodecOptions())
        val task = repository.state.value.tasks.single()
        assertEquals(TaskStatus.SUCCEEDED, task.status)
        assertArrayEquals(Base64.getEncoder().encode(bytes(source)), bytes(Uri.parse(task.outputUri)))
        assertTrue(task.attempts.last().residualUris.isEmpty())
        assertNoPartialFiles(Uri.parse(repository.state.value.outputBatch))
    }

    @Test fun unknownSizeNonSeekablePipeIsReadToEof() = runBlocking {
        val source = document(input, "pipe-unknown.bin", ByteArray(131_075) { (it * 13).toByte() })
        repository.setFiles(listOf(source), persistable = false)
        assertNull(repository.state.value.tasks.single().size)
        repository.run(CodecOptions())
        val task = repository.state.value.tasks.single()
        assertEquals(TaskStatus.SUCCEEDED, task.status)
        assertEquals(131_075L, task.readBytes)
        assertArrayEquals(Base64.getEncoder().encode(bytes(source)), bytes(Uri.parse(task.outputUri)))
    }

    @Test fun recursiveScanExcludesOutputSubtreeAndPreservesRelativeDirectories() = runBlocking {
        val sub = directory(input, "子目录")
        document(input, "direct.bin", byteArrayOf(1, 2))
        document(sub, "nested.bin", byteArrayOf(3, 4))
        val nestedOutput = directory(input, "results")
        document(nestedOutput, "old-result.b64", "do not process".toByteArray())
        document(directory(nestedOutput, "old-batch"), "old.bin", byteArrayOf(9))
        repository.scanTree(tree(input), tree(nestedOutput), recursive = true)
        val state = repository.state.value
        assertFalse(state.scanning)
        assertEquals(setOf("direct.bin", "nested.bin"), state.tasks.map { it.name }.toSet())
        assertEquals(listOf("子目录"), state.tasks.single { it.name == "nested.bin" }.relativePath)
        repository.run(CodecOptions())
        assertEquals(2, repository.state.value.succeeded)
        val batch = Uri.parse(repository.state.value.outputBatch)
        val children = children(batch)
        assertTrue(children.any { it.name == "子目录" && it.directory })
        assertTrue(children.any { it.name == "direct.bin.b64" })
    }

    @Test fun hundredFileBatchIncludesCollisionEmptyAndBadInputs() = runBlocking {
        val duplicateOne = directory(input, "duplicates-a")
        val duplicateTwo = directory(input, "duplicates-b")
        val sources = (0 until 100).map { index ->
            val parent = when (index) { 50 -> duplicateOne; 51 -> duplicateTwo; else -> input }
            val name = if (index == 50 || index == 51) "duplicate.b64" else "item-$index.b64"
            val value = when (index) {
                0 -> byteArrayOf()
                25 -> "Zm9v=garbage".toByteArray()
                else -> Base64.getEncoder().encode("payload $index\u0000内容".toByteArray())
            }
            document(parent, name, value)
        }
        repository.setFiles(sources, persistable = false)
        repository.run(CodecOptions(operation = Operation.DECODE))
        val state = repository.state.value
        assertEquals(100, state.tasks.size)
        assertEquals(99, state.succeeded)
        assertEquals(1, state.failed)
        assertEquals(99, state.tasks.mapNotNull { it.outputUri }.toSet().size)
        assertEquals(99, state.tasks.mapNotNull { it.outputName }.toSet().size)
        state.tasks.filter { it.status == TaskStatus.SUCCEEDED }.forEach { task ->
            assertArrayEquals(Base64.getDecoder().decode(bytes(Uri.parse(task.sourceUri))), bytes(Uri.parse(task.outputUri)))
        }
        assertNoPartialFiles(Uri.parse(state.outputBatch))
        val completedUris = state.tasks.filter { it.status == TaskStatus.SUCCEEDED }.associate { it.id to it.outputUri }
        requireNotNull(resolver.openOutputStream(sources[25], "wt")).use { it.write("cmVwYWlyZWQ=".toByteArray()) }
        // A retry keeps the original DECODE snapshot even if the caller now passes ENCODE.
        repository.run(CodecOptions(operation = Operation.ENCODE), retryOnly = true)
        val retried = repository.state.value
        assertEquals(100, retried.succeeded)
        assertEquals(Operation.DECODE, retried.options.operation)
        completedUris.forEach { (id, uri) -> assertEquals(uri, retried.tasks.single { it.id == id }.outputUri) }
        val repaired = retried.tasks.single { it.sourceUri == sources[25].toString() }
        assertEquals(2, repaired.attempts.size)
        assertEquals(TaskStatus.FAILED, repaired.attempts.first().status)
        assertArrayEquals("repaired".toByteArray(), bytes(Uri.parse(repaired.outputUri)))
    }

    @Test fun cancellationCleansAttemptAndRetryPreservesHistory() = runBlocking {
        val first = document(input, "pipe-slow.bin", ByteArray(2 * 1024 * 1024) { it.toByte() })
        val second = document(input, "next.bin", byteArrayOf(5, 6, 7))
        repository.setFiles(listOf(first, second), persistable = false)
        configure("delayReadMillis" to 3L)
        val running = async(Dispatchers.IO) { repository.run(CodecOptions()) }
        withTimeout(60_000) {
            repository.state.first { it.tasks.any { task -> task.readBytes > 0 && task.status == TaskStatus.RUNNING } }
        }
        repository.cancel()
        withTimeout(60_000) { running.await() }
        assertEquals(BatchStatus.CANCELLED, repository.state.value.status)
        assertTrue(repository.state.value.tasks.none { it.status == TaskStatus.RUNNING || it.status == TaskStatus.COMMITTING })
        assertEquals(0, repository.state.value.residualCount)
        val firstAttempts = repository.state.value.tasks.first().attempts.size
        configure("delayReadMillis" to 0L)
        repository.run(CodecOptions(), retryOnly = true)
        assertEquals(2, repository.state.value.succeeded)
        assertEquals(firstAttempts + 1, repository.state.value.tasks.first().attempts.size)
        assertEquals(TaskStatus.CANCELLED, repository.state.value.tasks.first().attempts.first().status)
        assertNoPartialFiles(Uri.parse(repository.state.value.outputBatch))
    }

    @Test fun failedCleanupIsRecordedAndCanBeRetriedWithoutDeletingSuccesses() = runBlocking {
        val source = document(input, "bad.b64", "Zg==AAAA".toByteArray())
        configure("rejectDelete" to true)
        repository.setFiles(listOf(source), persistable = false)
        repository.run(CodecOptions(operation = Operation.DECODE))
        assertEquals(TaskStatus.FAILED, repository.state.value.tasks.single().status)
        assertTrue(repository.state.value.residualCount > 0)
        configure("rejectDelete" to false)
        repository.cleanupResiduals()
        assertEquals(0, repository.state.value.residualCount)
        assertArrayEquals("Zg==AAAA".toByteArray(), bytes(source))
        assertNoPartialFiles(Uri.parse(repository.state.value.outputBatch))
    }


    @Test fun abandonedCommitIsMarkedInterruptedWithoutRerunningOrDeletingSuccess() = runBlocking {
        val source = document(input, "pending.bin", byteArrayOf(10, 20, 30))
        val temporary = document(output, "pending.bin.attempt.partial", byteArrayOf(1))
        val unverifiedFinal = document(output, "pending.bin.b64", byteArrayOf(2))
        val successfulOutput = document(output, "previous.bin.b64", "cHJldmlvdXM=".toByteArray())
        val abandoned = BatchState(
            outputRoot = tree(output).toString(), status = BatchStatus.RUNNING,
            tasks = listOf(
                FileTask(sourceUri = source.toString(), name = "pending.bin", status = TaskStatus.COMMITTING,
                    attempts = listOf(TaskAttempt(status = TaskStatus.COMMITTING,
                        tempUri = temporary.toString(), finalUri = unverifiedFinal.toString(), phase = "COPY_FINAL"))),
                FileTask(sourceUri = source.toString(), name = "previous.bin", status = TaskStatus.SUCCEEDED,
                    outputUri = successfulOutput.toString(), outputName = "previous.bin.b64",
                    attempts = listOf(TaskAttempt(status = TaskStatus.SUCCEEDED,
                        finalUri = successfulOutput.toString(), phase = "COMPLETED")))
            )
        )
        repository.close()
        val database = Room.databaseBuilder(context, BatchDatabase::class.java, databaseName).build()
        database.snapshots().write(SnapshotEntity(json = SnapshotJson.encode(abandoned)))
        database.close()
        repository = BatchRepository(context, databaseName)
        repository.load()
        assertEquals(BatchStatus.INTERRUPTED, repository.state.value.status)
        assertEquals(TaskStatus.INTERRUPTED, repository.state.value.tasks.first().status)
        assertEquals(TaskStatus.SUCCEEDED, repository.state.value.tasks.last().status)
        assertEquals(2, repository.state.value.residualCount)
        assertArrayEquals("cHJldmlvdXM=".toByteArray(), bytes(successfulOutput))
        repository.cleanupResiduals()
        assertEquals(0, repository.state.value.residualCount)
        assertArrayEquals("cHJldmlvdXM=".toByteArray(), bytes(successfulOutput))
        assertArrayEquals(byteArrayOf(10, 20, 30), bytes(source))
    }


    @Test fun providerWriteFailureNeverCommitsPartialOutput() = runBlocking {
        val source = document(input, "write-failure.bin", ByteArray(1024 * 1024) { (it * 7).toByte() })
        repository.setFiles(listOf(source), persistable = false)
        configure("failWrites" to true)
        repository.run(CodecOptions())
        val task = repository.state.value.tasks.single()
        assertEquals(TaskStatus.FAILED, task.status)
        assertEquals("WRITE_FAILED", task.errorCode)
        assertNull(task.outputUri)
        assertEquals(0, repository.state.value.residualCount)
        assertEquals(1024 * 1024, bytes(source).size)
        assertNoPartialFiles(Uri.parse(repository.state.value.outputBatch))
    }


    @Test fun permissionWithdrawalIsReportedAndTemporaryOutputIsRemoved() = runBlocking {
        val source = document(input, "permission.bin", byteArrayOf(1, 2, 3))
        repository.setFiles(listOf(source), persistable = false)
        configure("denyReads" to true)
        repository.run(CodecOptions())
        val task = repository.state.value.tasks.single()
        assertEquals(TaskStatus.FAILED, task.status)
        assertEquals("PERMISSION_LOST", task.errorCode)
        assertNull(task.outputUri)
        assertEquals(0, repository.state.value.residualCount)
        assertNoPartialFiles(Uri.parse(repository.state.value.outputBatch))
        configure("denyReads" to false)
        repository.run(CodecOptions(), retryOnly = true)
        assertEquals(1, repository.state.value.succeeded)
    }


    @Test fun multiMegabyteQueuePersistsInBoundedRoomRowsAndRetainsAttempts() = runBlocking {
        val longName = "metadata-name-" + "x".repeat(384)
        val explanation = "provider detail " + "e".repeat(512)
        val snapshot = BatchState(status = BatchStatus.PARTIAL, outputRoot = tree(output).toString(),
            tasks = (0 until 4000).map { index ->
                FileTask(sourceUri = "content://test.documents/document/$index", name = "$longName-$index",
                    status = TaskStatus.FAILED, errorCode = "SOURCE_UNAVAILABLE", error = explanation,
                    attempts = listOf(TaskAttempt(id = "attempt-$index", status = TaskStatus.FAILED,
                        phase = "WRITING", errorCode = "SOURCE_UNAVAILABLE", error = explanation)))
            })
        val json = SnapshotJson.encode(snapshot)
        assertTrue("Fixture must exceed the typical 2 MiB CursorWindow", json.length > 2 * 1024 * 1024)
        repository.close()
        val writer = Room.databaseBuilder(context, BatchDatabase::class.java, databaseName).build()
        writer.snapshots().write(SnapshotEntity(json = json))
        writer.close()
        val reader = Room.databaseBuilder(context, BatchDatabase::class.java, databaseName).build()
        val restored = SnapshotJson.decode(requireNotNull(reader.snapshots().read()).json)
        reader.close()
        assertEquals(4000, restored.tasks.size)
        assertEquals(snapshot.id, restored.id)
        assertEquals(snapshot.tasks.first(), restored.tasks.first())
        assertEquals(snapshot.tasks.last(), restored.tasks.last())
        assertEquals(4000, restored.tasks.sumOf { it.attempts.size })
        repository = BatchRepository(context, databaseName)
        repository.load()
        assertEquals(4000, repository.state.value.tasks.size)
    }


    @Test fun cancellationBeforeExecutionStartsCreatesNoOutputOrAttempt() = runBlocking {
        val source = document(input, "pending.bin", byteArrayOf(1, 2, 3))
        repository.setFiles(listOf(source), persistable = false)
        repository.run(CodecOptions(), startCancelled = { true })
        val state = repository.state.value
        assertEquals(BatchStatus.READY, state.status)
        assertEquals(TaskStatus.QUEUED, state.tasks.single().status)
        assertTrue(state.tasks.single().attempts.isEmpty())
        assertNull(state.outputBatch)
        assertTrue(children(output).isEmpty())
    }

    @Test fun successfulRenameTransfersOwnershipWithoutProbingRevokedOldUri() = runBlocking {
        val original = byteArrayOf(0, 1, 2, -1, 13, 10)
        val source = document(input, "rename.bin", original)
        configure("revokeRenamedUris" to true)
        repository.setFiles(listOf(source), persistable = false)
        repository.run(CodecOptions())
        val encoded = repository.state.value.tasks.single()
        assertEquals(TaskStatus.SUCCEEDED, encoded.status)
        assertEquals(0, repository.state.value.residualCount)
        assertNull(encoded.attempts.last().tempUri)
        val encodedUri = Uri.parse(encoded.outputUri)
        assertArrayEquals(Base64.getEncoder().encode(original), bytes(encodedUri))

        // No explicit clear: a completed rename must not create a phantom residual that blocks
        // the next selection, as happened with Android's real ExternalStorageProvider.
        repository.setFiles(listOf(encodedUri), persistable = false)
        assertEquals(BatchStatus.READY, repository.state.value.status)
        assertEquals(encoded.outputUri, repository.state.value.tasks.single().sourceUri)
        repository.run(CodecOptions(operation = Operation.DECODE))
        val decoded = repository.state.value.tasks.single()
        assertEquals(TaskStatus.SUCCEEDED, decoded.status)
        assertEquals("rename.bin", decoded.outputName)
        assertEquals(0, repository.state.value.residualCount)
        assertArrayEquals(original, bytes(Uri.parse(decoded.outputUri)))
        assertArrayEquals(original, bytes(source))
        assertEquals(0, resolver.call(FixtureDocumentsProvider.AUTHORITY, "stats", null, null)?.getInt("obsoleteUriAccesses"))
    }

    @Test fun renamedCommitRecoveryTracksOnlyTheReturnedUri() = runBlocking {
        val source = document(input, "source.bin", byteArrayOf(4, 5, 6))
        val temporary = document(output, "renamed.partial", byteArrayOf(7, 8, 9))
        configure("revokeRenamedUris" to true)
        val renamed = requireNotNull(DocumentsContract.renameDocument(resolver, temporary, "renamed.b64"))
        val interrupted = BatchState(outputRoot = tree(output).toString(), status = BatchStatus.RUNNING,
            tasks = listOf(FileTask(sourceUri = source.toString(), name = "source.bin", status = TaskStatus.COMMITTING,
                attempts = listOf(TaskAttempt(status = TaskStatus.COMMITTING, tempUri = temporary.toString(),
                    finalUri = renamed.toString(), phase = "RENAMED")))))
        repository.close()
        val database = Room.databaseBuilder(context, BatchDatabase::class.java, databaseName).build()
        database.snapshots().write(SnapshotEntity(json = SnapshotJson.encode(interrupted)))
        database.close()
        repository = BatchRepository(context, databaseName)
        repository.load()
        val attempt = repository.state.value.tasks.single().attempts.single()
        assertEquals(TaskStatus.INTERRUPTED, attempt.status)
        assertNull(attempt.tempUri)
        assertEquals(listOf(renamed.toString()), attempt.residualUris)
        repository.cleanupResiduals()
        assertEquals(0, repository.state.value.residualCount)
        assertArrayEquals(byteArrayOf(4, 5, 6), bytes(source))
        assertEquals(0, resolver.call(FixtureDocumentsProvider.AUTHORITY, "stats", null, null)?.getInt("obsoleteUriAccesses"))
    }

    private fun configure(vararg values: Pair<String, Any>) {
        resolver.call(FixtureDocumentsProvider.AUTHORITY, "configure", null, Bundle().apply {
            values.forEach { (key, value) -> when (value) {
                is Boolean -> putBoolean(key, value)
                is Long -> putLong(key, value)
            } }
        })
    }

    private fun directory(parent: Uri, name: String): Uri = requireNotNull(
        DocumentsContract.createDocument(resolver, parent, Document.MIME_TYPE_DIR, name))

    private fun document(parent: Uri, name: String, contents: ByteArray): Uri {
        val uri = requireNotNull(DocumentsContract.createDocument(resolver, parent, "application/octet-stream", name))
        requireNotNull(resolver.openOutputStream(uri, "wt")).use { it.write(contents) }
        return uri
    }

    private fun bytes(uri: Uri): ByteArray = requireNotNull(resolver.openInputStream(uri)).use { it.readBytes() }

    private fun tree(document: Uri): Uri {
        val uri = DocumentsContract.buildTreeDocumentUri(
            FixtureDocumentsProvider.AUTHORITY, DocumentsContract.getDocumentId(document))
        resolver.call(FixtureDocumentsProvider.AUTHORITY, "grant", context.packageName,
            Bundle().apply { putString("uri", uri.toString()) })
        return uri
    }

    private data class Child(val uri: Uri, val name: String, val directory: Boolean)
    private fun children(parent: Uri): List<Child> {
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
        val result = mutableListOf<Child>()
        requireNotNull(resolver.query(childUri, arrayOf(Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE), null, null, null)).use { cursor ->
            while (cursor.moveToNext()) result += Child(
                DocumentsContract.buildDocumentUriUsingTree(parent, cursor.getString(0)), cursor.getString(1),
                cursor.getString(2) == Document.MIME_TYPE_DIR)
        }
        return result
    }

    private fun assertNoPartialFiles(parent: Uri) {
        children(parent).forEach { child ->
            assertFalse("Leaked temporary document: ${child.name}", child.name.endsWith(".partial"))
            if (child.directory) assertNoPartialFiles(child.uri)
        }
    }

    companion object {
        private val ROOT_TREE = DocumentsContract.buildTreeDocumentUri(FixtureDocumentsProvider.AUTHORITY, "root")
        private val ROOT = DocumentsContract.buildDocumentUriUsingTree(ROOT_TREE, "root")
    }
}
