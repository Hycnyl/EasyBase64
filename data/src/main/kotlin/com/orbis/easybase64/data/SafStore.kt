package com.orbis.easybase64.data

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import java.io.IOException

internal data class DocumentInfo(
    val uri: Uri,
    val id: String,
    val name: String,
    val size: Long?,
    val directory: Boolean,
    val flags: Long,
)

internal class StorageException(val code: String, message: String, cause: Throwable? = null) : IOException(message, cause)

/** URI handling only uses DocumentsContract; document IDs are opaque except the documented local provider format. */
internal class SafStore(private val resolver: ContentResolver) {
    fun treeDocument(tree: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    fun info(uri: Uri): DocumentInfo {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val typeIndex = cursor.getColumnIndex(Document.COLUMN_MIME_TYPE)
                val flagsIndex = cursor.getColumnIndex(Document.COLUMN_FLAGS)
                return DocumentInfo(
                    uri, documentIdOrUri(uri),
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) cursor.getString(nameIndex) else "unnamed",
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex).takeIf { it >= 0L } else null,
                    typeIndex >= 0 && cursor.getString(typeIndex) == Document.MIME_TYPE_DIR,
                    if (flagsIndex >= 0 && !cursor.isNull(flagsIndex)) cursor.getLong(flagsIndex) else 0L,
                )
            }
        }
        throw FileNotFoundException("Document is unavailable: $uri")
    }

    fun children(parent: Uri, signal: CancellationSignal? = null, consume: (DocumentInfo) -> Unit) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
        val projection = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS)
        val cursor = resolver.query(children, projection, null, null, null, signal)
            ?: throw IOException("Document provider returned no directory listing")
        cursor.use {
            val id = it.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)
            val name = it.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)
            val size = it.getColumnIndex(Document.COLUMN_SIZE)
            val type = it.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)
            val flags = it.getColumnIndex(Document.COLUMN_FLAGS)
            while (it.moveToNext()) {
                signal?.throwIfCanceled()
                val docId = it.getString(id)
                consume(DocumentInfo(
                    DocumentsContract.buildDocumentUriUsingTree(parent, docId), docId,
                    it.getString(name) ?: "unnamed",
                    if (size >= 0 && !it.isNull(size)) it.getLong(size).takeIf { n -> n >= 0L } else null,
                    it.getString(type) == Document.MIME_TYPE_DIR,
                    if (flags >= 0 && !it.isNull(flags)) it.getLong(flags) else 0L,
                ))
            }
        }
    }

    /** Returns an output document ID to exclude when it is inside input. Fails closed on unknown relationships. */
    fun excludedOutputId(inputTree: Uri, outputTree: Uri): String? {
        if (inputTree.authority != outputTree.authority) return null
        val inputId = DocumentsContract.getTreeDocumentId(inputTree)
        val outputId = DocumentsContract.getTreeDocumentId(outputTree)
        if (inputId == outputId) throw StorageException("OUTPUT_EQUALS_INPUT", "Choose an output folder different from the input folder.")
        val input = treeDocument(inputTree)
        val output = treeDocument(outputTree)
        val isChild = try {
            DocumentsContract.isChildDocument(resolver, input, output)
        } catch (e: Exception) {
            // Only ExternalStorageProvider defines path-shaped IDs. Never infer hierarchy for arbitrary providers.
            if (inputTree.authority == "com.android.externalstorage.documents") {
                outputId.startsWith(inputId.trimEnd('/') + "/")
            } else {
                throw StorageException("OUTPUT_RELATION_UNKNOWN", "The provider cannot verify folder relationships; choose another output provider or use multiple files.", e)
            }
        }
        return outputId.takeIf { isChild }
    }

    fun createUnique(parent: Uri, requested: String, mime: String): DocumentInfo {
        val existingNames = HashSet<String>()
        val existingIds = HashSet<String>()
        children(parent) { existingNames += it.name; existingIds += it.id }
        val name = StorageNames.unique(requested, existingNames)
        val uri = DocumentsContract.createDocument(resolver, parent, mime, name)
            ?: throw StorageException("WRITE_FAILED", "Provider could not create the output document.")
        // A conforming DocumentsProvider creates a new document. If not, never write or delete an old URI.
        if (documentIdOrUri(uri) in existingIds) {
            throw StorageException("WRITE_FAILED", "Provider returned an existing document; output was not opened.")
        }
        return try { info(uri) } catch (_: Exception) {
            // The URI must be journaled even if the provider cannot immediately query its new metadata.
            DocumentInfo(uri, documentIdOrUri(uri), name, null, mime == Document.MIME_TYPE_DIR, 0L)
        }
    }

    fun finalName(parent: Uri, requested: String): String {
        val existing = HashSet<String>()
        children(parent) { existing += it.name }
        return StorageNames.unique(requested, existing)
    }

    fun sameDocument(a: Uri, b: Uri): Boolean = a.authority == b.authority && documentIdOrUri(a) == documentIdOrUri(b)
    fun rename(uri: Uri, name: String): Uri? = DocumentsContract.renameDocument(resolver, uri, name)
    fun delete(uri: Uri): Boolean = DocumentsContract.deleteDocument(resolver, uri)
    fun exists(uri: Uri): Boolean = try { info(uri); true } catch (_: FileNotFoundException) { false }
    private fun documentIdOrUri(uri: Uri): String = try { DocumentsContract.getDocumentId(uri) } catch (_: IllegalArgumentException) { uri.toString() }
}
