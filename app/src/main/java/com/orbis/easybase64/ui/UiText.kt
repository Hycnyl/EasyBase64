package com.orbis.easybase64.ui

import com.orbis.easybase64.core.*
import com.orbis.easybase64.data.*
import java.util.Locale

internal class UiText(val zh: Boolean) {
    fun t(en: String, cn: String) = if (zh) cn else en
    fun status(status: TaskStatus) = when (status) {
        TaskStatus.QUEUED -> t("Ready", "待处理")
        TaskStatus.RUNNING -> t("Processing", "处理中")
        TaskStatus.COMMITTING -> t("Saving", "正在保存")
        TaskStatus.SUCCEEDED -> t("Completed", "已完成")
        TaskStatus.FAILED -> t("Failed", "失败")
        TaskStatus.CANCELLED -> t("Cancelled", "已取消")
        TaskStatus.INTERRUPTED -> t("Interrupted", "已中断")
    }
    fun batch(status: BatchStatus) = when (status) {
        BatchStatus.IDLE, BatchStatus.READY -> t("Ready when you are", "准备就绪")
        BatchStatus.RUNNING -> t("Processing your files", "正在处理文件")
        BatchStatus.SUCCEEDED -> t("All files completed", "全部文件处理完成")
        BatchStatus.PARTIAL -> t("Completed with errors", "部分文件处理失败")
        BatchStatus.CANCELLED -> t("Batch cancelled", "批次已取消")
        BatchStatus.INTERRUPTED -> t("Previous batch interrupted", "上次批次已中断")
    }
    fun error(code: String?) = when (code?.substringBefore(':')) {
        "INVALID_CHARACTER" -> t("The body contains a character outside the selected alphabet. Check the alphabet and whitespace options.", "正文包含所选字母表之外的字符，请检查字母表及空白设置。")
        "INVALID_PADDING" -> t("Invalid padding, tail length or padding bits. Check the source; enable omitted padding only for unpadded input.", "填充、尾部长度或填充位无效。请检查源文件；仅对无填充输入开启“允许省略填充”。")
        "ENVELOPE_MISMATCH", "BOUNDARY_TOO_LONG" -> t("The CERTIFICATE wrapper is incomplete or unsupported. Use one matching BEGIN / END block.", "CERTIFICATE 包装不完整或不受支持，请使用单个匹配的 BEGIN / END 块。")
        "CONFIGURATION_MISMATCH", "ALPHABET_MISMATCH" -> t("CERTIFICATE wrapping requires the Standard alphabet. Change the alphabet and retry.", "CERTIFICATE 包装需要标准字母表，请更改字母表后重试。")
        "TRAILING_DATA" -> t("Unexpected data follows the Base64 body or wrapper. Remove the extra data in the source and retry.", "Base64 正文或包装之后存在额外内容，请修正源文件后重试。")
        "UNSUPPORTED_TEXT_ENCODING" -> t("Unsupported text encoding. Convert the source to ASCII or UTF-8 first.", "不支持此文本编码，请先将源文件转换为 ASCII 或 UTF-8。")
        "SOURCE_UNAVAILABLE" -> t("The source could not be read. Restore its availability or select it again.", "无法读取源文件，请恢复文件访问或重新选择。")
        "PERMISSION_LOST" -> t("File or folder access has expired. Select the source and output folder again.", "文件或文件夹授权已失效，请重新选择来源和输出目录。")
        "WRITE_FAILED" -> t("The output could not be saved. Check the selected folder, available space and provider, then retry.", "无法保存输出。请检查输出目录、可用空间及文档提供方后重试。")
        "SPACE_INSUFFICIENT" -> t("The provider reported insufficient space. Free space or select another output folder.", "提供方报告存储空间不足，请释放空间或选择其他输出目录。")
        "CLEANUP_FAILED" -> t("Some unfinished files could not be removed. Review their locations below and retry cleanup.", "部分未完成文件无法删除。请查看下方残留位置并重试清理。")
        "INTERRUPTED" -> t("The previous run did not finish. Review any residual files, then retry manually.", "上次运行未正常结束，请检查残留文件后手动重试。")
        "CANCELLED" -> t("This file was cancelled. Retry when ready.", "此文件已取消，可在准备好后重试。")
        "PERMISSION_NOT_PERSISTED" -> t("The provider did not retain access. You may need to select these files again after restarting the app.", "提供方未保留访问授权，重启应用后可能需要重新选择这些文件。")
        "SCAN_CANCELLED" -> t("Folder scan cancelled. Choose the folder again to create a complete queue.", "文件夹扫描已取消，请重新选择文件夹以建立完整队列。")
        "SCAN_INTERRUPTED" -> t("The previous folder scan was interrupted. Select the folder to scan again.", "上次文件夹扫描已中断，请重新选择文件夹进行扫描。")
        "RESCAN_REQUIRED" -> t("The output folder changed. Select the input folder again to refresh the queue safely.", "输出目录已更改，请重新选择输入目录以安全刷新队列。")
        "OUTPUT_RELATION_UNKNOWN" -> t("The provider could not verify the folder relationship. Choose an output folder in a separate document provider.", "提供方无法确认目录关系，请选择其他文档提供方中的独立输出目录。")
        "OUTPUT_EQUALS_INPUT" -> t("Input and output folders must differ. Choose a separate output folder.", "输入与输出不能为同一目录，请选择独立的输出目录。")
        "EMPTY_FOLDER" -> t("No eligible files were found. Try including subfolders or choose another folder.", "未找到可处理文件，请尝试包含子目录或选择其他文件夹。")
        "SCAN_FAILED" -> t("The folder could not be scanned. Check access and select it again.", "无法扫描文件夹，请检查访问权限后重新选择。")
        null -> ""
        else -> t("The operation could not finish. Check file access and the details, then retry.", "操作未能完成，请检查文件访问及详细信息后重试。")
    }
    fun summary(o: CodecOptions): String {
        if (o.operation == Operation.ENCODE && o.certificate) return "CERTIFICATE · Standard · 64 · CRLF"
        val parts = mutableListOf(if (o.alphabet == Alphabet.STANDARD) "Standard" else "URL-safe")
        if (o.operation == Operation.ENCODE) {
            parts += if (o.padding) t("padding", "保留填充") else t("no padding", "省略填充")
            parts += if (o.wrapWidth == 0) t("no wrap", "不折行") else "${o.wrapWidth} · ${o.lineEnding}"
            if (o.finalNewline) parts += t("final newline", "末尾换行")
        } else {
            if (o.ignoreWhitespace) parts += t("ignore whitespace", "忽略空白")
            if (o.recognizeCertificate) parts += t("wrapper recognition", "识别包装")
            parts += if (o.allowOmittedPadding) t("omitted padding allowed", "允许无填充") else t("strict padding", "严格填充")
        }
        return parts.joinToString(" · ")
    }
}

internal fun formatBytes(bytes: Long?): String {
    if (bytes == null) return "—"
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit++ }
    return String.format(Locale.ROOT, "%.1f %s", value, units[unit])
}
