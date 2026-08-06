package com.snapclear.app.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import com.snapclear.app.R
import java.io.File

/**
 * 剪贴板与文件删除工具类
 *
 * 提供：
 * - 将图片数据安全复制到系统剪贴板（写入缓存后通过 FileProvider 暴露 URI，
 *   确保删除原文件后剪贴板内容仍然有效）
 * - 删除 MediaStore 中的截图文件（适配分区存储，使用系统删除请求对话框）
 * - 组合操作：先复制再请求删除
 */
object ClipboardHelper {

    private const val CACHE_IMAGE_PREFIX = "snapclear_clipboard"

    /**
     * 将图片 URI 复制到系统剪贴板
     *
     * 先将原始图片数据复制到应用缓存目录，再通过 FileProvider 生成可共享的
     * content:// URI 放入剪贴板。这样剪贴板内容独立于原始文件，原文件删除后
     * 粘贴仍然有效。
     */
    fun copyImageToClipboard(context: Context, sourceUri: Uri): Boolean {
        return try {
            // 读取原始图片的 MIME 类型并确定文件扩展名
            val mimeType = context.contentResolver.getType(sourceUri) ?: "image/png"
            val extension = when {
                mimeType.contains("jpeg") || mimeType.contains("jpg") -> ".jpg"
                mimeType.contains("webp") -> ".webp"
                mimeType.contains("gif") -> ".gif"
                else -> ".png"
            }

            // 将图片数据复制到缓存目录（带正确的扩展名以便 FileProvider 识别 MIME）
            val cacheFile = File(context.cacheDir, "$CACHE_IMAGE_PREFIX$extension")
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                cacheFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return false

            // 通过 FileProvider 获取可共享的 content:// URI
            val cacheUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                cacheFile
            )

            // 放入剪贴板
            val clip = ClipData.newUri(context.contentResolver, "Screenshot", cacheUri)
            val manager = context.getSystemService(ClipboardManager::class.java)
            manager?.setPrimaryClip(clip)
            true
        } catch (e: Exception) {
            android.util.Log.e("ClipboardHelper", "copyImageToClipboard failed", e)
            false
        }
    }

    /**
     * 请求删除 MediaStore 中的截图文件
     *
     * API 30+：使用 MediaStore.createDeleteRequest() 弹出系统确认对话框，
     * 用户确认后由系统执行删除（异步，本方法在弹出对话框后立即返回）。
     *
     * API 29-：直接使用 contentResolver.delete()。
     *
     * @return true 表示删除请求已发送/执行；false 表示请求失败
     */
    fun requestDeleteScreenshot(context: Context, uri: Uri): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // API 30+：系统删除请求（弹出确认对话框）
                val pendingIntent = MediaStore.createDeleteRequest(
                    context.contentResolver,
                    listOf(uri)
                )
                pendingIntent.send()
                true
            } else {
                // API 29-：直接删除
                context.contentResolver.delete(uri, null, null) > 0
            }
        } catch (e: SecurityException) {
            android.util.Log.w("ClipboardHelper", "delete SecurityException, trying recovery", e)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val recoverableException = e as? android.app.RecoverableSecurityException
                    val intentSender = recoverableException?.userAction?.actionIntent?.intentSender
                    if (intentSender != null) {
                        context.startIntentSender(intentSender, null, 0, 0, 0)
                        return true
                    }
                }
            } catch (e2: Exception) {
                android.util.Log.e("ClipboardHelper", "Recovery attempt failed", e2)
            }
            false
        } catch (e: Exception) {
            android.util.Log.e("ClipboardHelper", "requestDeleteScreenshot failed", e)
            false
        }
    }

    /**
     * 先复制图片到剪贴板，再请求删除原文件
     *
     * 流程：
     * 1. 将图片数据复制到缓存并通过 FileProvider URI 放入剪贴板
     * 2. Toast 提示「已拷贝到剪贴板」
     * 3. 弹出系统删除确认对话框（用户确认后由系统执行删除）
     */
    fun copyAndDelete(context: Context, uri: Uri) {
        val copied = copyImageToClipboard(context, uri)
        if (!copied) {
            Toast.makeText(context, R.string.toast_copy_failed, Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(context, "已拷贝到剪贴板", Toast.LENGTH_SHORT).show()

        val deleteRequested = requestDeleteScreenshot(context, uri)
        if (!deleteRequested) {
            Toast.makeText(
                context,
                R.string.toast_copied_delete_failed,
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
