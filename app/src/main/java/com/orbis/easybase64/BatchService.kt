package com.orbis.easybase64

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.widget.Toast
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import com.orbis.easybase64.core.Alphabet
import com.orbis.easybase64.core.CodecOptions
import com.orbis.easybase64.core.LineEnding
import com.orbis.easybase64.core.Operation
import com.orbis.easybase64.data.BatchStatus
import com.orbis.easybase64.data.TaskStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class BatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository get() = (application as EasyBase64Application).repository
    private var work: Job? = null
    private var startCancelled = AtomicBoolean(false)
    private val zh get() = when (getSharedPreferences("easybase64_ui", MODE_PRIVATE).getString("language", "system")) {
        "zh" -> true; "en" -> false; else -> resources.configuration.locales[0].language == "zh"
    }
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, if (zh) "文件转换" else "File conversion", NotificationManager.IMPORTANCE_LOW)
        )
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) {
            startCancelled.set(true)
            repository.cancel()
            if (work?.isActive != true) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (work?.isActive == true) return START_NOT_STICKY
        val cancellation = AtomicBoolean(false)
        startCancelled = cancellation
        try {
            startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (_: IllegalStateException) {
            showFailure(); stopSelf(startId); return START_NOT_STICKY
        } catch (_: SecurityException) {
            showFailure(); stopSelf(startId); return START_NOT_STICKY
        }
        val options = intent?.let(::options) ?: CodecOptions()
        work = scope.launch {
            val updates = launch {
                while (true) {
                    getSystemService(NotificationManager::class.java).notify(ID, notification())
                    delay(500)
                }
            }
            try {
                repository.load()
                repository.run(options, intent?.getBooleanExtra("retry", false) ?: false, startCancelled = { cancellation.get() })
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                repository.interrupt()
                showFailure()
            } finally {
                updates.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    private fun showFailure() {
        Toast.makeText(this, if (zh) "任务无法继续，请重新打开应用检查状态并重试。" else "The task stopped. Reopen the app to review its status and retry.", Toast.LENGTH_LONG).show()
    }
    private fun notification(): Notification {
        val state = repository.state.value
        val current = state.tasks.firstOrNull { it.status == TaskStatus.RUNNING || it.status == TaskStatus.COMMITTING }
        val completed = state.tasks.count { it.status == TaskStatus.SUCCEEDED || it.status == TaskStatus.FAILED }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancel = PendingIntent.getService(this, 1, Intent(this, BatchService::class.java).setAction(CANCEL), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(if (state.cancelling) { if (zh) "正在取消…" else "Cancelling…" } else "EasyBase64 · $completed / ${state.tasks.size}")
            .setContentText(current?.name ?: if (zh) "正在准备文件转换" else "Preparing file conversion")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(state.tasks.size.coerceAtLeast(1), completed, state.tasks.isEmpty())
            .addAction(Notification.Action.Builder(null, if (zh) "取消" else "Cancel", cancel).build())
            .build()
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        startCancelled.set(true)
        repository.interrupt()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() {
        if (repository.state.value.status == BatchStatus.RUNNING) repository.interrupt()
        scope.cancel()
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "conversion"
        private const val ID = 64
        private const val CANCEL = "com.orbis.easybase64.CANCEL"
        fun intent(context: Context, options: CodecOptions, retry: Boolean): Intent = Intent(context, BatchService::class.java).apply {
            putExtra("retry", retry)
            putExtra("operation", options.operation.name); putExtra("alphabet", options.alphabet.name)
            putExtra("padding", options.padding); putExtra("wrapWidth", options.wrapWidth)
            putExtra("lineEnding", options.lineEnding.name); putExtra("finalNewline", options.finalNewline)
            putExtra("certificate", options.certificate); putExtra("ignoreWhitespace", options.ignoreWhitespace)
            putExtra("recognizeCertificate", options.recognizeCertificate); putExtra("allowOmittedPadding", options.allowOmittedPadding)
        }
        internal fun options(intent: Intent) = CodecOptions(
            operation = Operation.valueOf(intent.getStringExtra("operation") ?: "ENCODE"),
            alphabet = Alphabet.valueOf(intent.getStringExtra("alphabet") ?: "STANDARD"),
            padding = intent.getBooleanExtra("padding", true), wrapWidth = intent.getIntExtra("wrapWidth", 0),
            lineEnding = LineEnding.valueOf(intent.getStringExtra("lineEnding") ?: "LF"),
            finalNewline = intent.getBooleanExtra("finalNewline", false), certificate = intent.getBooleanExtra("certificate", false),
            ignoreWhitespace = intent.getBooleanExtra("ignoreWhitespace", true), recognizeCertificate = intent.getBooleanExtra("recognizeCertificate", true),
            allowOmittedPadding = intent.getBooleanExtra("allowOmittedPadding", false),
        )
    }
}
