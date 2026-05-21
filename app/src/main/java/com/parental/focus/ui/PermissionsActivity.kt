package com.parental.focus.ui

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.parental.focus.data.AppPreferences
import com.parental.focus.service.AppBlockerAccessibilityService

class PermissionsActivity : ComponentActivity() {

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            // Recompose will re-check states
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PermissionsScreen(
                onRequestRuntimePermissions = {
                    val perms = buildList {
                        add(Manifest.permission.CAMERA)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    requestPermissions.launch(perms.toTypedArray())
                },
                onAllGranted = {
                    AppPreferences(this).markSetupComplete()
                    startActivity(Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    })
                    finish()
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PermissionsScreen(
    onRequestRuntimePermissions: () -> Unit,
    onAllGranted: () -> Unit
) {
    val context = LocalContext.current
    var tick by remember { mutableStateOf(0) }

    // Re-check on resume
    LaunchedEffect(tick) {}

    fun isAccessibilityEnabled(): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val serviceId = "${context.packageName}/${AppBlockerAccessibilityService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return TextUtils.SimpleStringSplitter(':').apply { setString(enabledServices) }
            .any { it.equals(serviceId, ignoreCase = true) }
    }

    fun isUsageStatsGranted(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun isOverlayGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context)
        else true

    fun isBatteryExempt(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun isBrightnessGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            Settings.System.canWrite(context)
        else true

    val accessibilityOk by remember(tick) { derivedStateOf { isAccessibilityEnabled() } }
    val usageOk        by remember(tick) { derivedStateOf { isUsageStatsGranted() } }
    val overlayOk      by remember(tick) { derivedStateOf { isOverlayGranted() } }
    val batteryOk      by remember(tick) { derivedStateOf { isBatteryExempt() } }
    val brightnessOk   by remember(tick) { derivedStateOf { isBrightnessGranted() } }

    val allGranted = accessibilityOk && usageOk && overlayOk && batteryOk

    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Set Up Parental Focus", fontWeight = FontWeight.Bold) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xFF1565C0),
                        titleContentColor = Color.White
                    )
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .background(Color(0xFFF5F5F5))
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Grant all permissions to enable full protection.",
                    color = Color(0xFF616161),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                PermissionRow(
                    icon = Icons.Default.Accessibility,
                    title = "Accessibility Service",
                    description = "Detects blocked apps and sends user to home screen.",
                    granted = accessibilityOk,
                    onGrant = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        })
                    }
                )

                PermissionRow(
                    icon = Icons.Default.QueryStats,
                    title = "Usage Access",
                    description = "Fallback app-detection method.",
                    granted = usageOk,
                    onGrant = {
                        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        })
                    }
                )

                PermissionRow(
                    icon = Icons.Default.Layers,
                    title = "Display Over Other Apps",
                    description = "Show the block screen over restricted apps.",
                    granted = overlayOk,
                    onGrant = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                            )
                        }
                    }
                )

                PermissionRow(
                    icon = Icons.Default.BatteryChargingFull,
                    title = "Battery Optimization",
                    description = "Prevent Android from killing the blocking service.",
                    granted = batteryOk,
                    onGrant = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${context.packageName}")
                                ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                            )
                        }
                    }
                )

                val cameraOk by remember(tick) {
                    derivedStateOf {
                        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                            PackageManager.PERMISSION_GRANTED
                    }
                }

                PermissionRow(
                    icon = Icons.Default.CameraAlt,
                    title = "Camera + Notifications",
                    description = "Face enrollment and blocking alerts.",
                    granted = cameraOk,
                    onGrant = onRequestRuntimePermissions
                )

                PermissionRow(
                    icon = Icons.Default.Brightness6,
                    title = "Write System Settings (optional)",
                    description = "Auto-dim screen during focus sessions.",
                    granted = brightnessOk,
                    onGrant = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                    Uri.parse("package:${context.packageName}")
                                ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                            )
                        }
                    }
                )

                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = {
                        if (allGranted) onAllGranted() else tick++
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (allGranted) Color(0xFF388E3C) else Color(0xFF1565C0)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        if (allGranted) "Continue to Setup ✓" else "Re-check Permissions",
                        fontSize = 16.sp,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(
    icon: ImageVector,
    title: String,
    description: String,
    granted: Boolean,
    onGrant: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (granted) Color(0xFF388E3C) else Color(0xFF1565C0),
                modifier = Modifier.size(36.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(description, color = Color(0xFF757575), fontSize = 12.sp)
            }
            Spacer(Modifier.width(8.dp))
            if (granted) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Granted",
                    tint = Color(0xFF388E3C),
                    modifier = Modifier.size(28.dp)
                )
            } else {
                OutlinedButton(
                    onClick = onGrant,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("Grant", fontSize = 13.sp)
                }
            }
        }
    }
}
