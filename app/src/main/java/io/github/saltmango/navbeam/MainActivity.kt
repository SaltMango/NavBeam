package io.github.saltmango.navbeam

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.github.saltmango.navbeam.ui.navigation.AppNavigation
import io.github.saltmango.navbeam.viewmodel.RouteViewModel

class MainActivity : ComponentActivity() {
    private val routeViewModel: RouteViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        Log.w("Looog", "MainActivity onCreate")

        handleIntent(intent)
        setContent {
            // Root composable. AppNavigation handles internal padding and theming.
            AppNavigation(routeViewModel = routeViewModel)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                if (intent.type == "text/plain") {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
                    routeViewModel.handleSharedText(text)
                }
            }
            Intent.ACTION_VIEW -> {
                val uri = intent.data?.toString() ?: return
                routeViewModel.handleSharedText(uri)
            }
        }
    }
}
