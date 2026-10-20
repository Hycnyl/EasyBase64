package com.orbis.easybase64

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContract
import androidx.lifecycle.lifecycleScope
import com.orbis.easybase64.core.CodecOptions
import com.orbis.easybase64.ui.EasyBase64App
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val repository get() = (application as EasyBase64Application).repository
    private var recursive = false
    private var pendingStart: Pair<CodecOptions, Boolean>? = null
    private val zh get() = when (getSharedPreferences("easybase64_ui", MODE_PRIVATE).getString("language", "system")) {
        "zh" -> true
        "en" -> false
        else -> resources.configuration.locales[0].language == "zh"
    }
    private fun message(en: String, cn: String) = Toast.makeText(this, if (zh) cn else en, Toast.LENGTH_LONG).show()
    private fun persist(intent: Intent?, uris: List<Uri>) {
        val available = intent?.flags ?: 0
        val modes = available and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        var failed = false
        for (uri in uris) {
            try {
                if (available and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0 && modes != 0) {
                    when (modes) {
                        Intent.FLAG_GRANT_READ_URI_PERMISSION -> contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION -> contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        else -> contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    }
                } else failed = true
            } catch (_: SecurityException) { failed = true }
        }
        if (failed) message("This provider granted temporary access only. Reselect these files after restarting the app.", "此提供方仅授予临时访问权限，重启应用后可能需要重新选择文件。")
    }
    private val files = registerForActivityResult(object : ActivityResultContract<Array<String>, List<Uri>>() {
        private val delegate = ActivityResultContracts.OpenMultipleDocuments()
        override fun createIntent(context: Context, input: Array<String>) = delegate.createIntent(context, input)
        override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
            val result = delegate.parseResult(resultCode, intent)
            if (resultCode == Activity.RESULT_OK) persist(intent, result)
            return result
        }
    }) { uris -> if (uris.isNotEmpty()) lifecycleScope.launch { repository.setFiles(uris, true) } }
    private val output = registerForActivityResult(object : ActivityResultContract<Uri?, Uri?>() {
        private val delegate = ActivityResultContracts.OpenDocumentTree()
        override fun createIntent(context: Context, input: Uri?) = delegate.createIntent(context, input)
        override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
            val result = delegate.parseResult(resultCode, intent)
            if (result != null) persist(intent, listOf(result))
            return result
        }
    }) { uri -> if (uri != null) lifecycleScope.launch { repository.setOutput(uri) } }
    private val inputTree = registerForActivityResult(object : ActivityResultContract<Uri?, Uri?>() {
        private val delegate = ActivityResultContracts.OpenDocumentTree()
        override fun createIntent(context: Context, input: Uri?) = delegate.createIntent(context, input)
        override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
            val result = delegate.parseResult(resultCode, intent)
            if (result != null) persist(intent, listOf(result))
            return result
        }
    }) { uri ->
        val root = repository.state.value.outputRoot
        if (uri != null && root != null) lifecycleScope.launch { repository.scanTree(uri, Uri.parse(root), recursive) }
    }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingStart?.let { startService(it.first, it.second) }
        pendingStart = null
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recursive = savedInstanceState?.getBoolean("recursive") ?: false
        @Suppress("DEPRECATION")
        val savedStart = savedInstanceState?.getParcelable<Intent>("pending_start")
        if (savedStart != null) pendingStart = BatchService.options(savedStart) to savedStart.getBooleanExtra("retry", false)
        enableEdgeToEdge()
        setContent {
            EasyBase64App(
                repository = repository,
                onPickFiles = { files.launch(arrayOf("*/*")) },
                onPickInputTree = { includeChildren ->
                    recursive = includeChildren
                    if (repository.state.value.outputRoot == null) message("Choose an output folder first.", "请先选择输出目录。")
                    else inputTree.launch(null)
                },
                onPickOutput = { output.launch(repository.state.value.outputRoot?.let(Uri::parse)) },
                onStart = { options, retry ->
                    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        pendingStart = options to retry
                        notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else startService(options, retry)
                },
                onOpenOutput = {
                    val state = repository.state.value
                    val uri = (state.outputBatch ?: state.outputRoot)?.let(Uri::parse)
                    if (uri != null) {
                        try {
                            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            startActivity(view)
                        } catch (_: ActivityNotFoundException) {
                            val browse = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri)
                            try { startActivity(browse) } catch (_: Exception) { message("No file browser is available.", "没有可用的文件浏览器。") }
                        } catch (_: SecurityException) {
                            message("Output access was revoked. Please select the output folder again.", "输出目录授权已失效，请重新选择输出目录。")
                        }
                    }
                },
            )
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("recursive", recursive)
        pendingStart?.let { outState.putParcelable("pending_start", BatchService.intent(this, it.first, it.second)) }
        super.onSaveInstanceState(outState)
    }
    private fun startService(options: CodecOptions, retry: Boolean) {
        try { startForegroundService(BatchService.intent(this, options, retry)) }
        catch (_: IllegalStateException) { message("Keep the app open and try starting again.", "请保持应用在前台，然后重新开始。") }
        catch (_: SecurityException) { message("Unable to start the task. Check app permissions and retry.", "无法启动任务，请检查应用权限后重试。") }
    }
}
