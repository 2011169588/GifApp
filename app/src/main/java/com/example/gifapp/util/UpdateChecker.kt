package com.example.gifapp.util

import com.example.gifapp.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val hasUpdate: Boolean = false,
    val latestVersion: String = "",
    val releaseNotes: String = "",
    val downloadUrl: String = "",
    val releaseUrl: String = ""
)

object UpdateChecker {

    private const val API_URL = "https://api.github.com/repos/2011169588/GifApp/releases/latest"

    /** 检查最新 Release，网络失败返回无更新 */
    suspend fun check(): UpdateInfo = withContext(Dispatchers.IO) {
        try {
            val url = URL(API_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            val json = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val obj = JSONObject(json)
            val tagName = obj.getString("tag_name").removePrefix("v")
            val body = obj.optString("body", "")
            val releaseUrl = obj.getString("html_url")
            val downloadUrl = try {
                obj.getJSONArray("assets").getJSONObject(0).getString("browser_download_url")
            } catch (_: Exception) { "" }

            val hasUpdate = compareVersions(tagName, BuildConfig.VERSION_NAME) > 0
            UpdateInfo(hasUpdate, tagName, formatReleaseNotes(body), downloadUrl, releaseUrl)
        } catch (_: Exception) {
            UpdateInfo(false)
        }
    }

    /** Markdown → 纯文本：去除标题标记、加粗、列表符号 */
    private fun formatReleaseNotes(notes: String): String {
        return notes.lines().joinToString("\n") { line ->
            line.trimStart()
                .replace(Regex("^###+\\s*"), "")      // ### heading → text
                .replace(Regex("^\\*\\*([^*]+)\\*\\*"), "$1") // **bold** → text
                .replace(Regex("^[-*]\\s+"), "• ")    // list item → bullet
                .trimEnd()
        }.trim()
    }

    private fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
        val parts2 = v2.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(parts1.size, parts2.size)) {
            val cmp = (parts1.getOrElse(i) { 0 }).compareTo(parts2.getOrElse(i) { 0 })
            if (cmp != 0) return cmp
        }
        return 0
    }
}
