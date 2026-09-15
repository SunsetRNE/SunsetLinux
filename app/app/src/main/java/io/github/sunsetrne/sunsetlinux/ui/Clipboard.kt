package io.github.sunsetrne.sunsetlinux.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * 系统剪贴板。不用 Compose 的 ClipboardManager（各版本 API 有差异），
 * 直接用平台 API，行为在所有 Android 版本上一致。
 */
fun copyToClipboard(context: Context, label: String, text: String) {
    val manager = context.getSystemService(ClipboardManager::class.java) ?: return
    manager.setPrimaryClip(ClipData.newPlainText(label, text))
}
