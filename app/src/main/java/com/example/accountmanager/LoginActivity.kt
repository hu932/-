package com.example.accountmanager

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.util.concurrent.Executors

/** 注册 / 登录界面。登录成功后进入主界面，不同账号数据互不干扰。 */
class LoginActivity : AppCompatActivity() {

    private lateinit var usernameInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var statusText: TextView
    private lateinit var serverHint: TextView
    private lateinit var rememberCheck: CheckBox
    private lateinit var storage: Storage

    private val exec = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        storage = Storage(this)
        ShareApi.baseUrl = storage.getServerUrl()

        // 自动登录：已记住登录态则直接进主界面
        if (storage.isLoggedIn()) {
            ShareApi.token = storage.token()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        ShareApi.token = ""

        setContentView(R.layout.activity_login)

        usernameInput = findViewById(R.id.username)
        passwordInput = findViewById(R.id.password)
        statusText = findViewById(R.id.loginStatus)
        serverHint = findViewById(R.id.serverHint)
        rememberCheck = findViewById(R.id.rememberCheck)

        findViewById<TextView>(R.id.loginBtn).setOnClickListener { doAuth(false) }
        findViewById<TextView>(R.id.regBtn).setOnClickListener { doAuth(true) }
        serverHint.setOnClickListener { editServerUrl() }
        serverHint.text = "接口：" + ShareApi.baseUrl + "（点此修改）"
    }

    private fun doAuth(register: Boolean) {
        val u = usernameInput.text.toString().trim()
        val p = passwordInput.text.toString()
        if (u.isEmpty() || p.isEmpty()) {
            setStatus("请输入用户名和密码", false)
            return
        }
        setStatus(if (register) "注册中…" else "登录中…", null)

        exec.execute {
            try {
                val r = ShareApi.call(
                    JSONObject().apply {
                        put("act", if (register) "register" else "login")
                        put("username", u)
                        put("password", p)
                    }
                )
                if (!r.optBoolean("ok")) throw ShareApi.ApiException(r.optString("msg").ifEmpty { "操作失败" })
                val data = r.optJSONObject("data")
                val token = data?.optString("token") ?: ""
                val name = data?.optString("username") ?: u

                handler.post {
                    if (token.isEmpty()) {
                        setStatus("返回异常：未获取到 token", false)
                        return@post
                    }
                    ShareApi.token = token
                    if (rememberCheck.isChecked) {
                        storage.setAuth(name, token) // 记住登录：下次自动登录
                    } else {
                        storage.clearAuth() // 本次会话有效，退出后需重新登录
                    }
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }
            } catch (e: Exception) {
                handler.post { setStatus(failMsg(e), false) }
            }
        }
    }

    private fun editServerUrl() {
        val input = EditText(this)
        input.setText(ShareApi.baseUrl)
        input.setSelection(input.text.length)
        AlertDialog.Builder(this)
            .setTitle("共享接口地址")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isEmpty()) {
                    toast("地址不能为空")
                    return@setPositiveButton
                }
                storage.setServerUrl(url)
                ShareApi.baseUrl = url
                serverHint.text = "接口：" + url + "（点此修改）"
                toast("已保存")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun setStatus(msg: String, ok: Boolean?) {
        statusText.text = msg
        statusText.setTextColor(
            when (ok) {
                true -> Color.parseColor("#12a66a")
                false -> Color.parseColor("#e5484d")
                else -> Color.parseColor("#8a94a3")
            }
        )
    }

    private fun failMsg(e: Exception): String =
        if (e is ShareApi.ApiException) e.message ?: "网络错误" else e.message ?: "网络错误"

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        exec.shutdown()
        super.onDestroy()
    }
}
