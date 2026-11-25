package com.pvd.scwcontrolroom

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.pvd.scwcontrolroom.ui.ControlRoomTheme
import com.pvd.scwcontrolroom.ui.RootScreen

/** A FragmentActivity, which the fingerprint and screen-lock prompt needs. */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as ControlRoomApp
        // The screenshot test starts the app with a screen to open, as the
        // iPhone's Screenshots workflow does with -screenshotRoute.
        val route = intent.getStringExtra("screenshotRoute")
        setContent {
            ControlRoomTheme {
                RootScreen(app.model, app.pro, route)
            }
        }
    }
}
