# Parental Focus

A pure Kotlin Android app that blocks selected apps during scheduled time windows,
with face-based child identification and parent face-unlock override.

---

## Features

| Feature | Description |
|---|---|
| **Accessibility-based blocking** | Uses `AccessibilityService` to detect any blocked app in the foreground and immediately fires `GLOBAL_ACTION_HOME`, sending the user back to the launcher. |
| **Scheduled time windows** | One-shot (absolute start/end) or repeating (day-of-week + time-of-day) schedules. Blocking only activates within the configured window. |
| **App picker** | Searchable list of all installed launchable apps. Toggle any app to block it. |
| **Child face enrollment** | Front-camera + ML Kit Face Detection captures the child's facial landmark signature and stores it securely in `SharedPreferences`. |
| **Parent face-unlock** | The `BlockOverlayActivity` has a "Verify Parent Face" button. A successful face match grants a 5-minute parent override and dismisses the overlay. |
| **Foreground service** | `BlockerForegroundService` keeps the process alive, preventing OEM battery managers from killing the accessibility service. |
| **Boot persistence** | `BootReceiver` restarts the foreground service after device boot or app update. |
| **Permissions wizard** | First-launch `PermissionsActivity` walks through all required special-access grants (Accessibility, Usage Stats, Overlay, Battery, Camera). |

---

## Requirements

- **Android 8.0 (API 26)** or higher
- **Android Studio Hedgehog (2023.1.1)** or newer
- **JDK 17**

---

## Build & Run

```bash
# Open in Android Studio
File → Open → select the ParentalFocus/ directory

# Or build from command line
./gradlew assembleDebug
```

---

## First-Run Permissions (in order)

1. **Accessibility Service** → Settings → Accessibility → Installed services → Parental Focus – App Blocker → ON
2. **Usage Access** → Settings → Digital Wellbeing / Apps → Usage Access → Parental Focus → ON
3. **Display Over Other Apps** → Settings → Special app access → Appear on top → Parental Focus → ON
4. **Battery Optimisation** → Settings → Apps → Parental Focus → Battery → Unrestricted
5. **Camera** — runtime prompt (accept when asked)
6. **Notifications** — runtime prompt on Android 13+ (accept when asked)
7. *(Optional)* **Write System Settings** → allows screen auto-dim during block sessions

---

## Architecture

```
app/src/main/java/com/parental/focus/
├── ParentalFocusApp.kt              – Application class, notification channels
├── data/
│   ├── AppPreferences.kt            – SharedPreferences wrapper (single truth store)
│   └── Models.kt                   – BlockSchedule, FaceLandmarkPoint, AppInfo
├── service/
│   ├── AppBlockerAccessibilityService.kt  – Core engine: detects + blocks apps
│   ├── BlockerForegroundService.kt        – Keeps process alive
│   ├── BootReceiver.kt                    – Restart on boot / app update
│   └── ScheduleAlarmReceiver.kt           – AlarmManager hook for schedule starts
├── overlay/
│   └── BlockOverlayActivity.kt           – Full-screen block overlay + parent face-unlock
├── face/
│   └── FaceUtils.kt                      – ML Kit face detection, enrollment, verification
└── ui/
    ├── MainActivity.kt                    – Nav host + Schedules / Blocked Apps / Settings
    ├── PermissionsActivity.kt             – First-launch permissions wizard
    └── FaceEnrollmentActivity.kt          – Child face capture and enrollment
```

---

## Key Design Decisions

- **No database** — all state lives in `SharedPreferences` via Gson serialisation. Simple, fast, zero migration headaches.
- **Programmatic UI for overlays** — `BlockOverlayActivity` and `FaceEnrollmentActivity` build their layouts in code to avoid XML inflation issues with `showWhenLocked` windows.
- **Landmark-based face comparison** — ML Kit Face Detection provides landmark positions (eyes, nose, mouth, cheeks). These are normalised to the face bounding box and compared via mean Euclidean distance. Threshold: 0.08 (8 % of face box). Not a biometric-grade system but practical for parental control.
- **Never-block list** — System UI, launchers, dialer, and own package are unconditionally excluded from blocking so the user can never be trapped on a blank screen.
- **GLOBAL_ACTION_HOME** — Fired immediately when a blocked app is detected. No overlay is shown first; the user is returned to home before the blocked app can fully render.
- **5-retry mechanism** — After firing HOME, the service reschedules up to 4 re-checks at 400 ms intervals to catch apps that auto-relaunch.

---

## Permissions Reference

| Permission | Why |
|---|---|
| `BIND_ACCESSIBILITY_SERVICE` | Core app detection |
| `PACKAGE_USAGE_STATS` | Fallback foreground app detection |
| `SYSTEM_ALERT_WINDOW` | Block overlay rendering |
| `CAMERA` | Face enrollment and verification |
| `QUERY_ALL_PACKAGES` | App picker lists all installed apps |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | Background service stays alive |
| `RECEIVE_BOOT_COMPLETED` | Restart after reboot |
| `WAKE_LOCK` | Keep CPU awake during blocking checks |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Prevent OEM killing service |
| `KILL_BACKGROUND_PROCESSES` | Terminate blocked app process after HOME |
| `USE_FULL_SCREEN_INTENT` | Block alert notification launches overlay |
| `POST_NOTIFICATIONS` | Block alert notifications (Android 13+) |
| `WRITE_SETTINGS` | Optional: auto-dim brightness during blocks |
| `VIBRATE` | Optional: haptic feedback on block |
| `SCHEDULE_EXACT_ALARM` | Precise schedule start/end timing |
