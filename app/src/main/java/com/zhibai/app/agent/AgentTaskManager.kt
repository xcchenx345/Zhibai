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
 * Manages the agent checklist (T1–T999).
 *
 * Success: all remaining tasks are DONE or CANCELLED, and at least one is DONE.
 * Failure: any task is struck (removed from list), or all are cancelled with none done.
 */
class AgentTaskManager {
    private val _tasks = MutableStateFlow<List<CheckTask>>(emptyList())
    val tasks: StateFlow<List<CheckTask>> = _tasks.asStateFlow()

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private var nextId = 1
    private var hasFailure = false

    /** Unique session ID — increments on each startSession(). Used to prevent duplicate celebrations. */
    var sessionId: Int = 0
        private set

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

    fun startSession() {
        _tasks.value = emptyList()
        nextId = 1
        hasFailure = false
        sessionId++
        _isActive.value = true
        AppLog.d("agentSession start: #$sessionId")
    }

    fun addTask(title: String): CheckTask {
        val task = CheckTask(id = nextId++, title = title)
        _tasks.value = _tasks.value + task
        return task
    }

    fun updateStatus(taskId: Int, status: CheckTaskStatus) {
        _tasks.value = _tasks.value.map {
            if (it.id == taskId) it.copy(status = status) else it
        }
    }

    /** Mark a task as "划掉" — removed from list, counts as failure. */
    fun strikeTask(taskId: Int) {
        hasFailure = true
        _tasks.value = _tasks.value.filterNot { it.id == taskId }
    }

    fun getSessionResult(): AgentSessionResult {
        val taskList = _tasks.value
        if (hasFailure) return AgentSessionResult.FAILED
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
