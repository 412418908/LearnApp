package com.example.learnapp

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.File

data class Question(
    val id: String,
    val subject: String,
    val section: String,
    val number: Int,
    val type: String,
    val stemHtml: String,
    val optionsHtml: List<String>,
    val answerHtml: String,
    val explanationHtml: String
)

/** 每题的答题状态 */
class QStatus {
    var archived = false      // 已搞定，归档不再显示
    var removed = false       // 从题库中彻底删除
    var skipped = false       // 后续，排到尾部
    var errorCount = 0        // 答错次数
    fun passed() = archived
    fun failed() = !archived && errorCount > 0
}

class Session(
    val key: String,          // zip 文件名（去扩展名）
    val dir: File,            // 解压目录 filesDir/sessions/<key>
    val questions: List<Question>,
    val summary: String
) {
    val status = HashMap<String, QStatus>()   // questionId -> status
    val progressFile: File get() = File(dir, "progress.json")

    fun statusOf(id: String): QStatus =
        status.getOrPut(id) { QStatus() }

    fun loadProgress() {
        if (!progressFile.exists()) return
        try {
            val obj = JSONObject(progressFile.readText())
            val it2 = obj.keys()
            while (it2.hasNext()) {
                val id = it2.next()
                val o = obj.getJSONObject(id)
                val s = QStatus()
                s.archived = o.optBoolean("archived")
                s.removed = o.optBoolean("removed")
                s.skipped = o.optBoolean("skipped")
                s.errorCount = o.optInt("errors")
                status[id] = s
            }
        } catch (_: Exception) { }
    }

    fun saveProgress() {
        val obj = JSONObject()
        for ((id, s) in status) {
            obj.put(id, JSONObject()
                .put("archived", s.archived)
                .put("removed", s.removed)
                .put("skipped", s.skipped)
                .put("errors", s.errorCount))
        }
        progressFile.writeText(obj.toString())
    }

    fun statsText(): String {
        var pass = 0; var fail = 0; var todo = 0
        for (q in questions) {
            val s = status[q.id]
            when {
                s == null -> todo++
                s.archived -> pass++
                else -> fail++
            }
        }
        return "通过 $pass / 未通过 $fail / 未答 $todo / 共 ${questions.size}"
    }
}

/** 账本里一条 zip 的处理记录 */
private data class ZipEntry(
    val zipName: String,   // 原始 zip 文件名（含 .zip）
    val key: String,       // 会话名 = 去 .zip
    val size: Long,        // zip 大小，用于检测 zip 是否被替换
    val handledAt: Long    // 处理时间
)

object SessionManager {

    private const val PREF_CFG = "cfg"
    private const val PREF_LEDGER = "ledger"   // 账本存为 JSON string set

    fun sessionsRoot(ctx: Context): File = File(ctx.filesDir, "sessions").apply { mkdirs() }

    fun treeUri(ctx: Context): String? =
        ctx.getSharedPreferences(PREF_CFG, Context.MODE_PRIVATE).getString("tree", null)

    fun saveTreeUri(ctx: Context, uri: Uri) {
        ctx.getSharedPreferences(PREF_CFG, Context.MODE_PRIVATE).edit()
            .putString("tree", uri.toString()).apply()
    }

    /** 账本：zip 文件名 -> ZipEntry */
    private fun loadLedger(ctx: Context): Map<String, ZipEntry> {
        val raw = ctx.getSharedPreferences(PREF_CFG, Context.MODE_PRIVATE)
            .getStringSet(PREF_LEDGER, emptySet()) ?: emptySet()
        val out = HashMap<String, ZipEntry>()
        for (line in raw) {
            try {
                val o = JSONObject(line)
                out[o.getString("zipName")] = ZipEntry(
                    zipName = o.getString("zipName"),
                    key = o.getString("key"),
                    size = o.getLong("size"),
                    handledAt = o.getLong("handledAt")
                )
            } catch (_: Exception) { }
        }
        return out
    }

    private fun saveLedger(ctx: Context, map: Map<String, ZipEntry>) {
        val set = map.values.map { e ->
            JSONObject()
                .put("zipName", e.zipName)
                .put("key", e.key)
                .put("size", e.size)
                .put("handledAt", e.handledAt)
                .toString()
        }.toSet()
        ctx.getSharedPreferences(PREF_CFG, Context.MODE_PRIVATE).edit()
            .putStringSet(PREF_LEDGER, set).apply()
    }

    private fun keyOf(zipName: String) = zipName.removeSuffix(".zip")

