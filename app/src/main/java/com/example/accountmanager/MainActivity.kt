package com.example.accountmanager

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var source: EditText
    private lateinit var statusEl: TextView
    private lateinit var listTitle: TextView
    private lateinit var listInfo: TextView
    private lateinit var statTotal: TextView
    private lateinit var statUsed: TextView
    private lateinit var tabLocal: TextView
    private lateinit var tabShared: TextView
    private lateinit var shareStatus: TextView
    private lateinit var clearBtn: TextView
    private lateinit var buyBtn: TextView
    private lateinit var parseBtn: TextView
    private lateinit var fileBtn: TextView
    private lateinit var emptyView: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var dateBar: LinearLayout

    private var selectedDate: String? = null

    private lateinit var storage: Storage
    private lateinit var adapter: AccountAdapter

    private var tab = TAB_SHARED
    private val data = mutableMapOf(
        TAB_LOCAL to mutableListOf<Account>(),
        TAB_SHARED to mutableListOf<Account>()
    )
    private var removedKeys = mutableListOf<String>()

    private val exec = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var pushing = false
    private var pushAgain = false

    private val pushTask = Runnable { doPush() }
    private val parseDebounce = Runnable { doParse() }
    private val tickTask = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 1000L)
        }
    }
    private val pullTask = object : Runnable {
        override fun run() {
            pull(true)
            handler.postDelayed(this, 15000L)
        }
    }

    private val getFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) importFile(uri)
    }

    private fun cur(): MutableList<Account> = data.getValue(tab)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        source = findViewById(R.id.source)
        statusEl = findViewById(R.id.statusEl)
        listTitle = findViewById(R.id.listTitle)
        listInfo = findViewById(R.id.listInfo)
        statTotal = findViewById(R.id.statTotal)
        statUsed = findViewById(R.id.statUsed)
        tabLocal = findViewById(R.id.tabLocal)
        tabShared = findViewById(R.id.tabShared)
        shareStatus = findViewById(R.id.shareStatus)
        clearBtn = findViewById(R.id.clearBtn)
        buyBtn = findViewById(R.id.buyBtn)
        parseBtn = findViewById(R.id.parseBtn)
        fileBtn = findViewById(R.id.fileBtn)
        emptyView = findViewById(R.id.emptyView)
        recycler = findViewById(R.id.list)
        dateBar = findViewById(R.id.dateBar)

        storage = Storage(this)
        ShareApi.baseUrl = storage.getServerUrl()
        adapter = AccountAdapter(::copyAccount, ::deleteAccount)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        recycler.isNestedScrollingEnabled = false

        parseBtn.setOnClickListener { doParse() }
        fileBtn.setOnClickListener { getFile.launch("*/*") }
        clearBtn.setOnClickListener { confirmClear() }
        buyBtn.setOnClickListener { startActivity(Intent(this, PurchaseActivity::class.java)) }
        tabLocal.setOnClickListener { switchTab(TAB_LOCAL) }
        tabShared.setOnClickListener { switchTab(TAB_SHARED) }
        shareStatus.setOnClickListener {
            setShare("syncing", "同步中…")
            pull(false)
            doPush()
            toast("已同步")
        }
        shareStatus.setOnLongClickListener {
            editServerUrl()
            true
        }

        source.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                handler.removeCallbacks(parseDebounce)
                handler.postDelayed(parseDebounce, 500L)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        restore()
        render()
        setShare("syncing", "同步中…")
        doPush()
        pull(true)
        handler.postDelayed(pullTask, 15000L)
        handler.postDelayed(tickTask, 1000L)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        exec.shutdown()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // 从下单页返回时，读取它写入共享列表的新账号并同步
        val before = sig()
        data[TAB_SHARED] = storage.loadShared().toMutableList()
        if (sig() != before) {
            if (tab == TAB_SHARED) render()
            pushSoon()
        }
    }

    // ---------- render ----------

    private fun render() {
        styleTab(tabLocal, tab == TAB_LOCAL)
        styleTab(tabShared, tab == TAB_SHARED)
        tabLocal.text = "本地 " + data.getValue(TAB_LOCAL).size
        tabShared.text = "共享 " + data.getValue(TAB_SHARED).size

        val recs = cur()
        statTotal.text = recs.size.toString()
        statUsed.text = recs.count { it.usedAt != null }.toString()

        buildDateChips()

        val shown = filtered()
        listInfo.text = shown.size.toString() + " 个"
        listTitle.text = if (tab == TAB_LOCAL) "本地账号（只存这台设备）" else "共享账号（打开这页的所有人都能看到）"

        val empty = shown.isEmpty()
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        recycler.visibility = if (empty) View.GONE else View.VISIBLE
        if (empty) {
            emptyView.text = when {
                selectedDate != null -> "该日期没有账号"
                tab == TAB_LOCAL -> "本地还没有账号，粘贴数据后点「解析」"
                else -> "共享里还没有账号，粘贴数据后点「解析」"
            }
        }

        adapter.items = shown
        tick()
    }

    private fun dateKey(ts: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ts))

    private fun datesOf(): List<String> =
        cur().map { dateKey(it.createdAt) }.distinct().sortedDescending()

    private fun filtered(): List<Account> {
        val sel = selectedDate ?: return cur()
        return cur().filter { dateKey(it.createdAt) == sel }
    }

    private fun chipLabel(d: String): String {
        val today = dateKey(System.currentTimeMillis())
        val yesterday = dateKey(System.currentTimeMillis() - 86400000L)
        return when (d) {
            today -> "今天"
            yesterday -> "昨天"
            else -> if (d.length >= 10) d.substring(5) else d
        }
    }

    private fun buildDateChips() {
        dateBar.removeAllViews()
        val dates = datesOf()
        if (selectedDate != null && selectedDate !in dates) selectedDate = null

        dateBar.addView(makeChip("全部", selectedDate == null) { selectedDate = null; render() })
        for (d in dates) {
            val count = cur().count { dateKey(it.createdAt) == d }
            dateBar.addView(makeChip(chipLabel(d) + " " + count, selectedDate == d) { selectedDate = d; render() })
        }
    }

    private fun makeChip(text: String, on: Boolean, click: () -> Unit): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 12.5f
        tv.gravity = android.view.Gravity.CENTER
        tv.setTextColor(if (on) Color.WHITE else Color.parseColor("#6b7480"))
        tv.setBackgroundResource(if (on) R.drawable.bg_chip_on else R.drawable.bg_chip)
        val pad = dp(10)
        tv.setPadding(pad, dp(6), pad, dp(6))
        tv.setOnClickListener { click() }
        tv.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(8) }
        return tv
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun styleTab(v: TextView, on: Boolean) {
        if (on) {
            v.setBackgroundResource(R.drawable.bg_tab_on)
            v.setTextColor(Color.parseColor("#2f6bff"))
        } else {
            v.setBackgroundColor(Color.TRANSPARENT)
            v.setTextColor(Color.parseColor("#6b7480"))
        }
    }

    private fun tick() {
        val recs = filtered()
        for (i in 0 until recycler.childCount) {
            val vh = recycler.getChildViewHolder(recycler.getChildAt(i)) as? AccountAdapter.VH ?: continue
            val pos = vh.adapterPosition
            if (pos in recs.indices) adapter.updateTimer(vh, recs[pos])
        }
    }

    // ---------- parse ----------

    private fun doParse() {
        val raw = source.text.toString().trim()
        if (raw.isEmpty()) {
            setStatus("请先粘贴内容", null)
            return
        }
        val res = Parser.parse(raw)

        val old = cur().associateBy { Account.keyOf(it) }
        res.accounts.forEach { a ->
            val o = old[Account.keyOf(a)]
            if (o != null) {
                a.usedAt = o.usedAt // 保留使用计时
                a.createdAt = o.createdAt // 保留首次入库日期
                a.usage = o.usage.toMutableList() // 保留使用历史
            }
            a.updatedAt = System.currentTimeMillis() // 重新粘贴视为最新
        }

        if (tab == TAB_LOCAL) {
            data[TAB_LOCAL] = res.accounts.toMutableList()
        } else {
            val map = data.getValue(TAB_SHARED).associateBy { Account.keyOf(it) }.toMutableMap()
            res.accounts.forEach { map[Account.keyOf(it)] = it }
            data[TAB_SHARED] = map.values.toMutableList()
        }

        render()
        saveAll()
        if (tab == TAB_SHARED) pushSoon()

        when {
            res.accounts.isNotEmpty() ->
                setStatus("已识别 ${res.accounts.size} 个账号${if (res.skip > 0) "，跳过 ${res.skip} 段无效" else ""}", true)
            res.skip > 0 -> setStatus("未识别到有效账号", false)
            else -> setStatus("未识别到账号", false)
        }
    }

    // ---------- storage ----------

    private fun saveAll() {
        storage.save(data.getValue(TAB_LOCAL), data.getValue(TAB_SHARED), removedKeys)
    }

    private fun restore() {
        data[TAB_LOCAL] = storage.loadLocal().toMutableList()
        data[TAB_SHARED] = storage.loadShared().toMutableList()
        removedKeys = storage.loadRemoved().toMutableList()
    }

    // ---------- share ----------

    private fun sig(): String =
        data.getValue(TAB_SHARED).joinToString("|") { "${Account.keyOf(it)}:${it.updatedAt}:${it.usedAt ?: 0}" }

    private fun adoptShared(list: List<Account>) {
        data[TAB_SHARED] = list
            .filter { it.cookies.isNotEmpty() }
            .map {
                Account(
                    id = it.id.ifEmpty { Account.newId() },
                    uid = it.uid,
                    cookies = it.cookies,
                    ua = it.ua,
                    region = it.region,
                    usedAt = it.usedAt,
                    updatedAt = it.updatedAt,
                    createdAt = it.createdAt,
                    usage = it.usage.toMutableList()
                )
            }
            .toMutableList()
    }

    private fun setShare(state: String?, text: String) {
        val color = when (state) {
            "ok" -> Color.parseColor("#12a66a")
            "err" -> Color.parseColor("#e5484d")
            "syncing" -> Color.parseColor("#2f6bff")
            else -> Color.parseColor("#8a94a3")
        }
        shareStatus.setTextColor(color)
        shareStatus.text = "● $text"
    }

    private fun timeLabel(updatedAt: String): String =
        if (updatedAt.length > 16) updatedAt.substring(11, 16) else updatedAt

    private fun pull(quiet: Boolean) {
        exec.execute {
            try {
                if (!quiet) runOnUiThread { setShare("syncing", "同步中…") }
                val r = ShareApi.call(JSONObject().put("act", "list"))
                if (!r.optBoolean("ok")) throw ShareApi.ApiException(r.optString("msg").ifEmpty { "返回异常" })

                val arr = r.optJSONArray("data") ?: JSONArray()
                val list = (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { Account.fromJson(it) }
                }

                runOnUiThread {
                    adoptShared(list)
                    saveAll()
                    if (tab == TAB_SHARED) render()
                    setShare("ok", "已同步 " + timeLabel(r.optString("updated_at", "")))
                }
            } catch (e: Exception) {
                runOnUiThread { setShare("err", "读取失败：" + failMsg(e)) }
            }
        }
    }

    private fun pushSoon() {
        handler.removeCallbacks(pushTask)
        handler.postDelayed(pushTask, 700L)
    }

    private fun doPush() {
        if (pushing) {
            pushAgain = true
            return
        }
        pushing = true
        setShare("syncing", "上传中…")

        // 在主线程快照，避免后台线程读写 data 产生竞态
        val removed = removedKeys.filter { k -> data.getValue(TAB_SHARED).none { Account.keyOf(it) == k } }.toList()
        val accounts = data.getValue(TAB_SHARED).toList()

        exec.execute {
            try {
                val payload = JSONArray()
                accounts.forEach { a ->
                    payload.put(
                        JSONObject().apply {
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
                    )
                }
                val r = ShareApi.call(
                    JSONObject().apply {
                        put("act", "sync")
                        put("accounts", payload)
                        put("removed", JSONArray(removed))
                    }
                )
                if (!r.optBoolean("ok")) throw ShareApi.ApiException(r.optString("msg").ifEmpty { "返回异常" })

                runOnUiThread {
                    removedKeys.clear()
                    storage.clearRemoved()
                    val arr = r.optJSONArray("data") ?: JSONArray()
                    adoptShared((0 until arr.length()).mapNotNull { i ->
                        arr.optJSONObject(i)?.let { Account.fromJson(it) }
                    })
                    saveAll()
                    if (tab == TAB_SHARED) render()
                    setShare("ok", "已同步 " + timeLabel(r.optString("updated_at", "")))
                }
            } catch (e: Exception) {
                runOnUiThread { setShare("err", "上传失败：" + failMsg(e)) }
            } finally {
                pushing = false
                if (pushAgain) {
                    pushAgain = false
                    handler.post { doPush() }
                }
            }
        }
    }

    private fun failMsg(e: Exception): String =
        if (e is ShareApi.ApiException) e.message ?: "网络错误" else e.message ?: "网络错误"

    // ---------- actions ----------

    private fun switchTab(t: String) {
        if (t == tab) return
        tab = t
        render()
        if (tab == TAB_SHARED) pull(true)
    }

    private fun confirmClear() {
        val recs = cur()
        if (recs.isEmpty()) {
            toast("已经是空的")
            return
        }
        val isShared = tab == TAB_SHARED
        val msg = if (isShared)
            "确定清空共享里的 ${recs.size} 个账号？\n（服务器上的也会一起清空）"
        else
            "确定清空本地的 ${recs.size} 个账号？"

        AlertDialog.Builder(this)
            .setMessage(msg)
            .setPositiveButton("清空") { _, _ -> doClear(isShared) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doClear(isShared: Boolean) {
        source.setText("")
        if (isShared) {
            val recs = cur()
            recs.forEach { removedKeys.add(Account.keyOf(it)) }
            data[TAB_SHARED] = mutableListOf()
            render()
            saveAll()

            exec.execute {
                try {
                    val r = ShareApi.call(JSONObject().put("act", "clear"))
                    if (r.optBoolean("ok")) {
                        runOnUiThread {
                            val arr = r.optJSONArray("data") ?: JSONArray()
                            adoptShared((0 until arr.length()).mapNotNull { i ->
                                arr.optJSONObject(i)?.let { Account.fromJson(it) }
                            })
                            saveAll()
                            render()
                        }
                    }
                    runOnUiThread { setShare("ok", "已清空") }
                } catch (e: Exception) {
                    runOnUiThread { setShare("err", "清空失败：" + failMsg(e)) }
                }
            }
        } else {
            data[TAB_LOCAL] = mutableListOf()
            render()
            saveAll()
        }
        setStatus("已清空", true)
        toast("已清空")
    }

    private fun copyAccount(r: Account, i: Int) {
        // 列表可能被同步替换成新对象，这里按 key 找到当前真实对象再修改，避免改了旧对象
        val live = cur().firstOrNull { Account.keyOf(it) == Account.keyOf(r) } ?: r

        val text = JSONArray().apply { live.cookies.forEach { put(it) } }.toString()
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("cookies", text))
        } catch (e: Exception) {
            // 复制失败也不影响计时
        }

        val now = System.currentTimeMillis()
        live.usedAt = now
        live.updatedAt = now
        live.usage.add(now)

        render()
        saveAll()
        if (tab == TAB_SHARED) pushSoon()

        setStatus("已复制 ${live.uid.ifEmpty { "#${i + 1}" }}，开始计时", true)
        toast("已复制，开始计时 ⏱")
    }

    private fun deleteAccount(r: Account, i: Int) {
        AlertDialog.Builder(this)
            .setMessage("删除账号 ${r.uid.ifEmpty { "#${i + 1}" }} ？")
            .setPositiveButton("删除") { _, _ ->
                val recs = cur()
                val realIdx = recs.indexOfFirst { Account.keyOf(it) == Account.keyOf(r) }
                if (realIdx >= 0) recs.removeAt(realIdx)
                if (tab == TAB_SHARED) {
                    removedKeys.add(Account.keyOf(r))
                    pushSoon()
                }
                render()
                saveAll()
                toast("已删除")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun importFile(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return
            source.setText(text)
            doParse()
        } catch (e: Exception) {
            setStatus("文件读取失败", false)
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
                toast("已保存，开始同步")
                setShare("syncing", "同步中…")
                doPush()
                pull(true)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun setStatus(msg: String, ok: Boolean?) {
        statusEl.text = msg
        statusEl.setTextColor(
            when (ok) {
                true -> Color.parseColor("#12a66a")
                false -> Color.parseColor("#e5484d")
                else -> Color.parseColor("#8a94a3")
            }
        )
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val TAB_LOCAL = "local"
        const val TAB_SHARED = "shared"
    }
}
