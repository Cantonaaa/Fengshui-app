package com.fengshui.app

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用内更新通道（GitHub Releases）：
 * 用户主动"检查更新"→ 拉取最新 release → 比较版本 → 下载 APK → 系统安装。
 */
object UpdateChecker {

    const val REPO = "Cantonaaa/Fengshui-app"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"

    /** 更新信息。 */
    data class UpdateInfo(val version: String, val apkUrl: String, val notes: String)

    /** 拉取最新 release（网络，须后台线程）。无更新/失败返回 null。 */
    fun checkLatest(): UpdateInfo? {
        val conn = URL(API).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "FengshuiApp")
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            parse(body)
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(body: String): UpdateInfo? {
        val json = JSONObject(body)
        val tag = json.optString("tag_name", "").removePrefix("v")
        val notes = json.optString("body", "")
        val assets = json.optJSONArray("assets") ?: return null
        var apkUrl: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk")) { apkUrl = a.optString("browser_download_url"); break }
        }
        return apkUrl?.let { UpdateInfo(tag, it, notes) }
    }

    /** 语义版本比较：latest 是否新于 current。 */
    fun isNewer(latest: String, current: String): Boolean {
        val a = latest.split('.').map { it.toIntOrNull() ?: 0 }
        val b = current.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** 下载 APK 到 app 外部 Download 目录（系统 DownloadManager），文件名带版本号避免覆盖，返回下载 ID。 */
    fun download(context: Context, url: String, version: String): Long {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val req = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle("风水堪舆更新")
            setDescription("正在下载 v$version…")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "fengshui-update-$version.apk")
        }
        return dm.enqueue(req)
    }

    /** 下载进度百分比；完成返回 100，失败返回 -1。 */
    fun downloadProgress(context: Context, id: Long): Int {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val q = DownloadManager.Query().setFilterById(id)
        val cursor = dm.query(q)
        return try {
            if (cursor != null && cursor.moveToFirst()) {
                val bytes = cursor.getLong(cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = cursor.getLong(cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS))
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> 100
                    DownloadManager.STATUS_FAILED -> -1
                    else -> if (total > 0) (bytes * 100 / total).toInt() else 0
                }
            } else -1
        } finally {
            cursor?.close()
        }
    }

    /** 下载完成的 APK 文件（外部 Download 目录，按版本号命名）。 */
    fun downloadedFile(context: Context, version: String): File? {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
        val f = File(dir, "fengshui-update-$version.apk")
        return if (f.exists()) f else null
    }

    /** 唤起系统安装器。 */
    fun install(context: Context, file: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) return false
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return true
    }

    /** 引导用户开启"安装未知来源"。 */
    fun openInstallPermissionSettings(context: Context) {
        val intent = Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