    /** 扫描 SAF 目录下的 zip，导入"账本里没有"或"zip 已被替换"的；返回全部会话 */
    fun refresh(ctx: Context): List<Session> {
        val root = sessionsRoot(ctx)
        val tree = treeUri(ctx) ?: return listSessions(ctx)
        val dir = DocumentFile.fromTreeUri(ctx, Uri.parse(tree)) ?: return listSessions(ctx)
        val ledger = loadLedger(ctx)
        val updated = ledger.toMutableMap()

        for (f in dir.listFiles()) {
            val name = f.name ?: continue
            if (!name.endsWith(".zip", true)) continue
            val key = keyOf(name)
            val size = f.length()

            // 跳过已经处理过，且 zip 内容没变、解压目录还在的
            val prev = updated[name]
            val target = File(root, key)
            if (prev != null && prev.size == size && target.exists()) continue
            // 账本有但解压目录没了（用户删了会话）——也跳过，保持用户的删除意图
            if (prev != null && prev.size == size) continue

            // 解压
            target.mkdirs()
            try {
                var success = false
                ctx.contentResolver.openInputStream(f.uri)?.use { input ->
                    java.util.zip.ZipInputStream(input).use { zis ->
                        var e = zis.nextEntry
                        while (e != null) {
                            val out = File(target, e.name)
                            if (!out.canonicalPath.startsWith(target.canonicalPath)) { e = zis.nextEntry; continue }
                            if (e.isDirectory) out.mkdirs()
                            else {
                                out.parentFile?.mkdirs()
                                out.outputStream().use { zis.copyTo(it) }
                            }
                            zis.closeEntry()
                            e = zis.nextEntry
                        }
                    }
                }
                // 兼容两种 zip 结构：
                //   A. zip -> build/questions.jsonl         （旧版，期望结构）
                //   B. zip -> questions.jsonl + assets/     （用户直接打了 build 里的内容）
                //   C. zip -> 其它子目录/questions.jsonl    （如 物理/build/...）
                val buildDir = detectBuildDir(target)
                if (buildDir == null) {
                    target.deleteRecursively()
                    continue   // 不是题库 zip，跳过且不入账
                }
                if (buildDir.parentFile == target) {
                    // 已经是 target/build/questions.jsonl，结构 OK
                } else {
                    // 挪：target/xxx/questions.jsonl -> target/build/questions.jsonl
                    val tmp = File(target, "__tmp_build__")
                    buildDir.renameTo(tmp)
                    // 清根目录其它杂物（如原顶层 questions.jsonl / assets）
                    target.listFiles()?.filter { it.name != "__tmp_build__" }
                        ?.forEach { it.deleteRecursively() }
                    tmp.renameTo(File(target, "build"))
                }

                success = true
                updated[name] = ZipEntry(name, key, size, System.currentTimeMillis())
            } catch (ex: Exception) {
                target.deleteRecursively()
            }
        }
        saveLedger(ctx, updated)
        return listSessions(ctx)
    }

    /** 在解压目录里找到 questions.jsonl 所在目录；找不到返回 null */
    private fun detectBuildDir(target: File): File? {
        // 直接在 target/build/
        val a = File(target, "build/questions.jsonl")
        if (a.exists()) return File(target, "build")
        // 直接在 target 根
        val b = File(target, "questions.jsonl")
        if (b.exists()) return target
        // 任意一级子目录
        target.listFiles()?.filter { it.isDirectory }?.forEach { sub ->
            val c = File(sub, "questions.jsonl")
            if (c.exists()) return sub
        }
        return null
    }

    fun listSessions(ctx: Context): List<Session> {
        val root = sessionsRoot(ctx)
        return root.listFiles()?.filter { it.isDirectory }?.mapNotNull { dir ->
            val jsonl = File(dir, "build/questions.jsonl")
            if (!jsonl.exists()) return@mapNotNull null
            val questions = jsonl.readLines().filter { it.isNotBlank() }.mapNotNull { line ->
                try {
                    val o = JSONObject(line)
                    Question(
                        id = o.getString("id"),
                        subject = o.optString("subject"),
                        section = o.optString("section"),
                        number = o.optInt("number"),
                        type = o.optString("type"),
                        stemHtml = o.optString("stem_html"),
                        optionsHtml = (o.optJSONArray("options_html")?.let { arr ->
                            (0 until arr.length()).map { arr.optString(it) }
                        } ?: emptyList()),
                        answerHtml = o.optString("answer_html"),
                        explanationHtml = o.optString("explanation_html")
                    )
                } catch (_: Exception) { null }
            }
            if (questions.isEmpty()) return@mapNotNull null
            val s = Session(dir.name, dir, questions, "")
            s.loadProgress()
            s
        }?.sortedBy { it.key } ?: emptyList()
    }

    /** 删除会话（内部解压目录）。用户删过会话后，账本里这条仍在——下次扫描到同 zip 也不会自动再导入，保持用户的删除意图 */
    fun delete(ctx: Context, session: Session) {
        session.dir.deleteRecursively()
    }

    /** 强制把某个 zip 重新导入（会清空原会话进度）。可用于"我想恢复之前误删的会话" */
    fun reimport(ctx: Context, zipName: String): List<Session> {
        // 从账本移除，让 refresh 重新处理
        val ledger = loadLedger(ctx).toMutableMap()
        ledger.remove(zipName)
        saveLedger(ctx, ledger)
        // 顺手删掉旧的解压目录（如果有）
        File(sessionsRoot(ctx), keyOf(zipName)).deleteRecursively()
        return refresh(ctx)
    }

    /** 账本大小 —— 调试/UI 展示用 */
    fun ledgerSize(ctx: Context) = loadLedger(ctx).size
}
