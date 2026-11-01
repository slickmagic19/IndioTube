package com.indiotube.app

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import com.indiotube.app.ui.detectHoldToBoost
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Press-and-hold to watch at 2x has to live next to the gestures the player
// already has. The important part is that the hold detector observes the
// touch without consuming it, so tapping still toggles the chrome and
// double-tapping still seeks.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class HoldToBoostTest {

    @get:Rule
    val compose = createComposeRule()

    private var taps = 0
    private var boosts = 0
    private var released = 0

    private fun content(withTapGesture: Boolean = false) {
        compose.setContent {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectHoldToBoost(
                            onHoldStart = { boosts += 1 },
                            onHoldEnd = { released += 1 },
                        )
                    }
                    .then(
                        if (withTapGesture) {
                            Modifier.pointerInput(Unit) {
                                detectTapGestures(onTap = { taps += 1 })
                            }
                        } else {
                            Modifier
                        },
                    ),
            )
        }
    }

    @Test
    fun aQuickTapIsNotABoost() {
        content()

        compose.onRoot().performTouchInput { click() }

        assertEquals(0, boosts)
        assertEquals(0, released)
    }

    @Test
    fun aTapStillReachesTheTapGesture() {
        content(withTapGesture = true)

        compose.onRoot().performTouchInput { click() }

        assertEquals("the hold detector must not swallow taps", 1, taps)
        assertEquals(0, boosts)
    }
}
