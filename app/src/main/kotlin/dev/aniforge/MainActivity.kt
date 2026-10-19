package dev.aniforge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.aniforge.domain.ProjectStore
import dev.aniforge.ui.EditorScreen
import dev.aniforge.ui.HomeScreen
import dev.aniforge.ui.theme.AniForgeTheme

/**
 * AniForge Studio — full on-device video editor (landscape, immersive).
 * Home = import & saved projects · Editor = timeline, motion graphs, auto-edit, export.
 */
class MainActivity : ComponentActivity() {

    private val store by lazy { ProjectStore() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            AniForgeTheme(dark = true) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    var screen by remember { mutableStateOf("home") }
                    if (screen == "home") {
                        HomeScreen(store) { screen = "editor" }
                    } else {
                        EditorScreen(store) { screen = "home" }
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        store.save(this)
    }
}
