package com.orbis.easybase64

import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.orbis.easybase64.core.CodecOptions
import com.orbis.easybase64.core.Operation
import com.orbis.easybase64.data.BatchRepository
import com.orbis.easybase64.data.BatchStatus
import com.orbis.easybase64.data.TaskStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64
import java.util.UUID
import java.util.regex.Pattern

/** Exercises the actual local provider using an ordinary grant from Android's document picker. */
@RunWith(AndroidJUnit4::class)
class LocalSafIntegrationTest {
    @Test
    fun localProviderBinaryRoundTripDoesNotOverwriteSource() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val device = UiDevice.getInstance(instrumentation)
        val name = "EasyBase64-instrumented-${UUID.randomUUID()}"
        val rawPath = "/sdcard/Download/$name"
        val fixtureTree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "primary:Download/$name")
        val fixtureDocument = DocumentsContract.buildDocumentUriUsingTree(fixtureTree, "primary:Download/$name")
        val databaseName = "local-provider-${UUID.randomUUID()}.db"
        val repository = BatchRepository(context, databaseName)
        var scenario: ActivityScenario<MainActivity>? = null
        var granted = false
        try {
            shell("mkdir -p $rawPath")
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { activity ->
                @Suppress("DEPRECATION")
                activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                        DocumentsContract.buildDocumentUri(AUTHORITY, "primary:Download/$name"))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                }, 8877)
            }
            val useFolder = device.wait(Until.findObject(By.text(Pattern.compile("(?i)use this folder"))), 90_000)
            assertNotNull("Android document picker must offer the disposable fixture folder", useFolder)
            useFolder!!.click()
            val allow = device.wait(Until.findObject(By.text(Pattern.compile("(?i)allow"))), 30_000)
            assertNotNull("Android must confirm the app's folder access", allow)
            allow!!.click()
            assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName)), 30_000))
            resolver.takePersistableUriPermission(fixtureTree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            granted = true

            val sourceDirectory = requireNotNull(DocumentsContract.createDocument(
                resolver, fixtureDocument, DocumentsContract.Document.MIME_TYPE_DIR, "sources"
            ))
            val source = requireNotNull(DocumentsContract.createDocument(
                resolver, sourceDirectory, "application/octet-stream", "往返 binary.bin"
            ))
            val payload = ByteArray(65_537) { ((it * 37 + 11) and 255).toByte() }
            resolver.openOutputStream(source, "w")!!.use { it.write(payload) }
            repository.load()
            repository.setOutput(fixtureTree)
            repository.setFiles(listOf(source), persistable = false)
            repository.run(CodecOptions(operation = Operation.ENCODE))
            val encodedTask = repository.state.value.tasks.single()
            assertEquals(encodedTask.error, TaskStatus.SUCCEEDED, encodedTask.status)
            assertEquals("往返 binary.bin.b64", encodedTask.outputName)
            val encoded = Uri.parse(requireNotNull(encodedTask.outputUri))
            assertArrayEquals(Base64.getEncoder().encode(payload), resolver.openInputStream(encoded)!!.use { it.readBytes() })
            assertEquals("Successful local rename must not leave a residual: $encodedTask", 0, repository.state.value.residualCount)
            val firstBatch = repository.state.value.outputBatch

            repository.setFiles(listOf(encoded), persistable = false)
            assertEquals("A new decode selection must be ready", BatchStatus.READY, repository.state.value.status)
            assertEquals("The new decode batch must accept the encoded source: ${repository.state.value}", encoded.toString(), repository.state.value.tasks.single().sourceUri)
            repository.run(CodecOptions(operation = Operation.DECODE))
            assertEquals(Operation.DECODE, repository.state.value.options.operation)
            val decodedTask = repository.state.value.tasks.single()
            assertEquals(decodedTask.error, TaskStatus.SUCCEEDED, decodedTask.status)
            assertEquals("往返 binary.bin", decodedTask.outputName)
            assertNotEquals("Each run must have an independent output directory", firstBatch, repository.state.value.outputBatch)
            assertArrayEquals(payload, resolver.openInputStream(Uri.parse(decodedTask.outputUri))!!.use { it.readBytes() })
            assertArrayEquals("The original source must remain unchanged", payload, resolver.openInputStream(source)!!.use { it.readBytes() })
        } finally {
            repository.close()
            context.deleteDatabase(databaseName)
            if (granted) {
                try { DocumentsContract.deleteDocument(resolver, fixtureDocument) } catch (_: Exception) { }
                // The local provider may revoke the grant itself when its tree is deleted.
                try { resolver.releasePersistableUriPermission(fixtureTree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                } catch (_: SecurityException) { }
            }
            scenario?.close()
            // This exact UUID directory was created by this test, and contains no user-owned files.
            shell("rm -rf $rawPath")
        }
    }

    private fun shell(command: String) {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
            val buffer = ByteArray(1024)
            while (stream.read(buffer) != -1) Unit
        }
    }

    companion object {
        private const val AUTHORITY = "com.android.externalstorage.documents"
    }
}
