package com.example.learnapp

import android.content.Intent
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.ArrayDeque

class ChatActivity : AppCompatActivity() {

    private lateinit var session: Session
    private lateinit var rv: RecyclerView
    private lateinit var input: EditText
    private lateinit var panel: View
    private lateinit var adapter: MsgAdapter

    data class Msg(
        val role: String,            // bot / user / sys
        val text: String? = null,
        val html: String? = null,
        val qid: String? = null
    )

    private val msgs = mutableListOf<Msg>()
    private val queue = ArrayDeque<String>()
    private var currentQid: String? = null
    private var round = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        val key = intent.getStringExtra("key") ?: run { finish(); return }

        val all = SessionManager.listSessions(this)
        session = all.firstOrNull { it.key == key } ?: run { finish(); return }

        rv = findViewById(R.id.rvChat)
        adapter = MsgAdapter()
        rv.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        rv.adapter = adapter

        input = findViewById(R.id.etInput)
        panel = findViewById(R.id.quickPanel)

        findViewById<TextView>(R.id.tvChatTitle).apply {
            text = session.key
            setOnLongClickListener {
                session.statsText().also { Toast.makeText(this@ChatActivity, it, Toast.LENGTH_LONG).show() }
                true
            }
        }

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnMore).setOnClickListener { showMore(it) }
        findViewById<View>(R.id.btnVoice).setOnClickListener {
            Toast.makeText(this, "长按说话（示意）", Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.btnEmoji).setOnClickListener {
            Toast.makeText(this, "表情（示意）", Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.btnPlus).setOnClickListener {
            panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        input.setOnEditorActionListener { _, _, _ ->
            onSend(); true
        }

        quickButtons()

        // 向左滑动 → 下一题（作用于 bot 气泡所在区域）
        val flingDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 != null && (e2.x - e1.x) < -200 && kotlin.math.abs(vx) > 800 &&
                    kotlin.math.abs(e2.y - e1.y) < 300) {
                    onNextQuestion()
                    return true
                }
                return false
            }
        })
        rv.setOnTouchListener { _, e ->
            flingDetector.onTouchEvent(e)
            false
        }

        refillQueue(true)
        postText("bot", "第 $round 轮开始，共 ${queue.size} 题。向左滑下一题。双击当前题目 = 看答案。")
        nextQuestion()
    }

    private fun showMore(anchor: View) {
        PopupMenu(this, anchor).apply {
            menuInflater.inflate(R.menu.menu_chat, menu)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_stats -> {
                        startActivity(Intent(this@ChatActivity, StatsActivity::class.java)
                            .putExtra("key", session.key))
                    }
                    R.id.action_delete -> {
                        SessionManager.delete(this@ChatActivity, session)
                        Toast.makeText(this@ChatActivity, "已删除会话", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    R.id.action_reimport -> {
                        SessionManager.reimport(this@ChatActivity, "${session.key}.zip")
                        Toast.makeText(this@ChatActivity, "已重新导入", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    R.id.action_tip -> {
                        Toast.makeText(
                            this@ChatActivity,
                            "双击 bot 气泡 = 看答案\n左滑 = 下一题\n+ = 快捷按钮\n输入：搞定 / 答错了 / 下一题",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                true
            }
            show()
        }
    }

    private fun quickButtons() {
        val defs = listOf(
            "答案" to { onCommand("答案") },
            "搞定" to { onCommand("搞定") },
            "答错了" to { onCommand("答错了") },
            "下一题" to { onNextQuestion() },
            "统计" to { startActivity(Intent(this, StatsActivity::class.java).putExtra("key", session.key)) }
        )
        val row = panel.findViewById<LinearLayout>(R.id.quickRow)
        row.removeAllViews()
        for ((label, action) in defs) {
            val btn = layoutInflater.inflate(R.layout.item_quickbtn, row, false) as TextView
            btn.text = label
            btn.setOnClickListener { action(); panel.visibility = View.GONE }
            row.addView(btn)
        }
    }

    private fun onSend() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        input.setText("")
        msgs.add(Msg("user", text = t))
        adapter.notifyItemInserted(msgs.size - 1)
        scrollEnd()
        val cmd = when {
            t.contains("答案") -> "答案"
            t.contains("搞定") || t.contains("通过") -> "搞定"
            t.contains("答错") || t.contains("错了") -> "答错了"
            t.contains("下一题") -> "下一题"
            t.contains("统计") -> "统计"
            else -> null
        }
        if (cmd == null) postText("sys", "可用指令：答案 / 搞定 / 答错了 / 下一题 / 统计")
        else onCommand(cmd)
    }

    private fun onCommand(cmd: String) {
        when (cmd) {
            "答案" -> if (currentQ() == null) postText("sys", "当前没有题目") else showAnswer()
            "搞定" -> {
                val id = currentQid
                if (id == null) { postText("sys", "当前没有题目"); return }
                session.statusOf(id).archived = true
                session.saveProgress()
                postText("sys", "✅ 已搞定，进入归档")
                nextQuestion()
            }
            "答错了" -> {
                val id = currentQid
                if (id == null) { postText("sys", "当前没有题目"); return }
                val s = session.statusOf(id)
                s.errorCount++
                queue.addLast(id)
                session.saveProgress()
                postText("sys", "❌ 已记录（错误 ${s.errorCount} 次），进入候选")
                nextQuestion()
            }
            "下一题" -> onNextQuestion()
            "统计" -> {
                postText("sys", session.statsText())
                startActivity(Intent(this, StatsActivity::class.java).putExtra("key", session.key))
            }
        }
    }

    private fun currentQ(): Question? =
        currentQid?.let { id -> session.questions.firstOrNull { it.id == id } }

    private fun onNextQuestion() {
        val id = currentQid
        if (id != null && !session.statusOf(id).archived) queue.addLast(id)
        nextQuestion()
    }

    private fun refillQueue(firstRound: Boolean) {
        queue.clear()
        if (firstRound) {
            session.questions.forEach {
                if (!session.statusOf(it.id).archived) queue.add(it.id)
            }
        } else {
            session.questions.forEach { q ->
                val s = session.statusOf(q.id)
                if (!s.archived) queue.add(q.id)
            }
        }
    }

    private fun nextQuestion() {
        if (queue.isEmpty()) {
            val remaining = session.questions.count { !session.statusOf(it.id).archived }
            if (remaining > 0) {
                round++
                postText("bot", "🎉 本轮完成！开始第 $round 轮（剩余 $remaining 题）")
                refillQueue(false)
            } else {
                postText("bot", "🎉 全部题目已归档！${session.statsText()}")
                currentQid = null
                return
            }
        }
        val id = queue.pollFirst() ?: return
        currentQid = id
        val q = session.questions.firstOrNull { it.id == id } ?: return
        val idx = session.questions.indexOf(q) + 1
        val status = session.statusOf(id)
        val tag = if (status.failed()) "（候选 · 已错 ${status.errorCount} 次）" else ""
        val html = buildString {
            append("<b>【${idx}/${session.questions.size}】${q.section} 第${q.number}题</b>$tag")
            append(q.stemHtml)
            q.optionsHtml.forEach { append("<br>$it") }
        }
        msgs.add(Msg("bot", html = html, qid = id))
        adapter.notifyItemInserted(msgs.size - 1)
        scrollEnd()
    }

    private fun showAnswer() {
        val q = currentQ() ?: return
        val html = "<b>【答案】</b>${q.answerHtml}<br><b>【解析】</b>${q.explanationHtml}"
        msgs.add(Msg("bot", html = html, qid = q.id))
        adapter.notifyItemInserted(msgs.size - 1)
        scrollEnd()
    }

    private fun postText(role: String, text: String) {
        msgs.add(Msg(role, text = text))
        adapter.notifyItemInserted(msgs.size - 1)
        scrollEnd()
    }

    private fun scrollEnd() {
        rv.post { rv.smoothScrollToPosition(msgs.size - 1) }
    }

    inner class MsgAdapter : RecyclerView.Adapter<MsgAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val bubbleBot: View = v.findViewById(R.id.bubbleBot)
            val bubbleUser: View = v.findViewById(R.id.bubbleUser)
            val bubbleSys: TextView = v.findViewById(R.id.bubbleSys)
            val tvBot: TextView = v.findViewById(R.id.tvBot)
            val tvUser: TextView = v.findViewById(R.id.tvUser)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(layoutInflater.inflate(R.layout.item_msg, parent, false))

        override fun getItemCount() = msgs.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val m = msgs[pos]
            h.bubbleBot.visibility = View.GONE
            h.bubbleUser.visibility = View.GONE
            h.bubbleSys.visibility = View.GONE
            when (m.role) {
                "bot" -> {
                    h.bubbleBot.visibility = View.VISIBLE
                    if (m.html != null) HtmlRender.render(this@ChatActivity, m.html, session.dir, h.tvBot)
                    else h.tvBot.text = m.text
                    // 双击 bot 气泡 → 显示答案（仅限"题目"消息，答案消息本身不带答案 qid 的题目且已是答案）
                    val isQuestionMessage = m.qid != null && !((m.text ?: "").contains("【答案】"))
                    if (isQuestionMessage) {
                        val detector = GestureDetector(this@ChatActivity,
                            object : GestureDetector.SimpleOnGestureListener() {
                                override fun onDoubleTap(e: MotionEvent): Boolean {
                                    if (m.qid == currentQid) showAnswer()
                                    return true
                                }
                            })
                        // 把手势同时接到气泡和文字上
                        val listener = View.OnTouchListener { _, ev ->
                            detector.onTouchEvent(ev)
                            false
                        }
                        h.bubbleBot.setOnTouchListener(listener)
                        h.tvBot.setOnTouchListener(listener)
                    } else {
                        h.bubbleBot.setOnTouchListener(null)
                        h.tvBot.setOnTouchListener(null)
                    }
                }
                "user" -> { h.bubbleUser.visibility = View.VISIBLE; h.tvUser.text = m.text }
                else -> { h.bubbleSys.visibility = View.VISIBLE; h.bubbleSys.text = m.text }
            }
        }
    }
}
