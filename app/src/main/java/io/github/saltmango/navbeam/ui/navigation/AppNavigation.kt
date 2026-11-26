package io.github.saltmango.navbeam.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Directions
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.EaseOutQuad
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.saltmango.navbeam.dash.profile.DashProfileManager
import io.github.saltmango.navbeam.data.DiagReporter
import io.github.saltmango.navbeam.data.RideDiagnostics
import io.github.saltmango.navbeam.ui.NavBeamIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.saltmango.navbeam.ui.screens.*
import io.github.saltmango.navbeam.ui.theme.*
import io.github.saltmango.navbeam.viewmodel.AppViewModel
import io.github.saltmango.navbeam.viewmodel.ConnStage
import io.github.saltmango.navbeam.viewmodel.DashViewModel
import io.github.saltmango.navbeam.viewmodel.RouteViewModel
import io.github.saltmango.navbeam.viewmodel.RidesViewModel

sealed class Screen(val route: String) {
    object Login        : Screen("login")
    object ProfileSetup : Screen("profile_setup")
    object Home         : Screen("home")
    object Route        : Screen("route")
    object Dash         : Screen("dash")
    object Menu         : Screen("menu")
    object Rides        : Screen("rides")
}

private data class NavTab(val screen: Screen, val icon: ImageVector, val label: String)

private val bottomTabs = listOf(
    NavTab(Screen.Home,   NavBeamIcons.Home, "Home"),
    NavTab(Screen.Route,  Icons.Outlined.Directions, "Route"),
    NavTab(Screen.Menu,   NavBeamIcons.Gear, "Menu"),
)

