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

    /** 从远程 Gist 拉取公告，过滤已读 */
    suspend fun fetchUnread(context: Context): List<Announcement> = withContext(Dispatchers.IO) {
        val seen = getSeenIds(context)
        try {
            val url = URL(GIST_RAW_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "GET"
            val json = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val arr = JSONArray(json)
            val list = mutableListOf<Announcement>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.getString("id")
                if (id in seen) continue
                list.add(Announcement(
                    id = id,
                    title = obj.getString("title"),
                    message = obj.getString("message"),
                    date = obj.optString("date", ""),
                    type = obj.optString("type", "info")
                ))
            }
            list.sortedByDescending { it.date }
        } catch (_: Exception) { emptyList() }
    }

    fun markSeen(context: Context, id: String) {
        val seen = getSeenIds(context).toMutableSet()
        seen.add(id)
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
