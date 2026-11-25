package io.github.saltmango.navbeam

import android.app.Application
import android.content.ComponentName
import android.os.Build
import android.service.notification.NotificationListenerService
import io.github.saltmango.navbeam.dash.profile.DashProfileManager
import io.github.saltmango.navbeam.data.MapsKeyManager
import io.github.saltmango.navbeam.data.RideDiagnostics
import io.github.saltmango.navbeam.media.MediaInfoProvider
import io.github.saltmango.navbeam.media.NavBeamNotificationListener
import io.github.saltmango.navbeam.util.BuildId
import io.github.saltmango.navbeam.util.CrashGuard
import io.github.saltmango.navbeam.util.ExitInfoCollector

class NavBeamApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize Dash hardware & protocol profiles
        DashProfileManager.init(this)
        // Initialize Maps & Places via MapsKeyManager
        MapsKeyManager.applyKey(this)
        // Hash the installed APK off-thread so the build identity is ready to stamp into ride logs.
        BuildId.warm(this)
        // Initialize local-only diagnostics storage, crash guard, and abnormal exit collector.
        RideDiagnostics.init(this)
        CrashGuard.install(this)
        ExitInfoCollector.arm(this)
        ExitInfoCollector.collect(this)
        // Reinstalling an APK leaves the NotificationListenerService UNBOUND even though the grant
        // persists — which silently breaks now-playing + call mirroring. Ask the system to rebind it
        // on every launch so an over-the-top install self-heals without the user re-toggling access.
        rebindNotificationListener()
    }

    private fun rebindNotificationListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        runCatching {
            if (MediaInfoProvider.isAccessGranted(this)) {
                NotificationListenerService.requestRebind(
                    ComponentName(this, NavBeamNotificationListener::class.java)
                )
            }
        }
    }
}
