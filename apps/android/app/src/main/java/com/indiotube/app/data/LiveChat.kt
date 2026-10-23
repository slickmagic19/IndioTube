package com.indiotube.app.data

// One message in a live stream's chat. `replay` chats (premieres and finished
// live streams) arrive from the same endpoint, so the rows render identically.
data class LiveChatMessage(
    val id: String,
    val author: String,
    val text: String,
    val avatar: String? = null,
    val badge: String? = null,
    val timestampText: String? = null,
    val amountText: String? = null,
)

data class LiveChatPage(
    val messages: List<LiveChatMessage>,
    val continuation: String? = null,
    val replay: Boolean = false,
)

// Where the chat lives: the continuation token plus whether it is a replay
// (a finished stream) or a live conversation.
data class LiveChatInfo(
    val token: String,
    val replay: Boolean = false,
)

// True when a live viewer is at the edge of the DVR window. A stream with no
// window yet (duration unknown) counts as live.
fun isAtLiveEdge(positionMs: Long, durationMs: Long, thresholdMs: Long = 15_000L): Boolean =
    durationMs <= 0L || positionMs >= durationMs - thresholdMs

// The chat's small author badges map to short labels; anything unknown is
// dropped rather than shown as a raw enum.
fun liveChatBadgeLabel(iconType: String?): String? = when (iconType) {
    "OWNER" -> "Owner"
    "MODERATOR" -> "Moderator"
    "VERIFIED" -> null
    else -> null
}
