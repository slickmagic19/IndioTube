package com.indiotube.app.player

import android.os.Bundle
import androidx.media3.session.SessionCommand

// Custom MediaSession commands shared by the service and the controller.
object PlaybackCommands {

    // While the watch page is showing the Up next card, the player must pause
    // at the end of the item so the countdown can run. Elsewhere the service
    // follows the Autoplay preference (auto-advance when on).
    val SET_COUNTDOWN_PAUSE = SessionCommand(
        "com.indiotube.app.action.SET_COUNTDOWN_PAUSE",
        Bundle.EMPTY,
    )

    const val KEY_ENABLED = "enabled"
}
