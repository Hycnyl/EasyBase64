package com.hycnyl.easybase64.data

import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicInteger

/** Real resolver/descriptor IO, with controllable failures; never shipped in the application APK. */
class FixtureDocumentsProvider : DocumentsProvider() {
    private val root get() = File(requireNotNull(context).filesDir, "saf-test-fixtures")
    @Volatile private var noRename = false
    @Volatile private var rejectDelete = false
    @Volatile private var revokeRenamedUris = false
    private val revokedDocumentIds = java.util.Collections.synchronizedSet(HashSet<String>())
    private val obsoleteUriAccesses = AtomicInteger()
    @Volatile private var failWrites = false
    @Volatile private var denyReads = false
    @Volatile private var delayReadMillis = 0L
    private val opened = AtomicInteger()

    override fun onCreate(): Boolean = root.mkdirs() || root.isDirectory

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: ROOT_COLUMNS)
        val row = cursor.newRow()
        cursor.columnNames.forEach { column ->
            row.add(column, when (column) {
                Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID -> "root"
                Root.COLUMN_TITLE -> "EasyBase64 test files"
                Root.COLUMN_FLAGS -> Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD
                Root.COLUMN_MIME_TYPES -> "*/*"
                Root.COLUMN_AVAILABLE_BYTES -> root.usableSpace
                else -> null
            })
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply { addDocument(this, file(documentId)) }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply {
            val parent = file(parentDocumentId)
            if (!parent.isDirectory) throw FileNotFoundException("Not a directory")
            parent.listFiles()?.sortedBy { it.name }?.forEach { addDocument(this, it) }
        }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = file(parentDocumentId).canonicalPath + File.separator
        return file(documentId).canonicalPath.startsWith(parent)
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val source = file(documentId)
        opened.incrementAndGet()
        if (mode == "r" && denyReads) throw SecurityException("Injected source permission withdrawal")
        if (mode != "r" && failWrites) {
            val pipe = ParcelFileDescriptor.createReliablePipe()
            Thread({
                try {
                    ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).use { stream ->
                        stream.read(ByteArray(1024))
                        pipe[0].closeWithError("Injected output write failure")
                    }
                } catch (_: Exception) {
                    runCatching { pipe[0].close() }
                }
            }, "fixture-failing-output").start()
            return pipe[1]
        }
        if (mode == "r" && (source.name.startsWith("pipe-") || delayReadMillis > 0)) {
            val pipe = ParcelFileDescriptor.createReliablePipe()
            val delay = delayReadMillis
            Thread({
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                        source.inputStream().use { input ->
                            val buffer = ByteArray(4096)
                            var total = 0L
                            while (true) {
                                signal?.throwIfCanceled()
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (source.name.startsWith("pipe-read-failure") && total >= 8192) {
                                    pipe[1].closeWithError("Injected read failure")
                                    return@Thread
                                }
                                output.write(buffer, 0, count)
                                total += count
                                if (delay > 0) Thread.sleep(delay)
                            }
                        }
                    }
                } catch (_: Exception) {
                    runCatching { pipe[1].closeWithError("Pipe writer stopped") }
                }
            }, "fixture-provider-pipe").start()
            return pipe[0]
        }
        return ParcelFileDescriptor.open(source, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        require(displayName.isNotEmpty() && displayName != "." && displayName != "..")
        require(!displayName.contains('/') && !displayName.contains('\\') && !displayName.contains('\u0000'))
        val parent = file(parentDocumentId)
        if (!parent.isDirectory) throw FileNotFoundException("Parent unavailable")
        var target = File(parent, displayName)
        var index = 1
        while (target.exists()) target = File(parent, "$displayName (${index++})")
        val created = if (mimeType == Document.MIME_TYPE_DIR) target.mkdir() else target.createNewFile()
        if (!created) throw FileNotFoundException("Cannot create document")
        return id(target)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        if (noRename) throw UnsupportedOperationException("Rename disabled by test")
        require(!displayName.contains('/') && !displayName.contains('\\'))
        val source = file(documentId)
        val target = File(source.parentFile, displayName)
        if (target.exists()) throw FileNotFoundException("Refusing overwrite")
        if (!source.renameTo(target)) throw FileNotFoundException("Rename failed")
        if (revokeRenamedUris) revokedDocumentIds.add(documentId)
        return id(target)
    }

    override fun deleteDocument(documentId: String) {
        if (rejectDelete) throw FileNotFoundException("Injected delete failure")
        val target = file(documentId)
        require(target != root)
        if (!target.deleteRecursively()) throw FileNotFoundException("Delete failed")
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = when (method) {
        "reset" -> {
            root.deleteRecursively()
            check(root.mkdirs())
            noRename = false
            rejectDelete = false
            revokeRenamedUris = false
            revokedDocumentIds.clear()
            obsoleteUriAccesses.set(0)
            failWrites = false
            denyReads = false
            delayReadMillis = 0
            opened.set(0)
            if (arg != null) requireNotNull(context).grantUriPermission(arg,
                DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root"),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            Bundle()
        }
        "grant" -> {
            requireNotNull(context).grantUriPermission(requireNotNull(arg),
                Uri.parse(requireNotNull(extras?.getString("uri"))),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            Bundle()
        }
        "configure" -> {
            noRename = extras?.getBoolean("noRename", noRename) ?: noRename
            revokeRenamedUris = extras?.getBoolean("revokeRenamedUris", revokeRenamedUris) ?: revokeRenamedUris
            failWrites = extras?.getBoolean("failWrites", failWrites) ?: failWrites
            denyReads = extras?.getBoolean("denyReads", denyReads) ?: denyReads
            rejectDelete = extras?.getBoolean("rejectDelete", rejectDelete) ?: rejectDelete
            delayReadMillis = extras?.getLong("delayReadMillis", delayReadMillis) ?: delayReadMillis
            Bundle()
        }
        "stats" -> Bundle().apply { putInt("opened", opened.get()); putInt("obsoleteUriAccesses", obsoleteUriAccesses.get()) }
        else -> super.call(method, arg, extras)
    }

    private fun addDocument(cursor: MatrixCursor, file: File) {
        if (!file.exists()) throw FileNotFoundException("Document unavailable")
        cursor.newRow().apply {
            cursor.columnNames.forEach { column ->
                add(column, when (column) {
                    Document.COLUMN_DOCUMENT_ID -> id(file)
                    Document.COLUMN_DISPLAY_NAME -> file.name
                    Document.COLUMN_MIME_TYPE -> if (file.isDirectory) Document.MIME_TYPE_DIR else "application/octet-stream"
                    Document.COLUMN_FLAGS -> Document.FLAG_SUPPORTS_DELETE or
                        (if (noRename) 0 else Document.FLAG_SUPPORTS_RENAME) or
                        (if (file.isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE)
                    Document.COLUMN_SIZE -> if (file.name.startsWith("pipe-")) null else file.length()
                    Document.COLUMN_LAST_MODIFIED -> file.lastModified()
                    else -> null
                })
            }
        }
    }

    private fun file(id: String): File {
        if (id in revokedDocumentIds) {
            obsoleteUriAccesses.incrementAndGet()
            throw SecurityException("The provider revoked this URI after a successful rename")
        }
        val candidate = if (id == "root") root else File(root, id.removePrefix("root/"))
        if (candidate.canonicalFile != root.canonicalFile &&
            !candidate.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
            throw FileNotFoundException("Invalid document ID")
        }
        return candidate
    }

    private fun id(file: File): String = if (file.canonicalFile == root.canonicalFile) "root"
        else "root/" + file.relativeTo(root).invariantSeparatorsPath

    companion object {
        const val AUTHORITY = "com.hycnyl.easybase64.data.test.documents"
        private val ROOT_COLUMNS = arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE, Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES, Root.COLUMN_AVAILABLE_BYTES)
        private val DOCUMENT_COLUMNS = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
    }
}
