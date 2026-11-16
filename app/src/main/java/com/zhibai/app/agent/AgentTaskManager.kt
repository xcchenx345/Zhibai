package com.zhibai.app.agent

import com.zhibai.app.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Status of a single agent checklist task. */
enum class CheckTaskStatus {
    PENDING,      // 待办
    IN_PROGRESS,  // 进行中
    DONE,         // 已完成
    CANCELLED     // 已取消
}

/** A single task in the agent checklist (T1–T999). */
data class CheckTask(
    val id: Int,
    val title: String,
    val status: CheckTaskStatus = CheckTaskStatus.PENDING
) {
    val label: String get() = "T$id"
}

/**
 * Manages the agent checklist (T1–T999), scoped PER CHAT CONVERSATION — each
 * conversation keeps its own list (the user opens it from that conversation's
 * top-right menu), so switching chats never shows another chat's tasks.
 *
 * Success: all remaining tasks are DONE or CANCELLED, and at least one is DONE.
 * Failure: any task is struck (removed from list), or all are cancelled with none done.
 *
 * Buckets persist to [persistFile] so an app restart no longer wipes the
 * checklist the user is looking at.
 */
class AgentTaskManager(private val persistFile: java.io.File? = null) {

    private data class Bucket(
        val tasks: List<CheckTask> = emptyList(),
        val nextId: Int = 1,
        val hasFailure: Boolean = false
    )

    /** chat session id → that conversation's checklist. */
    private val buckets = java.util.concurrent.ConcurrentHashMap<String, Bucket>()
    private var currentChatId: String = ""

    private val _tasks = MutableStateFlow<List<CheckTask>>(emptyList())
    val tasks: StateFlow<List<CheckTask>> = _tasks.asStateFlow()

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private fun bucket(): Bucket = buckets[currentChatId] ?: Bucket()

    private fun mutateBucket(transform: (Bucket) -> Bucket) {
        buckets[currentChatId] = transform(bucket())
        _tasks.value = bucket().tasks
        persist()
    }

    /** Unique session ID — increments on each startSession(). Used to prevent duplicate celebrations. */
    var sessionId: Int = 0
        private set

    init {
        // After sessionId's declaration — property initializers run in order,
        // so load() must not run before sessionId exists (it would get zeroed).
        load()
    }

    private fun load() {
        val f = persistFile ?: return
        try {
            if (!f.exists()) return
            val root = com.google.gson.JsonParser.parseString(f.readText()).asJsonObject
            sessionId = root.get("sessionId")?.asInt ?: 0
            val bucketsEl = root.get("buckets")?.takeIf { it.isJsonObject }?.asJsonObject
            if (bucketsEl != null) {
                for ((chatId, v) in bucketsEl.entrySet()) {
                    val o = v.asJsonObject
                    val arr = o.getAsJsonArray("tasks") ?: continue
                    val tasks = arr.mapNotNull { el ->
                        val t = el.asJsonObject
                        val id = t.get("id")?.asInt ?: return@mapNotNull null
                        val title = t.get("title")?.asString ?: return@mapNotNull null
                        val status = try {
                            CheckTaskStatus.valueOf(t.get("status")?.asString ?: "PENDING")
                        } catch (_: Exception) { CheckTaskStatus.PENDING }
                        CheckTask(id = id, title = title, status = status)
                    }
                    buckets[chatId] = Bucket(
                        tasks = tasks,
                        nextId = o.get("nextId")?.asInt ?: ((tasks.maxOfOrNull { it.id } ?: 0) + 1),
                        hasFailure = o.get("hasFailure")?.asBoolean ?: false
                    )
                }
            }
            AppLog.d("agentTasks loaded: ${buckets.size} conversations from ${f.name}")
        } catch (e: Exception) {
            AppLog.e("agentTasks load failed", e)
        }
    }

