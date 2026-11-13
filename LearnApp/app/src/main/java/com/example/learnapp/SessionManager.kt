package com.example.learnapp

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

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
                s.errorCount = o.optInt("errors")
                status[id] = s
            }
        } catch (_: Exception) { }
    }

    fun saveProgress() {
        val obj = JSONObject()
        for ((id, s) in status) {
            obj.put(id, JSONObject().put("archived", s.archived).put("errors", s.errorCount))
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

object SessionManager {

    fun sessionsRoot(ctx: Context): File = File(ctx.filesDir, "sessions").apply { mkdirs() }

    fun treeUri(ctx: Context): String? =
        ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).getString("tree", null)

    fun saveTreeUri(ctx: Context, uri: Uri) {
        ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
            .putString("tree", uri.toString()).apply()
    }

    private fun keyOf(zipName: String) = zipName.removeSuffix(".zip")

    /** 扫描 SAF 目录下的 zip，导入新增的，返回全部会话 */
    fun refresh(ctx: Context): List<Session> {
        val root = sessionsRoot(ctx)
        val tree = treeUri(ctx) ?: return listSessions(ctx)
        val dir = DocumentFile.fromTreeUri(ctx, Uri.parse(tree)) ?: return listSessions(ctx)
        for (f in dir.listFiles()) {
            val name = f.name ?: continue
            if (!name.endsWith(".zip", true)) continue
            val key = keyOf(name)
            val target = File(root, key)
            if (target.exists()) continue
            target.mkdirs()
            try {
                ctx.contentResolver.openInputStream(f.uri)?.use { input ->
                    java.util.zip.ZipInputStream(input).use { zis ->
                        var e = zis.nextEntry
                        val buf = ByteArray(1 shl 16)
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
            } catch (ex: Exception) {
                target.deleteRecursively()
            }
        }
        return listSessions(ctx)
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

    fun delete(ctx: Context, session: Session) {
        session.dir.deleteRecursively()
    }
}
