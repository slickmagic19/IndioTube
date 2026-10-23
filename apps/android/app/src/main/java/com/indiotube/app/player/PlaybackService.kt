package com.indiotube.app.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.indiotube.app.IndioTubeApplication
import com.indiotube.app.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

// Hosts the single ExoPlayer instance. Lives in the foreground-service
// pipeline so playback (and the notification controls) survive the UI.
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Set while the watch page's Up next countdown is on screen. It forces
    // pause-at-end even with autoplay on so the card can run before the jump.
    private var countdownPause = false

    override fun onCreate() {
        super.onCreate()
        val app = application as IndioTubeApplication
        // Let MediaCodec fall back to another decoder (including software) if
        // the first match cannot initialise. High-resolution VP9/AV1 ladders
        // otherwise look like a network stall before quality fallback kicks in.
        val renderers = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
        val player = ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            // Tuned for snappy starts: the stock player waits for 2.5s of
            // buffered media before resuming, which is exactly the lag felt
            // when switching to a higher quality. 1s is plenty to avoid
            // stutter on a healthy link, and 2s after a rebuffer.
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        20_000, // min buffer
                        50_000, // max buffer
                        1_000,  // buffer needed to start/resume playback
                        2_000,  // buffer needed to resume after a rebuffer
                    )
                    .build(),
            )
            .setMediaSourceFactory(IndioMediaSourceFactory(app.container))
            .build()

        // Autoplay is a playlist behaviour on the player itself. The watch
        // page can additionally force pause-at-end while its countdown card is
        // visible; with autoplay off the item always ends paused.
        scope.launch {
            app.container.playbackPrefs.autoplayNext.collect { enabled ->
                player.setPauseAtEndOfMediaItems(!enabled || countdownPause)
            }
        }

        session = MediaSession.Builder(this, player)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                ): MediaSession.ConnectionResult {
                    val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                        .buildUpon()
                        .add(PlaybackCommands.SET_COUNTDOWN_PAUSE)
                        .build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(commands)
                        .build()
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle,
                ): ListenableFuture<SessionResult> {
                    if (customCommand.customAction == PlaybackCommands.SET_COUNTDOWN_PAUSE.customAction) {
                        countdownPause = args.getBoolean(PlaybackCommands.KEY_ENABLED, false)
                        val autoplay = app.container.playbackPrefs.autoplayNext.value
                        (session.player as? ExoPlayer)
                            ?.setPauseAtEndOfMediaItems(!autoplay || countdownPause)
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    return super.onCustomCommand(session, controller, customCommand, args)
                }
            })
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
