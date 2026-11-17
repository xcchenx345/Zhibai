package com.zhibai.app.ui.components

import com.zhibai.app.i18n.LangManager

/**
 * The canned Chinese replies used when the provider's high-risk English rejection
 * is sanitized. MessageBubble uses [isReply] to strip thinking / copy / regenerate
 * affordances from such messages — nothing useful to copy or re-ask there.
 */
object HighRiskRejection {
    val replies: List<String>
        get() = listOf(
            LangManager.t("chat.highrisk.reply1", "你好，这个问题我无法回答，换一个问题试试吧。"),
            LangManager.t("chat.highrisk.reply2", "世界上话题如此之多，换个话题再试试吧。")
        )

    fun isReply(text: String): Boolean = text.trim() in replies
}
