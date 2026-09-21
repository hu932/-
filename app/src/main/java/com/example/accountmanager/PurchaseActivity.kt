package com.example.accountmanager

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * 内嵌下单页：加载商城，支持跳转到支付宝 App 完成支付。
 * 付款后自动解析卡密：
 *  - 点「下载卡密」（.txt）会自动拦截下载并解析入库；
 *  - 查看卡密页可点顶部「抓取卡密」自动提取本页内容解析；
 *  - 进入订单/卡密相关页面时也会自动尝试提取。
 * 注意：支付确认需要用户本人在支付宝里操作，App 无法代付。
 */
class PurchaseActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var storage: Storage
    private val exec = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_purchase)

        web = findViewById(R.id.web)
        storage = Storage(this)

        findViewById<TextView>(R.id.backBtn).setOnClickListener { finish() }
        findViewById<TextView>(R.id.grabBtn).setOnClickListener { grabPage() }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            // 移动端 UA，便于正常显示下单与支付二维码页
            userAgentString =
                "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                routeUrl(view, request.url.toString())

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                routeUrl(view, url)

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                // 卡密 / 订单相关页面自动尝试提取
                if (Regex("kami|card|order|view|query|select|buy", RegexOption.IGNORE_CASE)
                        .containsMatchIn(url)
                ) {
                    grabPage()
                }
            }
        }

        // 拦截 .txt 卡密下载：下载后自动读取内容并解析
        web.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            if (url.endsWith(".txt", true) || mimetype?.contains("text", true) == true) {
                exec.execute { downloadAndParse(url) }
            } else {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    // ignore
                }
            }
        }

        web.loadUrl(PURCHASE_URL)
    }

    private fun routeUrl(view: WebView, url: String): Boolean {
        val uri = Uri.parse(url)
        val scheme = uri.scheme

        // alipays:// / alipay:// / weixin:// / intent:// 等第三方 App 跳转
        if (scheme != null && scheme !in setOf("http", "https", "javascript", "about", "data")) {
            return try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
                true
            } catch (e: Exception) {
                false
            }
        }

        if (url.startsWith("http")) {
            val host = uri.host
            if (host != null && (host == "chuyu123.com" || host.endsWith(".chuyu123.com"))) {
                view.loadUrl(url)
            } else {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (e: Exception) {
                    // ignore
                }
            }
            return true
        }
        return false
    }

    /** 抓取当前页面可见文本并尝试解析卡密。 */
    private fun grabPage() {
        web.evaluateJavascript(
            "(function(){return document.body ? document.body.innerText : ''})()"
        ) { value ->
            ingestCardKeys(unquoteJs(value))
        }
    }

    private fun unquoteJs(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        return try {
            JSONTokener(value).nextValue() as? String ?: ""
        } catch (e: Exception) {
            value
        }
    }

    private fun downloadAndParse(url: String) {
        try {
            val text = fetchText(url)
            handler.post { ingestCardKeys(text) }
        } catch (e: Exception) {
            handler.post { toast("下载卡密失败：${e.message}") }
        }
    }

    private fun fetchText(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 20000
            conn.readTimeout = 20000
            val cookie = CookieManager.getInstance().getCookie(url)
            if (!cookie.isNullOrEmpty()) conn.setRequestProperty("Cookie", cookie)
            conn.setRequestProperty("Accept", "text/plain,*/*")
            return conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** 解析卡密文本并合并进「共享」列表（返回主界面时自动同步）。 */
    private fun ingestCardKeys(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val res = Parser.parse(trimmed)
        if (res.accounts.isEmpty()) {
            toast("未识别到卡密内容")
            return
        }
        val shared = storage.loadShared().toMutableList()
        val map = shared.associateBy { Account.keyOf(it) }.toMutableMap()
        res.accounts.forEach { a ->
            val o = map[Account.keyOf(a)]
            if (o != null) {
                a.usedAt = o.usedAt
                a.createdAt = o.createdAt
                a.usage = o.usage.toMutableList()
            }
            a.updatedAt = System.currentTimeMillis()
            map[Account.keyOf(a)] = a
        }
        storage.save(storage.loadLocal(), map.values.toList(), storage.loadRemoved())
        toast("已解析 ${res.accounts.size} 个账号，已加入共享")
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        exec.shutdown()
        super.onDestroy()
    }

    companion object {
        const val PURCHASE_URL = "https://chuyu123.com/jingdian/index/zywlpay.html"
    }
}
