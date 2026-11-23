package com.pvd.scwcontrolroom

import android.content.Intent
import android.os.Looper
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.pvd.scwcontrolroom.kit.DemoState
import com.pvd.scwcontrolroom.model.AppModel
import com.pvd.scwcontrolroom.model.MemoryKeyStore
import com.pvd.scwcontrolroom.model.MockProStore
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Opens every main screen of the demo, as the iPhone's screenshots.sh does,
 * and checks it shows what it should. `./gradlew :app:recordRoborazziDebug`
 * also saves the pictures for the Play Store listing to
 * PlayStore/screenshots/phone, at 1080 x 1920.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-xxhdpi", sdk = [36])
class ScreenshotTest {
    @get:Rule val compose = createEmptyComposeRule()

    /** Name, route, and a text the screen must show. */
    private val shots = listOf(
        Triple("01-instances", "/", "web-prod-1"),
        Triple("02-instance", "/instance/demo-1", "Stop"),
        Triple("03-serverless", "/serverless", "checkout-api"),
        Triple("04-logs", "/logs?kind=serverless&id=demo-c1", "GET /api/cart 200 18ms"),
        Triple("05-costs", "/costs", "By category"),
        Triple("06-instance-logs", "/logs?kind=instance&id=demo-3", "This instance sends no logs"),
        Triple("07-metric", "/metric?kind=serverless&id=demo-c1&name=http_requests_total", "Latest"),
        Triple("08-other", "/other", "uploads"),
    )

    @Test fun everyScreenOfTheDemo() {
        val out = File("../PlayStore/screenshots/phone")
        val record = System.getProperty("roborazzi.test.record") == "true"
        if (record) out.listFiles { file -> file.extension == "png" }?.forEach { it.delete() }
        for ((name, route, expected) in shots) {
            // Every shot starts the app cold, with only the demo key.
            DemoState.reset()
            val app = ApplicationProvider.getApplicationContext<ControlRoomApp>()
            app.model = AppModel(MemoryKeyStore())
            app.pro = MockProStore(app)
            val intent = Intent(app, MainActivity::class.java).putExtra("screenshotRoute", route)
            ActivityScenario.launch<MainActivity>(intent).use {
                // The demo answers after 300 ms, on the test's clock; let it,
                // and the screens' own loading, finish.
                repeat(30) {
                    compose.mainClock.advanceTimeBy(100)
                    shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
                }
                compose.waitForIdle()
                compose.onRoot().captureRoboImage(File(out, "$name.png").path)
                compose.onAllNodesWithText(expected, substring = true).onFirst().assertIsDisplayed()
            }
        }
        // Identical pictures mean the app never got past one screen.
        if (record) {
            val pictures = out.listFiles { file -> file.extension == "png" }.orEmpty()
            val hashes = pictures.map { MessageDigest.getInstance("MD5").digest(it.readBytes()).toList() }.toSet()
            assertEquals(shots.size, pictures.size)
            assertEquals("Some screenshots are identical", pictures.size, hashes.size)
        }
    }
}
