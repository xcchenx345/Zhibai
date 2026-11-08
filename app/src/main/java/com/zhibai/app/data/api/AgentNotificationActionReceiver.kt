package com.zhibai.app.data.api

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Static bridge from notification action buttons back to the live ChatViewModel.
 * Registered by ChatViewModel while an agent run is active; cleared on ViewModel death.
 */
object AgentIslandBridge {
    @Volatile
    var approve: (() -> Unit)? = null

    @Volatile
    var deny: (() -> Unit)? = null

    @Volatile
    var phoneAgentStop: (() -> Unit)? = null

    @Volatile
    var phoneAgentTakeOver: (() -> Unit)? = null
}

/** Receives island action taps (approve/deny, phone-agent stop/take-over). */
class AgentNotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_APPROVE -> AgentIslandBridge.approve?.invoke()
            ACTION_DENY -> AgentIslandBridge.deny?.invoke()
            ACTION_PHONE_AGENT_STOP -> AgentIslandBridge.phoneAgentStop?.invoke()
            ACTION_PHONE_AGENT_TAKEOVER -> AgentIslandBridge.phoneAgentTakeOver?.invoke()
        }
    }

    companion object {
        const val ACTION_APPROVE = "com.zhibai.app.agent.NOTIFICATION_APPROVE"
        const val ACTION_DENY = "com.zhibai.app.agent.NOTIFICATION_DENY"
        const val ACTION_PHONE_AGENT_STOP = "com.zhibai.app.agent.PHONE_AGENT_STOP"
        const val ACTION_PHONE_AGENT_TAKEOVER = "com.zhibai.app.agent.PHONE_AGENT_TAKEOVER"
        const val REQ_APPROVE = 10002
        const val REQ_DENY = 10003
        const val REQ_PHONE_AGENT_STOP = 10004
        const val REQ_PHONE_AGENT_TAKEOVER = 10005
    }
}
