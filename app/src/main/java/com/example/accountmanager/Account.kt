package com.example.accountmanager

import org.json.JSONArray
import org.json.JSONObject

/**
 * 账号数据模型，对应原网页版的 account 记录。
 * cookies 保留原始 JSONObject，确保上传 / 复制时字段不丢失。
 */
data class Account(
    val id: String,
    val uid: String,
    val cookies: List<JSONObject>,
    val ua: String,
    val region: String,
    var usedAt: Long?,
    var updatedAt: Long,
    var createdAt: Long,
    var usage: MutableList<Long>
) {
    companion object {
        fun newId(): String =
            "a" + System.currentTimeMillis().toString(36) +
                (Math.random() * 16777216).toInt().toString(36)

        fun keyOf(a: Account): String =
            if (a.uid.isNotEmpty()) "u:${a.uid}" else "i:${a.id}"

        fun toJson(a: Account): JSONObject = JSONObject().apply {
            put("id", a.id)
            put("uid", a.uid)
            put("cookies", JSONArray().apply { a.cookies.forEach { put(it) } })
            put("ua", a.ua)
            put("region", a.region)
            if (a.usedAt != null) put("usedAt", a.usedAt) else put("usedAt", JSONObject.NULL)
            put("updatedAt", a.updatedAt)
            put("createdAt", a.createdAt)
            put("usage", JSONArray(a.usage))
        }

        fun fromJson(o: JSONObject): Account {
            val arr = o.optJSONArray("cookies")
            val cookies = mutableListOf<JSONObject>()
            for (i in 0 until (arr?.length() ?: 0)) {
                arr?.optJSONObject(i)?.let { cookies.add(it) }
            }
            val usedAt = if (o.has("usedAt") && !o.isNull("usedAt")) o.optLong("usedAt") else null
            val usageArr = o.optJSONArray("usage")
            val usage = mutableListOf<Long>()
            for (i in 0 until (usageArr?.length() ?: 0)) {
                val v = usageArr?.optLong(i) ?: 0L
                if (v > 0) usage.add(v)
            }
            return Account(
                id = o.optString("id").ifEmpty { newId() },
                uid = o.optString("uid"),
                cookies = cookies,
                ua = o.optString("ua"),
                region = o.optString("region"),
                usedAt = usedAt,
                updatedAt = o.optLong("updatedAt"),
                createdAt = o.optLong("createdAt").takeIf { it > 0 } ?: System.currentTimeMillis(),
                usage = usage
            )
        }
    }
}