@Composable
fun AppNavigation(
    appViewModel: AppViewModel = viewModel(),
    dashViewModel: DashViewModel = viewModel(),
    routeViewModel: RouteViewModel = viewModel(),
    ridesViewModel: RidesViewModel = viewModel(),
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val activity = remember(ctx) {
        var context = ctx
        while (context is android.content.ContextWrapper) {
            if (context is android.app.Activity) break
            context = context.baseContext
        }
        context as? android.app.Activity
    }

    // Analytics screen_view per destination
    LaunchedEffect(currentRoute) {
        currentRoute?.let { io.github.saltmango.navbeam.util.Telemetry.logScreen(it) }
    }

    fun navigateTab(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    val startDest = remember {
        when {
            !io.github.saltmango.navbeam.data.AuthPrefs.isOnboarded(ctx) -> Screen.Login.route
            !DashProfileManager.hasProfile() && !io.github.saltmango.navbeam.data.AuthPrefs.isProfileSkipped(ctx) ->
                Screen.ProfileSetup.route
            else -> Screen.Home.route
        }
    }

    val showBottomNav = currentRoute in listOf(
        Screen.Home.route, Screen.Route.route, Screen.Dash.route,
        Screen.Menu.route, Screen.Rides.route,
    )
    val routeState by routeViewModel.state.collectAsState()
    val themeMode by appViewModel.themeMode.collectAsState()

    val dashUiState = dashViewModel.ui.collectAsState()
    val conn by remember {
        derivedStateOf {
            when (dashUiState.value.stage) {
                ConnStage.STREAMING -> io.github.saltmango.navbeam.viewmodel.ConnectionState.Connected
                ConnStage.WIFI, ConnStage.AUTH -> io.github.saltmango.navbeam.viewmodel.ConnectionState.Searching
                else -> io.github.saltmango.navbeam.viewmodel.ConnectionState.Offline
            }
        }
    }

    // Prefetch map tiles the moment a destination resolves
    LaunchedEffect(routeState.destination?.lat, routeState.destination?.lng) {
        val d = routeState.destination
        if (d != null) dashViewModel.prefetchTiles(d.lat, d.lng)
    }

    // Auto-navigate to Route when a Maps share arrives
    LaunchedEffect(routeState.pendingNavigate, currentRoute) {
        if (routeState.pendingNavigate && currentRoute != null && currentRoute != Screen.Login.route) {
            if (currentRoute != Screen.Route.route) {
                navController.navigate(Screen.Route.route) { launchSingleTop = true }
            }
            routeViewModel.onNavigated()
        }
    }

    NavBeamTheme(mode = themeMode) {
        val colors = NavBeamTheme.colors

        LaunchedEffect(currentRoute, colors, activity) {
            activity?.window?.let { window ->
                val isTransparentScreen = currentRoute in listOf(Screen.Route.route, Screen.Dash.route)
                @Suppress("DEPRECATION")
                window.statusBarColor = if (isTransparentScreen) {
                    android.graphics.Color.TRANSPARENT
                } else {
                    colors.surf1.toArgb()
                }
            }
        }

        val applyStatusBarPadding = currentRoute !in listOf(Screen.Route.route, Screen.Dash.route)
        val statusBarBg = if (applyStatusBarPadding && currentRoute != Screen.Login.route) {
            colors.surf1
        } else {
            colors.bg1
        }
        Column(
            Modifier
                .fillMaxSize()
                .background(statusBarBg)
                .then(if (applyStatusBarPadding) Modifier.statusBarsPadding() else Modifier)
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .background(colors.bg1)
            ) {
                val tabRoutes = remember {
                    listOf(
                        Screen.Home.route,
                        Screen.Route.route,
                        Screen.Menu.route
                    )
                }
                fun getTabIndex(route: String?): Int = tabRoutes.indexOf(route)

                NavHost(
                    navController = navController,
                    startDestination = startDest,
                    enterTransition = {
                        val from = getTabIndex(initialState.destination.route)
                        val to = getTabIndex(targetState.destination.route)
                        if (from == -1 || to == -1) {
                            slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(300, easing = EaseOutQuad)) + fadeIn(tween(200))
                        } else if (to > from) {
                            slideInHorizontally(initialOffsetX = { it / 6 }, animationSpec = tween(250, easing = EaseOutQuad)) + fadeIn(tween(200))
                        } else {
                            slideInHorizontally(initialOffsetX = { -it / 6 }, animationSpec = tween(250, easing = EaseOutQuad)) + fadeIn(tween(200))
                        }
                    },
                    exitTransition = {
                        val from = getTabIndex(initialState.destination.route)
                        val to = getTabIndex(targetState.destination.route)
                        if (from == -1 || to == -1) {
                            slideOutHorizontally(targetOffsetX = { -it }, animationSpec = tween(300, easing = EaseOutQuad)) + fadeOut(tween(200))
                        } else if (to > from) {
                            slideOutHorizontally(targetOffsetX = { -it / 6 }, animationSpec = tween(250, easing = EaseOutQuad)) + fadeOut(tween(200))
                        } else {
                            slideOutHorizontally(targetOffsetX = { it / 6 }, animationSpec = tween(250, easing = EaseOutQuad)) + fadeOut(tween(200))
                        }
                    },
                    popEnterTransition = {
                        val from = getTabIndex(initialState.destination.route)
                        val to = getTabIndex(targetState.destination.route)
                        if (from == -1 || to == -1) {
                            slideInHorizontally(initialOffsetX = { -it }, animationSpec = tween(300, easing = EaseOutQuad)) + fadeIn(tween(200))
                        } else if (to > from) {
                            slideInHorizontally(initialOffsetX = { it / 6 }, animationSpec = tween(250, easing = EaseOutQuad)) + fadeIn(tween(200))
                        } else {
                            slideInHorizontally(initialOffsetX = { -it / 6 }, animationSpec = tween(250, easing = EaseOutQuad)) + fadeIn(tween(200))
                        }
                    },
                    popExitTransition = {
                        val from = getTabIndex(initialState.destination.route)
                        val to = getTabIndex(targetState.destination.route)
                        if (from == -1 || to == -1) {
                            slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(300, easing = EaseOutQuad)) + fadeOut(tween(200))
                        } else if (to > from) {
                            slideOutHorizontally(targetOffsetX = { -it / 6 }, animationSpec = tween(250, easing = EaseOutQuad)) + fadeOut(tween(200))
                        } else {
                            slideOutHorizontally(targetOffsetX = { it / 6 }, animationSpec = tween(250, easing = EaseOutQuad)) + fadeOut(tween(200))
                        }
                    }
                ) {
                    composable(Screen.Login.route) {
                        LoginScreen(
                            themeMode = themeMode,
                            onThemeModeChange = { appViewModel.setThemeMode(it) },
                            onContinue = {
                                navController.navigate(Screen.ProfileSetup.route)
                            },
                        )
                    }

                    composable(Screen.ProfileSetup.route) {
                        val prevRoute = navController.previousBackStackEntry?.destination?.route
                        val isFromApp = prevRoute in listOf(Screen.Menu.route, Screen.Home.route)
                        ProfileSetupScreen(
                            onComplete = {
                                io.github.saltmango.navbeam.data.AuthPrefs.setOnboarded(ctx, true)
                                io.github.saltmango.navbeam.data.AuthPrefs.setProfileSkipped(ctx, false)
                                navController.navigate(Screen.Home.route) {
                                    popUpTo(0) { inclusive = true }
                                }
                            },
                            onBack = if (isFromApp) {
                                { navController.popBackStack() }
                            } else null,
                            onSkip = if (!isFromApp) {
                                {
                                    io.github.saltmango.navbeam.data.AuthPrefs.setOnboarded(ctx, true)
                                    io.github.saltmango.navbeam.data.AuthPrefs.setProfileSkipped(ctx, true)
                                    navController.navigate(Screen.Home.route) {
                                        popUpTo(0) { inclusive = true }
                                    }
                                }
                            } else null,
                            isOnboarding = !isFromApp,
                            dashViewModel = dashViewModel,
                        )
                    }

                    composable(Screen.Home.route) {
                        HomeScreen(
                            conn = conn,
                            onNavigate = { dest ->
                                when (dest) {
                                    "route" -> navigateTab(Screen.Route.route)
                                    "dash" -> navigateTab(Screen.Dash.route)
                                    "rides" -> navController.navigate(Screen.Rides.route)
                                    "menu" -> navigateTab(Screen.Menu.route)
                                    "profile_setup" -> navController.navigate(Screen.ProfileSetup.route)
                                }
                            },
                            routeViewModel = routeViewModel,
                            ridesViewModel = ridesViewModel,
                            dashViewModel = dashViewModel,
                        )
                    }

                    composable(Screen.Route.route) {
                        RouteScreen(
                            routeViewModel = routeViewModel,
                            dashViewModel = dashViewModel,
                            onBack = { navController.popBackStack() },
                            onSentToDash = { destName ->
                                 dashViewModel.setDestination(
                                     name = destName,
                                     lat  = routeState.destination?.lat,
                                     lng  = routeState.destination?.lng,
                                     stops = routeState.stops.mapNotNull { it.location }
                                 )
                                 if (dashUiState.value.stage == ConnStage.CONNECTED) {
                                     dashViewModel.startProjection()
                                 } else if (dashUiState.value.stage != ConnStage.STREAMING) {
                                     dashViewModel.connect(autoStartProj = true)
                                 }
                                 navController.navigate(Screen.Dash.route) {
                                     popUpTo(Screen.Home.route)
                                 }
                            },
                        )
                    }

                    composable(Screen.Dash.route) {
                        DashScreen(
                            vm = dashViewModel,
                            onStopNavigation = {
                                routeViewModel.clear()
                                navController.popBackStack(Screen.Home.route, inclusive = false)
                            }
                        )
                    }

                    composable(Screen.Menu.route) {
                        MenuScreen(
                            appViewModel = appViewModel,
                            dashViewModel = dashViewModel,
                            conn = conn,
                            onNavigate = { dest ->
                                when (dest) {
                                    "rides" -> navController.navigate(Screen.Rides.route)
                                    "dash" -> navigateTab(Screen.Dash.route)
                                    "profile_setup" -> navController.navigate(Screen.ProfileSetup.route)
                                }
                            }
                        )
                    }

                    composable(Screen.Rides.route) {
                        RidesScreen(
                            onBack = { navController.popBackStack() },
                        )
                    }

                }
            }

            io.github.saltmango.navbeam.ui.components.UpdatePrompt()

            var pendingCrash by remember { mutableStateOf<java.io.File?>(null) }
            var crashActionBusy by remember { mutableStateOf(false) }
            val crashScope = rememberCoroutineScope()
            LaunchedEffect(currentRoute) {
                if (pendingCrash == null && currentRoute != null && currentRoute != Screen.Login.route) {
                    pendingCrash = withContext(Dispatchers.IO) {
                        DiagReporter.pendingCrashReport(ctx)
                    }
                }
            }
            pendingCrash?.let { crashFile ->
                val summary = remember(crashFile) { DiagReporter.summarizeCrash(crashFile) }
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = {
                        if (!crashActionBusy) {
                            DiagReporter.markCrashPrompted(ctx, crashFile)
                            pendingCrash = null
                        }
                    },
                    title = {
                        Text("NavBeam closed unexpectedly", color = colors.textHi)
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                summary,
                                color = colors.alert,
                                fontSize = 12.5.sp,
                                fontFamily = GeistMonoFamily,
                            )
                            Text(
                                "Send an anonymized bug report to GitHub (crash trace + ride performance KPIs). No personal, location, route, or account data is included.",
                                color = colors.textMid,
                                fontSize = 12.5.sp,
                            )
                        }
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(
                            enabled = !crashActionBusy,
                            onClick = {
                                crashActionBusy = true
                                crashScope.launch {
                                    val uri = withContext(Dispatchers.IO) {
                                        val zip = RideDiagnostics.exportZip(ctx)
                                        val savedPath = zip?.let { DiagReporter.saveZipToDownloads(ctx, it) }
                                        DiagReporter.buildGitHubIssueUri(ctx, crashFile, savedPath)
                                    }
                                    DiagReporter.markCrashPrompted(ctx, crashFile)
                                    DiagReporter.openGitHubIssue(ctx, uri)
                                    crashActionBusy = false
                                    pendingCrash = null
                                }
                            }
                        ) {
                            Text(if (crashActionBusy) "Preparing…" else "Report on GitHub", color = colors.primary)
                        }
                    },
                    dismissButton = {
                        Row {
                            androidx.compose.material3.TextButton(
                                enabled = !crashActionBusy,
                                onClick = {
                                    crashActionBusy = true
                                    crashScope.launch {
                                        val zip = withContext(Dispatchers.IO) { RideDiagnostics.exportZip(ctx) }
                                        DiagReporter.markCrashPrompted(ctx, crashFile)
                                        if (zip != null) {
                                            runCatching { ctx.startActivity(DiagReporter.shareZipIntent(ctx, zip)) }
                                        }
                                        crashActionBusy = false
                                        pendingCrash = null
                                    }
                                }
                            ) {
                                Text("Share .zip", color = colors.textHi)
                            }
                            androidx.compose.material3.TextButton(
                                enabled = !crashActionBusy,
                                onClick = {
                                    DiagReporter.markCrashPrompted(ctx, crashFile)
                                    pendingCrash = null
                                }
                            ) {
                                Text("Dismiss", color = colors.textMid)
                            }
                        }
                    },
                    containerColor = colors.surf1,
                )
            }

            if (showBottomNav) {
                NavBeamBottomNav(
                    currentRoute = currentRoute,
                    onNavSelect = { screen -> navigateTab(screen.route) },
                )
            }
        }
    }
}

