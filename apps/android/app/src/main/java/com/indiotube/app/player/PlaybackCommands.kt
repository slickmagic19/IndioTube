package com.indiotube.app.player

import android.os.Bundle
import androidx.media3.session.SessionCommand

// Custom MediaSession commands shared by the service and the controller.
object PlaybackCommands {

    // When true, the player pauses at the end of each item instead of moving
    // on to the next one (YouTube's Autoplay off).
    val SET_AUTOPLAY_NEXT = SessionCommand(
        "com.indiotube.app.action.SET_AUTOPLAY_NEXT",
        Bundle.EMPTY,
    )

    const val KEY_ENABLED = "enabled"
}
