package com.zhibai.app.ui.components

/**
 * The canned Chinese replies used when the provider's high-risk English rejection
 * is sanitized. MessageBubble uses [isReply] to strip thinking / copy / regenerate
 * affordances from such messages — nothing useful to copy or re-ask there.
 */
object HighRiskRejection {
    val replies = listOf(
        "你好，这个问题我无法回答，换一个问题试试吧。",
        "世界上话题如此之多，换个话题再试试吧。"
    )

    fun isReply(text: String): Boolean = text.trim() in replies
}
