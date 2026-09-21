package com.example.accountmanager

import android.content.Context
import org.json.JSONArray

/**
 * 本地持久化：按登录用户隔离（local / shared / removed 各自按用户名分键）。
 * 不同账号登录，读取/写入的数据互不干扰。
 */
class Storage(context: Context) {

    private val prefs = context.getSharedPreferences("fb_accounts", Context.MODE_PRIVATE)

    // ---------- 登录态 ----------
    fun username(): String = prefs.getString(KEY_USER, "") ?: ""

    fun token(): String = prefs.getString(KEY_TOKEN, "") ?: ""

    fun isLoggedIn(): Boolean = token().isNotEmpty()

    fun setAuth(user: String, tok: String) {
        prefs.edit().putString(KEY_USER, user).putString(KEY_TOKEN, tok).apply()
    }

    fun clearAuth() {
        prefs.edit().remove(KEY_USER).remove(KEY_TOKEN).apply()
    }

    // ---------- 数据（按用户隔离） ----------
    private fun k(base: String): String {
        val u = username()
        return if (u.isEmpty()) base else "$base@$u"
    }

    fun loadLocal(): List<Account> = loadList(k(KEY_LOCAL))

    fun loadShared(): List<Account> = loadList(k(KEY_SHARED))

    fun loadRemoved(): List<String> = try {
        val arr = JSONArray(prefs.getString(k(KEY_REMOVED), "[]") ?: "[]")
        (0 until arr.length()).map { arr.getString(it) }
    } catch (e: Exception) {
        emptyList()
    }

    fun save(local: List<Account>, shared: List<Account>, removed: List<String>) {
        prefs.edit()
            .putString(k(KEY_LOCAL), toJson(local))
            .putString(k(KEY_SHARED), toJson(shared))
            .putString(k(KEY_REMOVED), JSONArray(removed).toString())
            .apply()
    }

    fun clearRemoved() {
        prefs.edit().remove(k(KEY_REMOVED)).apply()
    }

    // ---------- 接口地址（全局，不按用户） ----------
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
        private const val KEY_USER = "fb_user"
        private const val KEY_TOKEN = "fb_token"
        private const val KEY_SERVER = "fb_server_url"
        private const val KEY_LOCAL = "fb_local_v1"
        private const val KEY_SHARED = "fb_shared_v1"
        private const val KEY_REMOVED = "fb_removed_v1"
    }
}
