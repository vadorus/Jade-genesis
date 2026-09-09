package com.jadegenesis.mobile.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jadegenesis.mobile.screen.FocusCropActivity
import com.jadegenesis.mobile.screen.ScreenCaptureService

private const val V2_LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

private enum class MobileV2Tab(val label: String, val icon: ImageVector) {
    JADE("Jade", Icons.Filled.Chat),
    NODES("Nœuds", Icons.Filled.Devices),
    ACTIVITY("Activité", Icons.Filled.Timeline),
    MEMORY("Mémoire", Icons.Filled.Memory),
    ADMIN("Admin", Icons.Filled.AdminPanelSettings)
}

@Composable
fun JadeAppV2(vm: JadeViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    var tab by remember { mutableStateOf(MobileV2Tab.JADE) }

    val needsLocalNetworkPermission = Build.VERSION.SDK_INT >= 37
    var localNetworkGranted by remember {
        mutableStateOf(
            !needsLocalNetworkPermission ||
                ContextCompat.checkSelfPermission(context, V2_LOCAL_NETWORK_PERMISSION) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val localNetworkLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> localNetworkGranted = granted }

    val projectionManager = remember(context) {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    var captureMode by remember { mutableStateOf(ScreenCaptureService.MODE_IMMEDIATE) }
    var pendingArmedPermission by remember { mutableStateOf(false) }

    val screenCaptureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val requestedAt = System.currentTimeMillis()
            val requestedMode = captureMode
            ScreenCaptureService.startCapture(
                context = context,
                resultCode = result.resultCode,
                resultData = data,
                mode = requestedMode
            )
            if (requestedMode == ScreenCaptureService.MODE_ARMED) {
                vm.onPhoneScreenArmed()
            } else {
                vm.onPhoneScreenCaptureStarted(requestedAt)
            }
            captureMode = ScreenCaptureService.MODE_IMMEDIATE
        } else {
            vm.onPhoneScreenCaptureDenied()
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val shouldArm = pendingArmedPermission
        pendingArmedPermission = false
        if (granted && shouldArm) {
            captureMode = ScreenCaptureService.MODE_ARMED
            screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
        } else if (shouldArm) {
            vm.onPhoneScreenArmUnavailable()
        }
    }

    val perceptionActions = PerceptionActions(
        captureNow = {
            captureMode = ScreenCaptureService.MODE_IMMEDIATE
            screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
        },
        armObservation = {
            val needsNotificationPermission = Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            if (needsNotificationPermission) {
                pendingArmedPermission = true
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                captureMode = ScreenCaptureService.MODE_ARMED
                screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
            }
        },
        openCrop = {
            context.startActivity(Intent(context, FocusCropActivity::class.java))
        }
    )

    JadeGenesisTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = JadeColors.Bg
        ) {
            if (state.loading) {
                Box(
                    Modifier.fillMaxSize().background(JadeColors.Bg),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(color = JadeColors.Jade)
                        Text(
                            "JADE GENESIS",
                            fontFamily = JadeMono,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = JadeColors.JadeDim
                        )
                    }
                }
                return@Surface
            }

            Scaffold(
                modifier = Modifier.fillMaxSize().statusBarsPadding(),
                containerColor = JadeColors.Bg,
                bottomBar = {
                    NavigationBar(
                        modifier = Modifier.navigationBarsPadding(),
                        containerColor = JadeColors.NavBg,
                        tonalElevation = 0.dp
                    ) {
                        MobileV2Tab.entries.forEach { item ->
                            NavigationBarItem(
                                selected = tab == item,
                                onClick = { tab = item },
                                icon = {
                                    Icon(
                                        item.icon,
                                        contentDescription = item.label,
                                        tint = if (tab == item) JadeColors.Jade else JadeColors.Muted3
                                    )
                                },
                                label = {
                                    Text(
                                        item.label,
                                        fontSize = 11.sp,
                                        fontWeight = if (tab == item) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = JadeColors.Jade,
                                    selectedTextColor = JadeColors.Jade,
                                    unselectedIconColor = JadeColors.Muted3,
                                    unselectedTextColor = JadeColors.Muted3,
                                    indicatorColor = JadeColors.Jade.copy(alpha = 0.11f)
                                )
                            )
                        }
                    }
                }
            ) { innerPadding ->
                val pageModifier = Modifier.fillMaxSize().padding(innerPadding)
                when (tab) {
                    MobileV2Tab.JADE -> JadeHomeV2(
                        state = state,
                        vm = vm,
                        actions = perceptionActions,
                        modifier = pageModifier
                    )
                    MobileV2Tab.NODES -> JadeNodesV2(
                        state = state,
                        vm = vm,
                        needsLocalNetworkPermission = needsLocalNetworkPermission,
                        localNetworkGranted = localNetworkGranted,
                        requestLocalNetwork = {
                            localNetworkLauncher.launch(V2_LOCAL_NETWORK_PERMISSION)
                        },
                        modifier = pageModifier
                    )
                    MobileV2Tab.ACTIVITY -> JadeActivityV2(
                        state = state,
                        vm = vm,
                        modifier = pageModifier
                    )
                    MobileV2Tab.MEMORY -> JadeMemoryV2(
                        state = state,
                        vm = vm,
                        modifier = pageModifier
                    )
                    MobileV2Tab.ADMIN -> JadeAdminV2(
                        state = state,
                        vm = vm,
                        modifier = pageModifier
                    )
                }
            }
        }
    }
}
