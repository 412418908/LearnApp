package com.example.learnapp

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** 题库下的题目列表页，点击进入聊天刷题 */
class QuestionListActivity : AppCompatActivity() {

    private lateinit var rv: RecyclerView
    private lateinit var adapter: QuestionAdapter
    private lateinit var session: Session
    // 当前列表里的题目（会被删除/后续 修改）
    private var visibleQuestions: MutableList<Question> = mutableListOf()
    private val swipeOpen = mutableMapOf<Int, Int>()  // adapter position -> -1 (none) / 0 (swipe open)
    private var actionAnchoredPos = -1                 // 哪一行被左滑露出按钮

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_questions)
        val key = intent.getStringExtra("key") ?: run { finish(); return }
        session = SessionManager.listSessions(this).firstOrNull { it.key == key }
            ?: run { finish(); return }

        rv = findViewById(R.id.rvQuestions)
        adapter = QuestionAdapter()
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        findViewById<TextView>(R.id.tvTitle).text = session.key
        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btnRight).setOnClickListener {
            startActivity(Intent(this, ChatActivity::class.java).putExtra("key", session.key))
        }
    }

    override fun onResume() {
        super.onResume()
        session.loadProgress()   // 每次进来刷新状态
        visibleQuestions = session.questions.filter { !session.statusOf(it.id).archived &&
                !session.statusOf(it.id).removed }.toMutableList()
        adapter.notifyDataSetChanged()
        rv.post { rv.scrollToPosition(0) }   // 强制回到顶部
    }

    /** 移除题目（彻底删除，不再显示） */
    private fun removeQuestion(q: Question) {
        session.statusOf(q.id).removed = true
        session.saveProgress()
        visibleQuestions.removeAll { it.id == q.id }
        adapter.notifyDataSetChanged()
    }

    /** 后续 —— 标记为 pending，聊天时排到尾部 */
    private fun markLater(q: Question) {
        session.statusOf(q.id).skipped = true
        session.saveProgress()
        // 列表先不动，让用户看到状态变更
        adapter.notifyDataSetChanged()
    }

    inner class QuestionViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val content: View = v.findViewById(R.id.content)
        val index: TextView = v.findViewById(R.id.tvIndex)
        val title: TextView = v.findViewById(R.id.tvTitle)
        val subtitle: TextView = v.findViewById(R.id.tvSubtitle)
        val status: TextView = v.findViewById(R.id.tvStatus)
        val swipeLater: TextView = v.findViewById(R.id.tvSwipeLater)
        val swipeDelete: TextView = v.findViewById(R.id.tvSwipeDelete)
    }

    inner class QuestionAdapter : RecyclerView.Adapter<QuestionViewHolder>() {
        override fun getItemCount() = visibleQuestions.size

        override fun onCreateViewHolder(p: android.view.ViewGroup, t: Int): QuestionViewHolder {
            val v = layoutInflater.inflate(R.layout.item_question, p, false)
            val h = QuestionViewHolder(v)

            h.swipeDelete.setOnClickListener {
                val i = h.adapterPosition
                if (i in visibleQuestions.indices) {
                    removeQuestion(visibleQuestions[i])
                    swipeOpen.clear()
                    adapter.notifyDataSetChanged()
                }
            }
            h.swipeLater.setOnClickListener {
                val i = h.adapterPosition
                if (i in visibleQuestions.indices) {
                    markLater(visibleQuestions[i])
                    swipeOpen.clear()
                    adapter.notifyDataSetChanged()
                }
            }

            // 左滑露出按钮 —— 用简单的 view translation 实现
            h.content.setOnTouchListener(SwipeTouchListener(
                context = this@QuestionListActivity,
                getOpenState = { swipeOpen[h.adapterPosition] ?: -1 },
                onOpen = {
                    swipeOpen.clear(); swipeOpen[h.adapterPosition] = 0
                    adapter.notifyDataSetChanged()
                },
                onClose = {
                    swipeOpen.remove(h.adapterPosition)
                    h.content.translationX = 0f
                },
                onClick = {
                    val i = h.adapterPosition
                    if (i in visibleQuestions.indices) {
                        startActivity(Intent(this@QuestionListActivity, ChatActivity::class.java)
                            .putExtra("key", session.key)
                            .putExtra("startQid", visibleQuestions[i].id))
                    }
                }
            ))

            return h
        }

        override fun onBindViewHolder(h: QuestionViewHolder, pos: Int) {
            val q = visibleQuestions[pos]
            h.index.text = "${session.questions.indexOf(q) + 1}"
            val plain = android.text.Html.fromHtml(
                q.stemHtml.replace(Regex("<img[^>]*>"), "［图］"),
                android.text.Html.FROM_HTML_MODE_LEGACY
            ).toString().trim()
                .replace(Regex("\\s+"), " ")   // 合并多空白
                .trim('。', '.', '?', '？')    // 末尾标点去掉让列表更简洁
            val title = if (plain.length > 42) plain.substring(0, 42) + "…" else plain
            h.title.text = title.ifBlank { "第 ${q.number} 题" }
            h.subtitle.text = q.section
            val st = session.statusOf(q.id)
            if (st.skipped) {
                h.status.visibility = View.VISIBLE
                h.status.text = "后续"
                h.status.setBackgroundColor(0xFFFF9500.toInt())
            } else if (st.errorCount > 0) {
                h.status.visibility = View.VISIBLE
                h.status.text = "错${st.errorCount}"
                h.status.setBackgroundColor(0xFFFA5151.toInt())
            } else {
                h.status.visibility = View.GONE
            }

            // 如果该位置被标记为"左滑露出"
            val isOpen = swipeOpen[pos] != null
            h.content.animate().cancel()
            h.content.animate().translationX(if (isOpen) -176.dp() else 0f).setDuration(150).start()
        }

        private fun Int.dp(): Float =
            this@QuestionListActivity.resources.displayMetrics.density * this
    }
}
