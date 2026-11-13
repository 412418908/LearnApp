package com.example.learnapp

import android.content.Intent
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.ArrayDeque

class ChatActivity : AppCompatActivity() {

    private lateinit var session: Session
    private lateinit var rv: RecyclerView
    private lateinit var adapter: MsgAdapter
    private val msgs = mutableListOf<Msg>()

    private var currentQid: String? = null
    private val queue = ArrayDeque<String>()
    private var botAvatarRes = R.drawable.avatar_1   // 当前 bot 头像
    private var questionMsgPos = -1                  // 上一条"题目"气泡在 msgs 里的位置（双击定位）

    // 消息类型
    data class Msg(
        val kind: Int,           // 0 bot题 1 bot答案 2 用户 3 系统 4 中间提示
        val text: String? = null,
        val html: String? = null,
        val qid: String? = null,
        val botAvatar: Int = R.drawable.avatar_1
    )

    private companion object {
        const val KIND_BOT_Q = 0
        const val KIND_BOT_A = 1
        const val KIND_SYS = 3
        const val KIND_CENTER = 4
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        val key = intent.getStringExtra("key") ?: run { finish(); return }

        session = SessionManager.listSessions(this).firstOrNull { it.key == key }
            ?: run { finish(); return }

        rv = findViewById(R.id.rvChat)
        adapter = MsgAdapter()
        rv.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        rv.adapter = adapter

        findViewById<TextView>(R.id.tvTitle).text = session.key
        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btnMore).setOnClickListener { anchor ->
            PopupMenu(this, anchor).apply {
                menu.add("搞定（进入归档）")
                menu.add("后续（排到尾部）")
                setOnMenuItemClickListener { item ->
                    when (item.title.toString()) {
                        in "搞定（进入归档）" -> markDone()
                        in "后续（排到尾部）" -> markLater()
                    }
                    true
                }
                show()
            }
        }

        // 初始化队列：未归档 & 未删除 & 未后续（后续的排后面）
        val startQid = intent.getStringExtra("startQid")
        val ordered = session.questions.filter { q ->
            val s = session.statusOf(q.id)
            !s.archived && !s.removed
        }.partition { !session.statusOf(it.id).skipped }
            .let { it.first + it.second }     // 未后续在前，后续在后
        queue.clear()
        // 如果指定了 startQid，把它挪到队头
        val ids = ordered.map { it.id }.toMutableList()
        val startIdx = ids.indexOf(startQid)
        if (startIdx > 0) {
            val id = ids.removeAt(startIdx)
            ids.add(0, id)
        }
        queue.addAll(ids)