@Composable
private fun NavBeamBottomNav(
    currentRoute: String?,
    onNavSelect: (Screen) -> Unit,
) {
    val colors = NavBeamTheme.colors
    val shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 24.dp,
                shape = shape,
                ambientColor = colors.shadow,
                spotColor = colors.shadow,
            )
            .background(colors.surf1, shape = shape)
            .navigationBarsPadding()
    ) {
        io.github.saltmango.navbeam.ui.components.NavBeamDivider()
        Row(

            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            bottomTabs.forEach { tab ->
                val active = when (tab.screen) {
                    Screen.Menu -> currentRoute in listOf(
                        Screen.Menu.route, Screen.Rides.route
                    )
                    else -> currentRoute == tab.screen.route
                }

                val pillAlpha by animateFloatAsState(
                    targetValue = if (active) 1f else 0f,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    label = "pillAlpha"
                )

                val iconTint by animateColorAsState(
                    targetValue = if (active) colors.primary else colors.textLo,
                    animationSpec = spring(stiffness = Spring.StiffnessMedium),
                    label = "iconTint"
                )

                val scale by animateFloatAsState(
                    targetValue = if (active) 1.12f else 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    ),
                    label = "iconScale"
                )

                val shadowOffset by androidx.compose.animation.core.animateDpAsState(
                    targetValue = if (active) 0.8.dp else 0.dp,
                    animationSpec = spring(stiffness = Spring.StiffnessMedium),
                    label = "shadowOffset"
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(64.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onNavSelect(tab.screen) },
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(28.dp)
                        ) {
                            // 3D Shadow Icon (offset positive x/y)
                            if (active) {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = null,
                                    tint = colors.primary.copy(alpha = 0.3f * pillAlpha),
                                    modifier = Modifier
                                        .size(24.dp)
                                        .scale(scale)
                                        .offset(x = shadowOffset, y = shadowOffset)
                                )
                            }
                            // Main Foreground Icon (offset negative x/y when active to appear raised)
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.label,
                                tint = iconTint,
                                modifier = Modifier
                                    .size(24.dp)
                                    .scale(scale)
                                    .offset(x = -shadowOffset, y = -shadowOffset)
                            )
                        }
                        Text(
                            text = tab.label,
                            fontSize = 10.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            color = iconTint,
                            modifier = Modifier.padding(top = 3.dp)
                        )
                    }
                }
            }
        }
    }
}

