package com.hycnyl.easybase64.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hycnyl.easybase64.core.CodecException
import com.hycnyl.easybase64.core.Operation
import com.hycnyl.easybase64.core.TextCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 192 KiB of UTF-8 produces at most 256 KiB of padded Base64.
private const val MAX_TEXT_BYTES = 192 * 1024
private const val MAX_INPUT_LENGTH = 256 * 1024

// Keep drafts across rotations in memory, outside the saved instance state bundle.
private class TextCodecViewModel : ViewModel() {
    var input by mutableStateOf("")
        private set
    var result by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    private var conversion: Job? = null

    fun edit(value: String) {
        conversion?.cancel()
        busy = false
        result = null
        if (value.length > MAX_INPUT_LENGTH) {
            error = "EDIT_TOO_LONG"
            return
        }
        input = value
        error = null
    }

    fun convert(operation: Operation) {
        if (busy) return
        val source = input
        result = null
        error = null
        busy = true
        conversion = viewModelScope.launch {
            try {
                result = withContext(Dispatchers.Default) {
                    if (operation == Operation.ENCODE && source.length > MAX_TEXT_BYTES / 3 &&
                        source.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES) {
                        throw CodecException("TEXT_TOO_LONG", "UTF-8 text exceeds 192 KiB")
                    }
                    TextCodec.transform(source, operation) { !isActive }
                }
            } catch (failure: CodecException) {
                ensureActive()
                error = failure.code
            } finally {
                if (isActive) busy = false
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun TextCodecDialog(tx: UiText, onClose: () -> Unit) {
    val model = viewModel { TextCodecViewModel() }
    val close = {
        model.edit("")
        onClose()
    }
    SheetDialog(tx.t("Text Base64", "文本编解码"), tx, close, "text_tool_dialog") {
        val focus = LocalFocusManager.current
        // Keep the IME/editor's internal state out of Android's Binder transaction, too.
        CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
            OutlinedTextField(
                value = model.input,
                onValueChange = model::edit,
                modifier = Modifier.fillMaxWidth().testTag("text_input"),
                label = { Text(tx.t("Text or Base64", "文本或 Base64")) },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                minLines = 3,
                maxLines = 6,
            )
        }
        val canConvert = model.input.isNotEmpty() && !model.busy
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Button(onClick = {
                focus.clearFocus()
                model.convert(Operation.ENCODE)
            }, enabled = canConvert, modifier = Modifier.testTag("text_encode")) {
                Text(tx.t("Encode", "编码"))
            }
            OutlinedButton(onClick = {
                focus.clearFocus()
                model.convert(Operation.DECODE)
            }, enabled = canConvert, modifier = Modifier.testTag("text_decode")) {
                Text(tx.t("Decode", "解码"))
            }
        }
        model.error?.let { code ->
            val message = when (code) {
                "EDIT_TOO_LONG" -> tx.t("That edit exceeds the text box limit. Use file mode for longer input.", "此次输入超出文本框容量，请使用文件模式处理更长的内容。")
                "TEXT_TOO_LONG" -> tx.t("Use file mode to encode more than 192 KiB of UTF-8 text.", "超过 192 KiB 的 UTF-8 文本请使用文件模式编码。")
                "INVALID_UTF8" -> tx.t("The decoded bytes are not UTF-8 text. Use file mode for binary data.", "解码后的内容不是 UTF-8 文本，请使用文件模式处理二进制数据。")
                "INVALID_UNICODE" -> tx.t("The text contains an incomplete Unicode character. Replace it and try again.", "文本中有不完整的 Unicode 字符，请替换后重试。")
                else -> tx.t("Check the Base64 characters and trailing padding (=).", "请检查 Base64 字符和末尾填充（=）。")
            }
            Text(message, modifier = Modifier.testTag("text_error").semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        model.result?.let { result ->
            val context = LocalContext.current
            val label = tx.t("Result", "结果")
            CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
                OutlinedTextField(
                    value = result,
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth().testTag("text_result"),
                    label = { Text(label) },
                    minLines = 2,
                    maxLines = 6,
                )
            }
            TextButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(label, result))
                if (Build.VERSION.SDK_INT < 33) {
                    Toast.makeText(context, tx.t("Copied", "已复制"), Toast.LENGTH_SHORT).show()
                }
            }, enabled = result.isNotEmpty(), modifier = Modifier.testTag("text_copy")) {
                Text(tx.t("Copy result", "复制结果"))
            }
        }
    }
}
