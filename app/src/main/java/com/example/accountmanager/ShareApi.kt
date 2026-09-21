package com.example.accountmanager

import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.StandardCharsets

/** 共享接口客户端，POST JSON，对应网页版的 api()。 */
object ShareApi {

    /** 部署后的共享接口地址，也可在 App 里长按顶部同步状态修改。 */
    const val DEFAULT_URL = "http://103.146.231.72:2525/accounts_api.php"

    var baseUrl: String = DEFAULT_URL

    /** 登录后由 LoginActivity / MainActivity 写入，数据接口请求会自动带上。 */
    var token: String = ""

    class ApiException(message: String, val timeout: Boolean = false) : Exception(message)

    fun call(body: JSONObject, timeoutMs: Int = 20000): JSONObject {
        if (token.isNotEmpty()) body.put("token", token)
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(baseUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")

            conn.outputStream.use { os ->
                OutputStreamWriter(os, StandardCharsets.UTF_8).use { it.write(body.toString()) }
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
            if (text.isBlank()) throw ApiException("空响应 (HTTP $code)")
            JSONObject(text)
        } catch (e: ApiException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw ApiException("超时", true)
        } catch (e: Exception) {
            throw ApiException(e.message ?: "网络错误")
        } finally {
            conn?.disconnect()
        }
    }
}