        botAvatarRes = randomBotAvatar()
        nextQuestion()
    }

    private fun markDone() {
        val id = currentQid ?: return
        session.statusOf(id).archived = true
        session.saveProgress()
        msgs.add(Msg(KIND_SYS, text = "✅ 已归档"))
        adapter.notifyItemInserted(msgs.size - 1); scrollEnd()
        nextQuestion()
    }

    private fun markLater() {
        val id = currentQid ?: return
        session.statusOf(id).skipped = true
        session.saveProgress()
        msgs.add(Msg(KIND_SYS, text = "⏳ 排到尾部"))
        adapter.notifyItemInserted(msgs.size - 1); scrollEnd()
        queue.addLast(id)
        nextQuestion()
    }

    private fun onSwipeUpNext() {
        // 上滑跳过，记录 errorCount++（候选），然后进下一题
        val id = currentQid ?: return
        session.statusOf(id).errorCount++
        session.statusOf(id).skipped = true
        session.saveProgress()
        queue.addLast(id)
        msgs.add(Msg(KIND_SYS, text = "⬆ 跳过（已记录错误次数 +1）"))
        adapter.notifyItemInserted(msgs.size - 1); scrollEnd()
        nextQuestion()
    }

    private fun nextQuestion() {
        if (queue.isEmpty()) {
            // 全部过完，再来一轮（已经 archived 的不再进队）
            session.questions.forEach { q ->
                val s = session.statusOf(q.id)
                if (!s.archived && !s.removed) {
                    queue.addLast(q.id)
                }
            }
            if (queue.isEmpty()) {
                msgs.add(Msg(KIND_SYS, text = "🎉 全部题目已搞定！"))
                adapter.notifyItemInserted(msgs.size - 1); scrollEnd()
                currentQid = null
                return
            }
        }
        val id = queue.pollFirst() ?: return
        currentQid = id
        val q = session.questions.firstOrNull { it.id == id } ?: return
        botAvatarRes = randomBotAvatar()
        val idx = session.questions.indexOf(q) + 1
        val status = session.statusOf(id)
        val tag = if (status.errorCount > 0) "（候选 · 已错${status.errorCount}次）" else ""
        val html = buildString {
            append("<b>【${idx}/${session.questions.size}】${q.section} 第${q.number}题</b>$tag")
            append(q.stemHtml)
            q.optionsHtml.forEach { append("<br>$it") }
        }
        msgs.add(Msg(KIND_BOT_Q, html = html, qid = id, botAvatar = botAvatarRes))
        questionMsgPos = msgs.size - 1
        adapter.notifyItemInserted(msgs.size - 1); scrollEnd()
    }

    private fun showAnswer() {
        val id = currentQid ?: return
        val q = session.questions.firstOrNull { it.id == id } ?: return
        val html = "<b>【答案】</b>${q.answerHtml}<br><b>【解析】</b>${q.explanationHtml}"
        msgs.add(Msg(KIND_BOT_A, html = html, botAvatar = botAvatarRes))
        adapter.notifyItemInserted(msgs.size - 1); scrollEnd()
    }

    private fun scrollEnd() = rv.post { rv.smoothScrollToPosition(msgs.size - 1) }

    private fun randomBotAvatar(): Int {
        val avatars = listOf(
            R.drawable.avatar_1, R.drawable.avatar_2, R.drawable.avatar_3,
            R.drawable.avatar_4, R.drawable.avatar_5, R.drawable.avatar_6,
            R.drawable.avatar_7, R.drawable.avatar_8, R.drawable.avatar_9
        )
        return avatars.random()
    }

    inner class MsgAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val TYPE_BOT = 0
        private val TYPE_USER = 1
        private val TYPE_CENTER = 2

        override fun getItemViewType(pos: Int): Int {
            val m = msgs[pos]
            return when (m.kind) {
                KIND_BOT_Q, KIND_BOT_A -> TYPE_BOT
                KIND_SYS, KIND_CENTER -> TYPE_CENTER
                else -> TYPE_USER
            }
        }

        override fun onCreateViewHolder(p: android.view.ViewGroup, t: Int): RecyclerView.ViewHolder {
            return when (t) {
                TYPE_BOT -> {
                    val v = layoutInflater.inflate(R.layout.item_bubble_left, p, false)
                    BotVH(v)
                }
                TYPE_USER -> {
                    val v = layoutInflater.inflate(R.layout.item_bubble_right, p, false)
                    UserVH(v)
                }
                else -> {
                    val v = layoutInflater.inflate(R.layout.item_center, p, false)
                    CenterVH(v)
                }
            }
        }

        override fun getItemCount() = msgs.size

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
            val m = msgs[pos]
            when (h) {
                is BotVH -> {
                    h.avatar.setImageResource(m.botAvatar)
                    if (m.html != null) HtmlRender.render(this@ChatActivity, m.html, session.dir, h.tv)
                    else h.tv.text = m.text
                    // 双击 bot 题目 → 出答案；上滑 → 下一题
                    if (m.kind == KIND_BOT_Q && m.qid == currentQid) {
                        val d = GestureDetector(this@ChatActivity,
                            object : GestureDetector.SimpleOnGestureListener() {
                                override fun onDoubleTap(e: MotionEvent): Boolean {
                                    showAnswer(); return true
                                }
                                override fun onFling(e1: MotionEvent?, e2: MotionEvent,
                                                     vx: Float, vy: Float): Boolean {
                                    if (e1 != null && (e2.y - e1.y) < -120 && vy < -800) {
                                        onSwipeUpNext(); return true
                                    }
                                    return false
                                }
                            })
                        // 同时挂在 tv（正文）和 root（气泡 padding 区）
                        val l = View.OnTouchListener { _, ev ->
                            d.onTouchEvent(ev); false
                        }
                        h.tv.setOnTouchListener(l)
                        h.root.setOnTouchListener(l)
                    } else {
                        h.tv.setOnTouchListener(null)
                        h.root.setOnTouchListener(null)
                    }
                }
                is UserVH -> {
                    h.avatar.setImageResource(R.drawable.my_avatar)
                    h.tv.text = m.text
                }
                is CenterVH -> {
                    h.tv.text = m.text
                }
            }
        }

        inner class BotVH(v: View) : RecyclerView.ViewHolder(v) {
            val root = v
            val avatar: ImageView = v.findViewById(R.id.ivAvatar)
            val tv: TextView = v.findViewById(R.id.tvBubble)
        }
        inner class UserVH(v: View) : RecyclerView.ViewHolder(v) {
            val avatar: ImageView = v.findViewById(R.id.ivAvatar)
            val tv: TextView = v.findViewById(R.id.tvBubble)
        }
        inner class CenterVH(v: View) : RecyclerView.ViewHolder(v) {
            val tv: TextView = v.findViewById(R.id.tvCenter)
        }
    }
}
