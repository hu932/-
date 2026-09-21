package com.example.accountmanager

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AccountAdapter(
    private val onCopy: (Account, Int) -> Unit,
    private val onDelete: (Account, Int) -> Unit
) : RecyclerView.Adapter<AccountAdapter.VH>() {

    var items: List<Account> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val num: TextView = view.findViewById(R.id.num)
        val uid: TextView = view.findViewById(R.id.uid)
        val meta: TextView = view.findViewById(R.id.meta)
        val copyBtn: TextView = view.findViewById(R.id.copyBtn)
        val delBtn: TextView = view.findViewById(R.id.delBtn)
        val usage: View = view.findViewById(R.id.usage)
        val timer: TextView = view.findViewById(R.id.timer)
        val clock: TextView = view.findViewById(R.id.clock)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_account, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = items[position]
        holder.num.text = "#${position + 1}"
        holder.uid.text = r.uid.ifEmpty { "未识别" }

        val parts = mutableListOf("${r.cookies.size} 个 Cookie")
        if (r.region.isNotEmpty()) parts.add(r.region)
        if (r.usage.isNotEmpty()) parts.add("已用 ${r.usage.size} 次")
        holder.meta.text = parts.joinToString(" · ")

        if (r.usedAt != null) {
            holder.usage.visibility = View.VISIBLE
            val used = r.usedAt
            if (used != null) holder.clock.text = "(${fmtClock(used)} 起)"
        } else {
            holder.usage.visibility = View.GONE
        }

        holder.copyBtn.setOnClickListener { onCopy(r, position) }
        holder.delBtn.setOnClickListener { onDelete(r, position) }
        updateTimer(holder, r)
    }

    fun updateTimer(holder: VH, r: Account) {
        val used = r.usedAt
        if (used != null) {
            holder.usage.visibility = View.VISIBLE
            holder.timer.text = fmtDur(System.currentTimeMillis() - used)
        } else {
            holder.usage.visibility = View.GONE
        }
    }

    override fun getItemCount(): Int = items.size

    companion object {
        fun fmtDur(ms: Long): String {
            val s = maxOf(0, ms / 1000)
            val h = s / 3600
            val m = (s % 3600) / 60
            val sec = s % 60
            return if (h > 0) "%02d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
        }

        fun fmtClock(ts: Long): String =
            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
    }
}
