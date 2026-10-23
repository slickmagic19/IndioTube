package com.indiotube.app.data

// What Home should show for the current connection. Offline with cached or
// downloaded content keeps the list (with a banner); offline with nothing keeps
// the user pointed at Downloads instead of an empty recommendation feed.
enum class HomeConnection {
    ONLINE,
    OFFLINE_WITH_CONTENT,
    OFFLINE_EMPTY,
}

fun homeConnection(online: Boolean, hasContent: Boolean): HomeConnection = when {
    online -> HomeConnection.ONLINE
    hasContent -> HomeConnection.OFFLINE_WITH_CONTENT
    else -> HomeConnection.OFFLINE_EMPTY
}

// Every feed fetch (initial load, tab return, pull-to-refresh, pagination and
// category search) is gated on this: with no validated network there is
// nothing useful to ask YouTube for.
fun shouldRefreshFeed(online: Boolean): Boolean = online
