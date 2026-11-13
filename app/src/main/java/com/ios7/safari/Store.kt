package com.ios7.safari

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Link(val title: String, val url: String, val time: Long = 0L)

/** 书签 / 阅读列表 / 历史记录 的本地持久化（SharedPreferences + JSON） */
object Store {

    private const val P = "ios7_safari"
    private const val K_BM = "bookmarks"
    private const val K_RL = "reading"
    private const val K_HS = "history"

    private fun prefs(c: Context) = c.getSharedPreferences(P, Context.MODE_PRIVATE)

    /**
     * 数据版本号：只要有写入就 +1。
     * 书签页拿它判断内存缓存（书签 / 阅读列表 / 历史记录）是否过期，
     * 于是切换分段时不用再重读 SharedPreferences + 解析 JSON。
     */
    @Volatile
    var version: Int = 0
        private set

    private fun read(c: Context, key: String): MutableList<Link> {
        val raw = prefs(c).getString(key, "") ?: ""
        val out = mutableListOf<Link>()
        if (raw.isBlank()) return out
        return try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Link(o.optString("t"), o.optString("u"), o.optLong("ts")))
            }
            out
        } catch (e: Exception) {
            out
        }
    }

    private fun write(c: Context, key: String, list: List<Link>) {
        version++                              // 让界面上的列表缓存失效
        val arr = JSONArray()
        list.forEach {
            val o = JSONObject()
            o.put("t", it.title)
            o.put("u", it.url)
            o.put("ts", it.time)
            arr.put(o)
        }
        prefs(c).edit().putString(key, arr.toString()).apply()
    }

    fun bookmarks(c: Context) = read(c, K_BM)

    fun saveBookmarks(c: Context, l: List<Link>) = write(c, K_BM, l)

    fun addBookmark(c: Context, l: Link) {
        val list = bookmarks(c)
        if (list.none { it.url == l.url }) {
            list.add(0, l)
            saveBookmarks(c, list)
        }
    }

    fun removeBookmark(c: Context, url: String) =
        saveBookmarks(c, bookmarks(c).filter { it.url != url })

    fun reading(c: Context) = read(c, K_RL)

    fun saveReading(c: Context, l: List<Link>) = write(c, K_RL, l)

    fun addReading(c: Context, l: Link) {
        val list = reading(c)
        if (list.none { it.url == l.url }) {
            list.add(0, l)
            saveReading(c, list)
        }
    }

    fun removeReading(c: Context, url: String) =
        saveReading(c, reading(c).filter { it.url != url })

    fun history(c: Context) = read(c, K_HS)

    fun addHistory(c: Context, l: Link) {
        val list = history(c).toMutableList()
        list.removeAll { it.url == l.url }
        list.add(0, Link(l.title, l.url, System.currentTimeMillis()))
        write(c, K_HS, list.take(300))
    }

    fun clearHistory(c: Context) = write(c, K_HS, emptyList())

    /** 首次启动时预置的个人收藏 */
    fun seedDefaults(c: Context) {
        if (prefs(c).contains(K_BM)) return
        saveBookmarks(c, listOf(
            Link("Apple", "https://www.apple.com.cn"),
            Link("百度", "https://www.baidu.com"),
            Link("Google", "https://www.google.com"),
            Link("维基百科", "https://zh.wikipedia.org"),
            Link("知乎", "https://www.zhihu.com"),
            Link("微博", "https://m.weibo.cn")
        ))
    }
}
