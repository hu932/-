package com.example.accountmanager

import android.content.Context
import org.json.JSONArray

/** 本地持久化：local / shared / removed 三个键，存 SharedPreferences。 */
class Storage(context: Context) {

    private val prefs = context.getSharedPreferences("fb_accounts", Context.MODE_PRIVATE)

    fun loadLocal(): List<Account> = loadList(KEY_LOCAL)

    fun loadShared(): List<Account> = loadList(KEY_SHARED)

    fun loadRemoved(): List<String> = try {
        val arr = JSONArray(prefs.getString(KEY_REMOVED, "[]") ?: "[]")
        (0 until arr.length()).map { arr.getString(it) }
    } catch (e: Exception) {
        emptyList()
    }

    fun save(local: List<Account>, shared: List<Account>, removed: List<String>) {
        prefs.edit()
            .putString(KEY_LOCAL, toJson(local))
            .putString(KEY_SHARED, toJson(shared))
            .putString(KEY_REMOVED, JSONArray(removed).toString())
            .apply()
    }

    fun clearRemoved() {
        prefs.edit().remove(KEY_REMOVED).apply()
    }

    fun getServerUrl(): String =
        prefs.getString(KEY_SERVER, ShareApi.DEFAULT_URL) ?: ShareApi.DEFAULT_URL

    fun setServerUrl(url: String) {
        prefs.edit().putString(KEY_SERVER, url.trim()).apply()
    }

    private fun toJson(list: List<Account>): String =
        JSONArray().apply { list.forEach { put(Account.toJson(it)) } }.toString()

    private fun loadList(key: String): List<Account> = try {
        val arr = JSONArray(prefs.getString(key, "[]") ?: "[]")
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { Account.fromJson(it) }
        }
    } catch (e: Exception) {
        emptyList()
    }

    companion object {
        private const val KEY_LOCAL = "fb_local_v1"
        private const val KEY_SHARED = "fb_shared_v1"
        private const val KEY_REMOVED = "fb_removed_v1"
        private const val KEY_SERVER = "fb_server_url"
    }
}
