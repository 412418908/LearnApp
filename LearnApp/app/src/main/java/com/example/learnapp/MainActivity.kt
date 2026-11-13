package com.example.learnapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var rv: RecyclerView
    private var sessions: List<Session> = emptyList()

    private val pickTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            SessionManager.saveTreeUri(this, uri)
            refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        rv = findViewById(R.id.rvSessions)
        rv.layoutManager = LinearLayoutManager(this)
        findViewById<View>(R.id.btnRefresh).setOnClickListener { refresh() }
        if (SessionManager.treeUri(this) == null) {
            Toast.makeText(this, "请选择 SD 卡 Downloads 下的 LearnApp 目录", Toast.LENGTH_LONG).show()
            pickTree.launch(null)
        }
        refresh()
    }

    private fun refresh() {
        Thread {
            sessions = SessionManager.refresh(this)
            runOnUiThread {
                if (sessions.isEmpty()) {
                    Toast.makeText(this, "未发现题库 zip（目录：Download/LearnApp）", Toast.LENGTH_SHORT).show()
                }
                rv.adapter = SessionAdapter()
            }
        }.start()
    }

    inner class SessionAdapter : RecyclerView.Adapter<SessionAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.tvTitle)
            val desc: TextView = v.findViewById(R.id.tvDesc)
            val avatar: TextView = v.findViewById(R.id.tvAvatar)
            val delete: ImageView = v.findViewById(R.id.btnDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(layoutInflater.inflate(R.layout.item_session, parent, false))

        override fun getItemCount() = sessions.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val s = sessions[pos]
            h.title.text = s.key
            h.desc.text = s.statsText()
            h.avatar.text = s.key.take(1)
            h.itemView.setOnClickListener {
                startActivity(Intent(this@MainActivity, ChatActivity::class.java)
                    .putExtra("key", s.key))
            }
            h.delete.setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("删除会话")
                    .setMessage("删除「${s.key}」及其答题记录？")
                    .setPositiveButton("删除") { _, _ ->
                        SessionManager.delete(this@MainActivity, s)
                        refresh()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }
}
