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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as IndioTubeApplication).container
        val player = PlaybackController(this, container.library)

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
}
