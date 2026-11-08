package com.zhibai.app.agent

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

    fun startSession() {
        _tasks.value = emptyList()
        nextId = 1
        hasFailure = false
        sessionId++
        _isActive.value = true
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
        _isActive.value = false
    }
}

enum class AgentSessionResult {
    NONE,
    IN_PROGRESS,
    SUCCESS,
    FAILED
}
