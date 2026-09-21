package com.example.accountmanager

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.math.max

data class ParseResult(val accounts: List<Account>, val skip: Int)

/**
 * 解析粘贴文本中的 JSON Cookie 数组，逻辑与网页版 parse() 一一对应。
 */
object Parser {

    fun parse(text: String): ParseResult {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val found = scan(normalized)
        val out = mutableListOf<Account>()
        var skip = 0

        for (f in found) {
            val cookies = toCookies(tryParse(normalized.substring(f.first, f.last + 1)))
            if (cookies == null) {
                skip++
                continue
            }

            val ls = normalized.lastIndexOf('\n', f.first - 1) + 1
            val nlIdx = normalized.indexOf('\n', f.last + 1)
            val le = if (nlIdx >= 0) nlIdx else normalized.length
            val line = normalized.substring(ls, le)
            val relS = f.first - ls
            val relE = f.last - ls

            // 按 tab 切列，找到 JSON 所在列
            val segs = mutableListOf<Seg>()
            var cstart = 0
            for (part in line.split('\t')) {
                segs.add(Seg(cstart, cstart + part.length, part))
                cstart += part.length + 1
            }
            var idx = -1
            for ((k, seg) in segs.withIndex()) {
                if (seg.start <= relS && relE <= max(seg.end, seg.start)) {
                    idx = k
                    break
                }
            }
            val pre = if (idx > 0) segs.subList(0, idx).map { it.txt.trim() }.filter { it.isNotEmpty() } else emptyList()
            val post = if (idx >= 0) segs.subList(idx + 1, segs.size).map { it.txt.trim() }.filter { it.isNotEmpty() } else emptyList()

            val cu = cookies.firstOrNull { it.optString("name").lowercase() == "c_user" }
            var uid = cu?.optString("value")?.trim() ?: ""
            if (uid.isEmpty() && pre.isNotEmpty()) uid = pre[0]

            var ua = ""
            var region = ""
            if (post.size >= 2) {
                region = post.last()
                ua = post.dropLast(1).joinToString(" ")
            } else if (post.size == 1) {
                val p = post[0]
                if (Regex("mozilla|applewebkit|android|chrome|windows|iphone", RegexOption.IGNORE_CASE)
                        .containsMatchIn(p)
                ) ua = p else region = p
            }

            out.add(
                Account(
                    id = Account.newId(),
                    uid = uid,
                    cookies = cookies,
                    ua = ua,
                    region = region,
                    usedAt = null,
                    updatedAt = System.currentTimeMillis(),
                    createdAt = System.currentTimeMillis(),
                    usage = mutableListOf()
                )
            )
        }

        // 若无 JSON 数组，则按原始 cookie 字符串（name=value; name=value...）解析卡密
        if (out.isEmpty()) {
            for (line in normalized.split('\n')) {
                val cookies = parseCookieHeader(line) ?: continue
                val cu = cookies.firstOrNull { it.optString("name").lowercase() == "c_user" }
                out.add(
                    Account(
                        id = Account.newId(),
                        uid = cu?.optString("value")?.trim() ?: "",
                        cookies = cookies,
                        ua = "",
                        region = "",
                        usedAt = null,
                        updatedAt = System.currentTimeMillis(),
                        createdAt = System.currentTimeMillis(),
                        usage = mutableListOf()
                    )
                )
            }
        }
        return ParseResult(out, skip)
    }

    /** 解析一行原始 cookie 头（name=value; name2=value2），返回 Cookie 数组。 */
    private fun parseCookieHeader(line: String): List<JSONObject>? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || !trimmed.contains('=')) return null
        val cookies = trimmed.split(';')
            .map { it.trim() }
            .filter { it.contains('=') }
            .mapNotNull { seg ->
                val idx = seg.indexOf('=')
                if (idx <= 0) return@mapNotNull null
                JSONObject().apply {
                    put("name", seg.substring(0, idx).trim())
                    put("value", seg.substring(idx + 1).trim())
                    put("domain", ".facebook.com")
                    put("path", "/")
                }
            }
        // 至少两对 name=value，避免把页面里的零散文本误判成卡密
        return if (cookies.size >= 2) cookies else null
    }

    private data class Seg(val start: Int, val end: Int, val txt: String)

    /** 扫描所有顶层 [ ... ] 区间，跳过字符串内的括号。 */
    private fun scan(text: String): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var s = -1
        var d = 0
        var q = false
        var esc = false
        for (i in text.indices) {
            val c = text[i]
            if (q) {
                if (esc) esc = false
                else if (c == '\\') esc = true
                else if (c == '"') q = false
                continue
            }
            if (c == '"') q = true
            else if (c == '[') {
                if (d == 0) s = i
                d++
            } else if (c == ']') {
                d--
                if (d == 0 && s >= 0) {
                    out.add(s..i)
                    s = -1
                }
            }
        }
        return out
    }

    private fun tryParse(raw: String): Any? {
        val candidates = mutableListOf(raw)
        if (raw.contains("\\_")) candidates.add(raw.replace("\\_", "_"))
        for (c in candidates) {
            try {
                return JSONTokener(c).nextValue()
            } catch (e: Exception) {
                // ignore
            }
        }
        return null
    }

    private fun isCookie(o: Any?): Boolean =
        o is JSONObject && (o.has("name") || o.has("value") || o.has("domain"))

    private fun toCookies(v: Any?): List<JSONObject>? {
        if (v !is JSONArray) return null
        val n = v.length()
        if (n == 0) return null

        if ((0 until n).all { isCookie(v.opt(it)) }) {
            return (0 until n).map { v.getJSONObject(it) }
        }
        if ((0 until n).all { i ->
                val x = v.opt(i)
                x is JSONArray && x.length() > 0 && (0 until x.length()).all { isCookie(x.opt(it)) }
            }
        ) {
            val flat = mutableListOf<JSONObject>()
            for (i in 0 until n) {
                val x = v.getJSONArray(i)
                for (j in 0 until x.length()) flat.add(x.getJSONObject(j))
            }
            return flat
        }
        return null
    }
}
