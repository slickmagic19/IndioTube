package com.indiotube.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.indiotube.app.player.PlaybackController
import com.indiotube.app.ui.IndioTubeApp
import com.indiotube.app.ui.IndioTubeTheme

class MainActivity : ComponentActivity() {

    private var player: PlaybackController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as IndioTubeApplication).container
        val player = PlaybackController(this, container.library, container.playbackPrefs, container)
        this.player = player

        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
        }

        setContent {
            IndioTubeTheme {
                IndioTubeApp(container, player)
            }
        }
    }

    override fun onDestroy() {
        // The player lives in PlaybackService; this connection is per-activity.
        // Releasing it stops the old polling/countdown when the activity is
        // recreated, so a rotation cannot leave two controllers racing.
        player?.release()
        player = null
        super.onDestroy()
    }
}
