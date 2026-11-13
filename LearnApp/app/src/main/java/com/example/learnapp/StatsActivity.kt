package com.example.learnapp

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class StatsActivity : AppCompatActivity() {

    private lateinit var session: Session

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stats)
        val key = intent.getStringExtra("key") ?: run { finish(); return }
        session = SessionManager.listSessions(this).firstOrNull { it.key == key } ?: run { finish(); return }

        findViewById<TextView>(R.id.tvSummary).text = "「${session.key}」\n${session.statsText()}"
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        val rv = findViewById<RecyclerView>(R.id.rvStats)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object :
                RecyclerView.ViewHolder(layoutInflater.inflate(R.layout.item_stat, parent, false)) {}

            override fun getItemCount() = session.questions.size

            override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
                val q = session.questions[pos]
                val s = session.status[q.id]
                val state = when {
                    s == null -> "未答"
                    s.archived -> "通过"
                    else -> "未通过(${s.errorCount}错)"
                }
                h.itemView.findViewById<TextView>(R.id.tvStatLine).text =
                    "${pos + 1}. [${q.subject}/${q.section}] 第${q.number}题 — $state"
            }
        }
    }
}
