package com.parental.focus.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.parental.focus.data.AppInfo
import com.parental.focus.data.AppPreferences
import com.parental.focus.data.BlockSchedule
import com.parental.focus.service.BlockerForegroundService
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = AppPreferences(applicationContext)

        // First-launch: go to permissions setup
        if (!prefs.isSetupComplete()) {
            startActivity(Intent(this, PermissionsActivity::class.java))
            finish()
            return
        }

        // Start foreground service
        val svc = Intent(this, BlockerForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc)
        else startService(svc)

        setContent {
            ParentalFocusApp()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ParentalFocusApp() {
    val navController = rememberNavController()
    val navBackStack by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStack?.destination?.route ?: "schedules"

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF1565C0),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFBBDEFB),
            secondary = Color(0xFF00897B),
            background = Color(0xFFF5F5F5),
            surface = Color.White
        )
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("🛡️ ", fontSize = 20.sp)
                            Text("Parental Focus", fontWeight = FontWeight.Bold)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xFF1565C0),
                        titleContentColor = Color.White
                    )
                )
            },
            bottomBar = {
                NavigationBar(containerColor = Color.White) {
                    NavigationBarItem(
                        selected = currentRoute == "schedules",
                        onClick = { navController.navigate("schedules") { launchSingleTop = true } },
                        icon = { Icon(Icons.Default.Schedule, null) },
                        label = { Text("Schedules") }
                    )
                    NavigationBarItem(
                        selected = currentRoute == "apps",
                        onClick = { navController.navigate("apps") { launchSingleTop = true } },
                        icon = { Icon(Icons.Default.Apps, null) },
                        label = { Text("Blocked Apps") }
                    )
                    NavigationBarItem(
                        selected = currentRoute == "settings",
                        onClick = { navController.navigate("settings") { launchSingleTop = true } },
                        icon = { Icon(Icons.Default.Settings, null) },
                        label = { Text("Settings") }
                    )
                }
            }
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = "schedules",
                modifier = Modifier.padding(padding)
            ) {
                composable("schedules") { SchedulesScreen() }
                composable("apps") { BlockedAppsScreen() }
                composable("settings") { SettingsScreen() }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Schedules Screen
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchedulesScreen() {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    var schedules by remember { mutableStateOf(prefs.getSchedules()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingSchedule by remember { mutableStateOf<BlockSchedule?>(null) }

    Box(Modifier.fillMaxSize().background(Color(0xFFF5F5F5))) {
        if (schedules.isEmpty()) {
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.Schedule, null, tint = Color(0xFFBDBDBD), modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(12.dp))
                Text("No schedules yet.", color = Color(0xFF9E9E9E), fontSize = 16.sp)
                Text("Tap + to add one.", color = Color(0xFFBDBDBD), fontSize = 14.sp)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(schedules, key = { it.id }) { schedule ->
                    ScheduleCard(
                        schedule = schedule,
                        onToggle = {
                            val updated = schedule.copy(enabled = !schedule.enabled)
                            prefs.addSchedule(updated)
                            schedules = prefs.getSchedules()
                        },
                        onEdit = { editingSchedule = schedule; showAddDialog = true },
                        onDelete = { prefs.removeSchedule(schedule.id); schedules = prefs.getSchedules() }
                    )
                }
                item { Spacer(Modifier.height(72.dp)) }
            }
        }

        FloatingActionButton(
            onClick = { editingSchedule = null; showAddDialog = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            containerColor = Color(0xFF1565C0)
        ) {
            Icon(Icons.Default.Add, "Add schedule", tint = Color.White)
        }
    }

    if (showAddDialog) {
        ScheduleDialog(
            existing = editingSchedule,
            onDismiss = { showAddDialog = false; editingSchedule = null },
            onSave = { schedule ->
                prefs.addSchedule(schedule)
                schedules = prefs.getSchedules()
                showAddDialog = false
                editingSchedule = null
            }
        )
    }
}

@Composable
private fun ScheduleCard(
    schedule: BlockSchedule,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val fmt = remember { SimpleDateFormat("EEE dd MMM, HH:mm", Locale.getDefault()) }
    val isActive = remember(schedule) { schedule.isActiveAt(System.currentTimeMillis()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        schedule.label.ifBlank { "Unnamed Schedule" },
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    if (schedule.isOneShot) {
                        Text("From: ${fmt.format(Date(schedule.startEpochMs))}", fontSize = 13.sp, color = Color(0xFF616161))
                        Text("Until: ${fmt.format(Date(schedule.endEpochMs))}", fontSize = 13.sp, color = Color(0xFF616161))
                    } else {
                        Text("Repeats: ${formatDays(schedule.repeatDays)}", fontSize = 13.sp, color = Color(0xFF616161))
                        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
                        Text("${timeFmt.format(Date(schedule.startEpochMs))} – ${timeFmt.format(Date(schedule.endEpochMs))}", fontSize = 13.sp, color = Color(0xFF616161))
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Switch(checked = schedule.enabled, onCheckedChange = { onToggle() })
                    if (isActive && schedule.enabled) {
                        Text("● Active", color = Color(0xFF388E3C), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onEdit) { Text("Edit") }
                TextButton(onClick = onDelete) { Text("Delete", color = Color(0xFFD32F2F)) }
            }
        }
    }
}

private fun formatDays(mask: Int): String {
    val names = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    return (0..6).filter { (mask and (1 shl it)) != 0 }.joinToString(", ") { names[it] }
}

// ─────────────────────────────────────────────────────────────────────────────
// Schedule Dialog (Add / Edit)
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleDialog(
    existing: BlockSchedule?,
    onDismiss: () -> Unit,
    onSave: (BlockSchedule) -> Unit
) {
    var label by remember { mutableStateOf(existing?.label ?: "") }
    var repeating by remember { mutableStateOf((existing?.repeatDays ?: 0) != 0) }
    var repeatDays by remember { mutableStateOf(existing?.repeatDays ?: BlockSchedule.WEEKDAYS) }

    val now = System.currentTimeMillis()
    var startMs by remember { mutableStateOf(existing?.startEpochMs ?: now) }
    var endMs   by remember { mutableStateOf(existing?.endEpochMs   ?: (now + 2 * 3600_000L)) }

    val dayNames = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    val fmt = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add Schedule" else "Edit Schedule") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label (e.g. Homework time)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Repeating schedule", Modifier.weight(1f))
                    Switch(checked = repeating, onCheckedChange = { repeating = it })
                }

                if (repeating) {
                    Text("Repeat days:", fontSize = 13.sp, color = Color(0xFF616161))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        dayNames.forEachIndexed { i, name ->
                            val bit = 1 shl i
                            FilterChip(
                                selected = (repeatDays and bit) != 0,
                                onClick = { repeatDays = repeatDays xor bit },
                                label = { Text(name, fontSize = 11.sp) }
                            )
                        }
                    }
                    Text("Start time: ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(startMs))}", fontSize = 13.sp)
                    Text("End time:   ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(endMs))}", fontSize = 13.sp)
                    Text("(Tap to edit times — use a TimePicker in the next iteration)", fontSize = 11.sp, color = Color(0xFFBDBDBD))
                } else {
                    Text("Start: ${fmt.format(Date(startMs))}", fontSize = 13.sp)
                    Text("End:   ${fmt.format(Date(endMs))}", fontSize = 13.sp)
                    Text("(Date/time picker — tap to set)", fontSize = 11.sp, color = Color(0xFFBDBDBD))
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val schedule = BlockSchedule(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    label = label.ifBlank { "Schedule" },
                    startEpochMs = startMs,
                    endEpochMs = endMs,
                    repeatDays = if (repeating) repeatDays else 0,
                    enabled = existing?.enabled ?: true
                )
                onSave(schedule)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Blocked Apps Screen
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun BlockedAppsScreen() {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val pm = context.packageManager
        val blocked = prefs.getBlockedPackages()
        val installed = pm.getInstalledApplications(0)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .map { info ->
                AppInfo(
                    packageName = info.packageName,
                    appName = pm.getApplicationLabel(info).toString(),
                    isBlocked = info.packageName in blocked,
                    isSystemApp = (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                )
            }
            .sortedWith(compareByDescending<AppInfo> { it.isBlocked }.thenBy { it.appName })
        apps = installed
        loading = false
    }

    Column(Modifier.fillMaxSize().background(Color(0xFFF5F5F5))) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search apps…") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            modifier = Modifier.fillMaxWidth().padding(16.dp, 12.dp, 16.dp, 4.dp),
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            val filtered = apps.filter {
                it.appName.contains(searchQuery, ignoreCase = true) ||
                it.packageName.contains(searchQuery, ignoreCase = true)
            }
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item { Spacer(Modifier.height(4.dp)) }
                items(filtered, key = { it.packageName }) { app ->
                    AppBlockRow(app = app, onToggle = { pkg, block ->
                        if (block) prefs.addBlockedPackage(pkg)
                        else prefs.removeBlockedPackage(pkg)
                        apps = apps.map { if (it.packageName == pkg) it.copy(isBlocked = block) else it }
                    })
                }
                item { Spacer(Modifier.height(72.dp)) }
            }
        }
    }
}

