package com.example.learnapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var rv: RecyclerView
    private lateinit var adapter: BankAdapter
    private var sessionList: List<Session> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rv = findViewById(R.id.rvBanks)
        adapter = BankAdapter()
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        // 左滑删除
        val callback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder,
                                t: RecyclerView.ViewHolder) = false
            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {
                val pos = vh.adapterPosition
                if (pos in sessionList.indices) {
                    SessionManager.delete(this@MainActivity, sessionList[pos])
                    sessionList = SessionManager.listSessions(this@MainActivity)
                    adapter.notifyDataSetChanged()
                }
            }
            override fun getSwipeThreshold(vh: RecyclerView.ViewHolder) = 0.35f
        }
        ItemTouchHelper(callback).attachToRecyclerView(rv)

        findViewById<TextView>(R.id.btnTopMore).setOnClickListener { anchor ->
            PopupMenu(this, anchor).apply {
                menu.add(0, 1, 0, "选择题库目录")
                menu.add(0, 2, 1, "扫描新题库")
                setOnMenuItemClickListener {
                    when (it.itemId) {
                        1 -> showDirPicker()
                        2 -> { SessionManager.refresh(this@MainActivity); loadData() }
                    }
                    true
                }
                show()
            }
        }
    }

    override fun onResume() { super.onResume(); loadData() }

    private fun loadData() {
        sessionList = SessionManager.listSessions(this)
        adapter.notifyDataSetChanged()
        findViewById<View>(R.id.tvHint).visibility =
            if (sessionList.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showDirPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, 1001)
    }

    override fun onActivityResult(r: Int, code: Int, d: Intent?) {
        super.onActivityResult(r, code, d)
        if (r == 1001 && code == RESULT_OK) {
            val uri = d?.data ?: return
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            SessionManager.saveTreeUri(this, uri)
            SessionManager.refresh(this)
            loadData()
        }
    }

    inner class BankViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val content: View = v.findViewById(R.id.content)
        val avatar: ImageView = v.findViewById(R.id.ivAvatar)
        val title: TextView = v.findViewById(R.id.tvTitle)
        val subtitle: TextView = v.findViewById(R.id.tvSubtitle)
        val time: TextView = v.findViewById(R.id.tvTime)
    }

    inner class BankAdapter : RecyclerView.Adapter<BankViewHolder>() {
        override fun onCreateViewHolder(p: android.view.ViewGroup, t: Int): BankViewHolder {
            val v = layoutInflater.inflate(R.layout.item_bank, p, false)
            val h = BankViewHolder(v)
            h.content.setOnClickListener {
                val i = h.adapterPosition
                if (i in sessionList.indices) {
                    startActivity(Intent(this@MainActivity, QuestionListActivity::class.java)
                        .putExtra("key", sessionList[i].key))
                }
            }
            return h
        }
        override fun getItemCount() = sessionList.size
        override fun onBindViewHolder(h: BankViewHolder, pos: Int) {
            val s = sessionList[pos]
            h.title.text = s.key
            h.subtitle.text = s.statsText()
            h.time.text = timeSince(s)
            val seed = Math.abs(s.key.hashCode())
            h.avatar.setBackgroundColor(palette[seed % palette.size])
        }
    }

    companion object {
        private val palette = intArrayOf(
            0xFF07C160.toInt(), 0xFFF6A623.toInt(), 0xFF5C6BC0.toInt(),
            0xFF26C6DA.toInt(), 0xFFEF5350.toInt(), 0xFFAB47BC.toInt(),
            0xFF8D6E63.toInt(), 0xFF42A5F5.toInt()
        )
        private fun timeSince(s: Session): String {
            val f = java.io.File(s.progressFile.parent, "progress.json")
            val mtime = if (f.exists()) f.lastModified() else
                java.io.File(s.dir, "build/questions.jsonl").lastModified()
            val d = (System.currentTimeMillis() - mtime) / 1000L
            return when {
                d < 60 -> "刚刚"
                d < 3600 -> "${d / 60}分钟前"
                d < 86400 -> "${d / 3600}小时前"
                d < 86400 * 2 -> "昨天"
                d < 86400 * 7 -> "${d / 86400}天前"
                else -> SimpleDateFormat("M/d", Locale.getDefault()).format(Date(mtime))
            }
        }
    }
}