    private fun persist() {
        val f = persistFile ?: return
        try {
            val bucketsEl = com.google.gson.JsonObject()
            for ((chatId, b) in buckets) {
                val arr = com.google.gson.JsonArray().apply {
                    b.tasks.forEach { t ->
                        add(com.google.gson.JsonObject().apply {
                            addProperty("id", t.id)
                            addProperty("title", t.title)
                            addProperty("status", t.status.name)
                        })
                    }
                }
                bucketsEl.add(chatId, com.google.gson.JsonObject().apply {
                    addProperty("nextId", b.nextId)
                    addProperty("hasFailure", b.hasFailure)
                    add("tasks", arr)
                })
            }
            val root = com.google.gson.JsonObject().apply {
                addProperty("sessionId", sessionId)
                add("buckets", bucketsEl)
            }
            f.writeText(root.toString())
        } catch (e: Exception) {
            AppLog.e("agentTasks persist failed", e)
        }
    }

    /** Point the checklist at a chat conversation (drawer / session switch). */
    fun bindConversation(chatSessionId: String) {
        if (currentChatId == chatSessionId) return
        currentChatId = chatSessionId
        _tasks.value = bucket().tasks
    }

    /** Last session whose finish celebration already played — survives leaving
     *  the Agent screen, unlike a `remember` in composition (which reset to -1 on
     *  re-entering Agent mode and re-triggered the snow every time). */
    private var celebratedSessionId = 0

    /** Session the UI is currently watching run — celebration only fires for a
     *  finish the user actually saw, never for a stale session discovered later. */
    private var watchingSessionId = 0

    /** Call while the UI observes an active run (agentActive true). */
    fun armCelebration() {
        if (_isActive.value) {
            watchingSessionId = sessionId
            AppLog.d("agentSession watching: #$sessionId")
        }
    }

    /**
     * One-shot celebration gate: true exactly once per finished session, and only
     * for a run the UI watched AND that checked off its whole checklist. A run
     * that created no tasks — or finished with anything still unchecked — never
     * snows.
     */
    fun consumeCelebration(): Boolean {
        val id = sessionId
        if (_isActive.value || id == 0 || id == celebratedSessionId || id != watchingSessionId) return false
        celebratedSessionId = id
        val taskList = _tasks.value
        val allChecked = taskList.isNotEmpty() && taskList.all { it.status == CheckTaskStatus.DONE }
        AppLog.d("agentSession celebrate gate: #$id tasks=${taskList.size} allChecked=$allChecked")
        return allChecked
    }

    /** A new agent run starts in [chatSessionId] — that conversation gets a fresh checklist. */
    fun startSession(chatSessionId: String = currentChatId) {
        currentChatId = chatSessionId
        buckets[chatSessionId] = Bucket()
        _tasks.value = emptyList()
        sessionId++
        _isActive.value = true
        persist()
        AppLog.d("agentSession start: #$sessionId chat=$chatSessionId")
    }

    fun addTask(title: String): CheckTask {
        val b = bucket()
        val task = CheckTask(id = b.nextId, title = title)
        mutateBucket { it.copy(tasks = it.tasks + task, nextId = it.nextId + 1) }
        return task
    }

    fun updateStatus(taskId: Int, status: CheckTaskStatus) {
        mutateBucket { b ->
            b.copy(tasks = b.tasks.map { if (it.id == taskId) it.copy(status = status) else it })
        }
    }

    /** Mark a task as "划掉" — removed from list, counts as failure. */
    fun strikeTask(taskId: Int) {
        mutateBucket { b ->
            b.copy(tasks = b.tasks.filterNot { it.id == taskId }, hasFailure = true)
        }
    }

    fun getSessionResult(): AgentSessionResult {
        val b = bucket()
        val taskList = b.tasks
        if (b.hasFailure) return AgentSessionResult.FAILED
        if (taskList.isEmpty()) return AgentSessionResult.NONE
        val allDoneOrCancelled = taskList.all {
            it.status == CheckTaskStatus.DONE || it.status == CheckTaskStatus.CANCELLED
        }
        val hasDone = taskList.any { it.status == CheckTaskStatus.DONE }
        return when {
            allDoneOrCancelled && hasDone -> AgentSessionResult.SUCCESS
            allDoneOrCancelled && !hasDone -> AgentSessionResult.FAILED
            else -> AgentSessionResult.IN_PROGRESS
        }
    }

    fun endSession() {
        if (_isActive.value) AppLog.d("agentSession end: #$sessionId result=${getSessionResult()}")
        _isActive.value = false
    }
}

enum class AgentSessionResult {
    NONE,
    IN_PROGRESS,
    SUCCESS,
    FAILED
}