@Composable
private fun AppBlockRow(app: AppInfo, onToggle: (String, Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (app.isBlocked) Color(0xFFFFEBEE) else Color.White
        ),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(app.appName, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                Text(app.packageName, fontSize = 11.sp, color = Color(0xFF9E9E9E))
            }
            if (app.isBlocked) {
                Text("BLOCKED", fontSize = 10.sp, color = Color(0xFFD32F2F),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 8.dp))
            }
            Switch(
                checked = app.isBlocked,
                onCheckedChange = { onToggle(app.packageName, it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFFD32F2F),
                    checkedTrackColor = Color(0xFFFFCDD2)
                )
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Settings Screen
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    var blockingEnabled by remember { mutableStateOf(prefs.isBlockingEnabled()) }
    var dimOnBlock by remember { mutableStateOf(prefs.isDimOnBlockEnabled()) }
    var faceEnrolled by remember { mutableStateOf(prefs.isFaceEnrolled()) }

    Column(
        Modifier.fillMaxSize().background(Color(0xFFF5F5F5))
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Master toggle
        SettingsSection(title = "Blocking") {
            SettingsToggleRow(
                icon = Icons.Default.Shield,
                title = "Blocking Enabled",
                subtitle = "Master on/off for all app restrictions",
                checked = blockingEnabled,
                onToggle = { blockingEnabled = it; prefs.setBlockingEnabled(it) }
            )
            SettingsToggleRow(
                icon = Icons.Default.Brightness3,
                title = "Dim Screen on Block",
                subtitle = "Lower brightness when a block triggers",
                checked = dimOnBlock,
                onToggle = { dimOnBlock = it; prefs.setDimOnBlock(it) }
            )
        }

        // Face enrollment
        SettingsSection(title = "Face Enrollment") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(1.dp)
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.FaceRetouchingNatural,
                        null,
                        tint = Color(0xFF1565C0),
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (faceEnrolled) "Face enrolled ✓" else "No face enrolled",
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            if (faceEnrolled)
                                "The child's face is registered. Parent can override by verifying identity on the block screen."
                            else
                                "Enroll the child's face to enable identity-based blocking.",
                            fontSize = 12.sp,
                            color = Color(0xFF757575)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            context.startActivity(Intent(context, FaceEnrollmentActivity::class.java))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0))
                    ) { Text(if (faceEnrolled) "Re-enroll" else "Enroll") }
                }
                if (faceEnrolled) {
                    TextButton(
                        onClick = { prefs.clearFaceEnrollment(); faceEnrolled = false },
                        modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
                    ) { Text("Remove enrolled face", color = Color(0xFFD32F2F), fontSize = 12.sp) }
                }
            }
        }

        // Permissions quick-links
        SettingsSection(title = "Permissions") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(1.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    TextButton(onClick = {
                        context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        })
                    }) { Text("Open Accessibility Settings") }
                    TextButton(onClick = {
                        context.startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        })
                    }) { Text("Open Usage Access Settings") }
                    TextButton(onClick = {
                        context.startActivity(Intent(context, PermissionsActivity::class.java))
                    }) { Text("Review All Permissions") }
                }
            }
        }

        // About
        SettingsSection(title = "About") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(1.dp)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Parental Focus", fontWeight = FontWeight.Bold)
                    Text("Version 1.0", color = Color(0xFF9E9E9E), fontSize = 13.sp)
                    Text("Package: com.parental.focus", color = Color(0xFF9E9E9E), fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Uses Accessibility Services to detect and block restricted apps during scheduled sessions.",
                        fontSize = 12.sp,
                        color = Color(0xFF757575)
                    )
                }
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title.uppercase(),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF1565C0),
            modifier = Modifier.padding(start = 4.dp)
        )
        content()
    }
}

@Composable
private fun SettingsToggleRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Color(0xFF1565C0), modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                Text(subtitle, color = Color(0xFF757575), fontSize = 12.sp)
            }
            Switch(checked = checked, onCheckedChange = onToggle)
        }
    }
}
