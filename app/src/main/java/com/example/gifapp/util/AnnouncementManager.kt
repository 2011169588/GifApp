package com.example.gifapp.util

import android.content.Context
import android.os.Build
import com.example.gifapp.BuildConfig
import com.example.gifapp.model.Announcement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

object AnnouncementManager {

    private const val PREFS_NAME = "gif_announcement"
    private const val SEEN_KEY = "seen_ids"
    private const val GIST_RAW_URL = "https://gist.githubusercontent.com/2011169588/9e80ff69c80c3e1aa407563468ae14b5/raw/announcements.json"
    private const val HOMEPAGE_DESC_URL = "https://gist.githubusercontent.com/2011169588/9e80ff69c80c3e1aa407563468ae14b5/raw/homepage.txt"

    /** 从远程 Gist 拉取公告和主页介绍，返回 Pair(公告列表, 主页介绍文字) */
    suspend fun fetchAll(context: Context): Pair<List<Announcement>, String?> = withContext(Dispatchers.IO) {
        val seen = getSeenIds(context)
        try {
            // 拉取公告（旧格式 JSONArray，完全向后兼容）
            val url = URL(GIST_RAW_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "GET"
            val text = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val arr = org.json.JSONArray(text)
            val list = mutableListOf<Announcement>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.getString("id")
                val title = obj.getString("title")
                val message = obj.getString("message")
                // 用 id + 内容摘要判断已读（内容更新后会重新弹）
                val digest = id + "|" + title.take(20) + "|" + message.take(40)
                if (digest in seen) continue
                list.add(Announcement(
                    id = id, title = title, message = message,
                    date = obj.optString("date", ""), type = obj.optString("type", "info")
                ))
            }
            Pair(list.sortedByDescending { it.date }, fetchHomepageDescription())
        } catch (_: Exception) { Pair(emptyList(), null) }
    }

    /** 拉取主页介绍文字（独立文件，不影响旧版公告） */
    private suspend fun fetchHomepageDescription(): String? = withContext(Dispatchers.IO) {
        try {
            val hpUrl = URL(HOMEPAGE_DESC_URL)
            val conn = hpUrl.openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            val text = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            text.trim().ifEmpty { null }
        } catch (_: Exception) { null }
    }

    /** 向后兼容：只拉取公告 */
    suspend fun fetchUnread(context: Context): List<Announcement> = fetchAll(context).first

    fun markSeen(context: Context, id: String, title: String = "", message: String = "") {
        val seen = getSeenIds(context).toMutableSet()
        val digest = id + "|" + title.take(20) + "|" + message.take(40)
        seen.add(digest)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putStringSet(SEEN_KEY, seen).apply()
    }

    fun markAllSeen(context: Context) {
        // 远程获取太麻烦，直接清掉所有已读标记让下次重新获取
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(SEEN_KEY).apply()
    }

    private fun getSeenIds(context: Context): Set<String> {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(SEEN_KEY, emptySet()) ?: emptySet()
    }
}
