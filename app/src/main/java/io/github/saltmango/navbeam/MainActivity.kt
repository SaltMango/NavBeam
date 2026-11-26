package io.github.saltmango.navbeam

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import io.github.saltmango.navbeam.ui.navigation.AppNavigation
import io.github.saltmango.navbeam.viewmodel.AppViewModel
import io.github.saltmango.navbeam.viewmodel.RouteViewModel

class MainActivity : ComponentActivity() {
    private val appViewModel: AppViewModel by viewModels()
    private val routeViewModel: RouteViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        Log.w("Looog", "MainActivity onCreate")

        handleIntent(intent)
        setContent {
            // Root composable. AppNavigation handles internal padding and theming.
            AppNavigation(
                appViewModel = appViewModel,
                routeViewModel = routeViewModel,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                val streamUri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
                if (streamUri != null) {
                    appViewModel.importProfileFromUri(streamUri)
                } else if (intent.type == "text/plain") {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
                    routeViewModel.handleSharedText(text)
                }
            }
            Intent.ACTION_VIEW -> {
                val uri = intent.data ?: return
                val scheme = uri.scheme?.lowercase()
                if (scheme == "http" || scheme == "https" || scheme == "geo") {
                    routeViewModel.handleSharedText(uri.toString())
                } else {
                    appViewModel.importProfileFromUri(uri)
                }
            }
        }
    }
}
