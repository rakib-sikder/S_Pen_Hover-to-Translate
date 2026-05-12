# S Pen Hover-to-Translate

An Android app for Samsung Galaxy S Pen devices that translates any on-screen text in real time — just hover your S Pen over it. No tap required.

---

## How It Works

1. User grants overlay + accessibility permissions and taps **Start**
2. `CaptureService` starts a `MediaProjection` (screen capture pipeline)
3. `HoverWatchService` (AccessibilityService) adds a full-screen invisible overlay to `WindowManager`
4. When the S Pen hovers over the screen, the overlay captures the exact pen X/Y via `setOnHoverListener`
5. The blue cursor dot follows the pen tip in real time
6. After the pen holds still for ~900ms (dwell), `CaptureService` takes a screenshot and runs ML Kit OCR
7. The word under the pen is found, highlighted in blue, and translated using ML Kit Translate
8. A dark card tooltip shows the translation above (or below) the word, with speak (▶) and copy (⧉) buttons

---

## Requirements

- Samsung Galaxy device with S Pen (Note series, S Ultra, Tab S series, etc.)
- Android 11+ (minSdk 31)
- Android Studio Hedgehog or newer
- Internet connection only for first model download; everything else is fully on-device

---

## Permissions Required

| Permission | Why |
|---|---|
| `SYSTEM_ALERT_WINDOW` | Draw the cursor dot, highlight, and tooltip over other apps |
| `FOREGROUND_SERVICE` | Keep `CaptureService` alive while translating |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Required by Android 14+ for screen capture |
| `POST_NOTIFICATIONS` | Show the persistent notification for the foreground service |
| Accessibility Service | Receive hover enter/exit events from S Pen |
| Screen Capture (runtime) | `MediaProjectionManager` — user must approve once |

---

## File Structure

```
S_Pen_Hover-to-Translate/
├── app/
│   ├── libs/
│   │   ├── sdk-v1.0.0.jar          # Samsung S Pen Remote SDK (compile only)
│   │   └── spenremote-v1.0.1.jar   # Samsung S Pen Remote SDK (compile only)
│   │
│   └── src/main/
│       ├── AndroidManifest.xml     # Permissions + service declarations
│       │
│       ├── java/com/sikder/spentranslator/
│       │   │
│       │   ├── MainActivity.kt         ★ Entry point — permission wizard + Start/Stop button
│       │   ├── Language.kt             # Language name ↔ ML Kit code mapping helper
│       │   │
│       │   ├── model/
│       │   │   └── OcrWord.kt          # Data class: { text: String, bounds: Rect }
│       │   │
│       │   ├── services/
│       │   │   ├── HoverWatchService.kt  ★ Core — AccessibilityService that tracks the S Pen,
│       │   │   │                           draws overlays, triggers OCR and translation
│       │   │   └── CaptureService.kt     ★ Core — ForegroundService that holds MediaProjection,
│       │   │                               captures screenshots, runs ML Kit OCR
│       │   │
│       │   ├── spen/
│       │   │   └── SPenHoverListener.kt  # Samsung S Pen Remote SDK wrapper (Air Motion)
│       │   │                             # NOTE: currently unused — Air Motion is for gesture
│       │   │                             # remotes, not screen hover position tracking
│       │   │
│       │   └── utils/
│       │       └── OcrHelper.kt          # Wraps ML Kit TextRecognizer — bitmap → List<OcrWord>
│       │
│       └── res/
│           ├── layout/
│           │   ├── activity_main.xml       # Main UI: status, language spinner, Start/Stop button
│           │   ├── overlay_tooltip.xml     # (legacy layout — tooltip is now built in code)
│           │   └── overlay_top_bar.xml     # (legacy layout — mode button is now built in code)
│           │
│           ├── drawable/
│           │   ├── bg_icon_btn.xml         # Icon button background
│           │   ├── bg_tooltip_bubble.xml   # Tooltip card background
│           │   ├── bg_tooltip_caret.xml    # Tooltip arrow
│           │   ├── bg_word_highlight.xml   # Blue highlight box
│           │   ├── ic_close.xml
│           │   ├── ic_copy.xml
│           │   ├── ic_translate.xml
│           │   └── ic_volume.xml
│           │
│           ├── xml/
│           │   └── accessibility_service_config.xml  ★ Accessibility service metadata
│           │
│           └── values/
│               ├── strings.xml
│               ├── colors.xml
│               └── themes.xml
│
├── build.gradle.kts      # Project-level Gradle config
└── app/build.gradle.kts  # App-level Gradle config (dependencies listed below)
```

**★ = files you will most commonly edit**

---

## Key Dependencies (app/build.gradle.kts)

```kotlin
// ML Kit — On-device OCR (no internet needed after install)
implementation("com.google.mlkit:text-recognition:16.0.0")

// ML Kit — On-device Translation (language model downloaded once per language)
implementation("com.google.mlkit:translate:17.0.2")

// ML Kit — Language auto-detection
implementation("com.google.mlkit:language-id:17.0.6")

// Samsung S Pen SDK JARs (in app/libs/)
implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
```

---

## How Each Key File Works

### `HoverWatchService.kt` (AccessibilityService)

The main brain of the app. Runs continuously as long as the accessibility service is enabled.

**Hover tracking — the hover overlay:**
A full-screen transparent `View` is added to `WindowManager` with:
- `FLAG_NOT_TOUCHABLE` — finger touches pass through freely to the app underneath
- `FLAG_NOT_TOUCH_MODAL` — touches outside bounds also pass through
- `setOnHoverListener` — receives `ACTION_HOVER_MOVE` from S Pen (hover = generic motion event, NOT a touch event, so it is NOT blocked by `FLAG_NOT_TOUCHABLE`)

This gives exact pen tip `rawX` / `rawY` in real time.

**Dwell detection:**
After each pen move, a 900ms timer starts. If the pen moves more than 25px, the timer resets. If the pen holds still for 900ms, OCR is triggered at the current position.

**Tooltip auto-dismiss:**
Tooltip disappears after 6s (word mode) or 9s (paragraph mode), or when the pen moves more than 100px from where the translation happened.

**Mode button:**
A small WORD/PARA button floats on the right edge of the screen. Tap it to switch between translating a single word vs. the entire line the pen is on.

---

### `CaptureService.kt` (ForegroundService)

Holds the `MediaProjection` token and provides `captureAndOcr(callback)`.

Internally:
1. Creates an `ImageReader` backed by `VirtualDisplay`
2. On each call, acquires the latest frame from `ImageReader`
3. Converts it to a `Bitmap` and passes it to `OcrHelper`
4. `OcrHelper` runs ML Kit `TextRecognizer` and returns `List<OcrWord>`
5. Each `OcrWord` contains the recognized text and its bounding box in screen coordinates

**Important:** `CaptureService.instance` is a singleton reference used by `HoverWatchService` to trigger captures. It is `null` when the service is not running.

---

### `MainActivity.kt`

Guides the user through three setup steps in order:

1. **Overlay permission** → opens `Settings.ACTION_MANAGE_OVERLAY_PERMISSION`
2. **Accessibility service** → opens `Settings.ACTION_ACCESSIBILITY_SETTINGS`
3. **Screen capture** → launches `MediaProjectionManager.createScreenCaptureIntent()`

Once all three are done, `CaptureService` starts. The button changes to **Stop service** and actually stops it (`stopService()`).

Status text and button label always reflect real runtime state by checking `CaptureService.instance != null`.

---

### `accessibility_service_config.xml`

```xml
<accessibility-service
    android:accessibilityEventTypes="typeViewHoverEnter|typeViewHoverExit|typeWindowContentChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagReportViewIds|flagRetrieveInteractiveWindows"
    android:canRetrieveWindowContent="true"
    android:notificationTimeout="0"
    android:settingsActivity="com.sikder.spentranslator.MainActivity" />
```

Note: `android:canRequestMotionEventReporting` does NOT exist as an XML attribute — do not add it. Motion event reporting is enabled at runtime through `serviceInfo.flags` if needed.

---

## Bugs Fixed (vs. original code)

### Bug 1 — Cursor dot only moved up/down, not following real pen X position
**Root cause:** `onMotionEvent()` in `AccessibilityService` was not firing for S Pen hover on this device. `penX` stayed `0`, so the code fell back to `nb.centerX()` — the center of whatever accessibility node was hovered. Since text views are full-width, X never changed.

**Fix:** Added a full-screen `WindowManager` hover overlay with `setOnHoverListener`. S Pen hover events (`ACTION_HOVER_MOVE`) are generic motion events — they are received by the overlay even with `FLAG_NOT_TOUCHABLE` active. This gives exact `rawX`/`rawY` per frame.

---

### Bug 2 — Hover overlay blocked all finger touches and screen activity
**Root cause:** First version of the overlay omitted `FLAG_NOT_TOUCHABLE`, so finger touch events hit the overlay and were consumed.

**Fix:** Added `FLAG_NOT_TOUCHABLE` and `FLAG_NOT_TOUCH_MODAL` to the overlay's `WindowManager.LayoutParams`. Touch events (finger) = blocked by flag → pass through to app. Hover events (S Pen in air) = generic motion events, not blocked by flag → still received by `setOnHoverListener`.

---

### Bug 3 — "Stop service" button did not stop the service
**Root cause:** In `MainActivity.setupButtons()`, the `else` branch always launched `createScreenCaptureIntent()` again, even when the service was already running. There was no code path to stop `CaptureService`.

**Fix:**
```kotlin
CaptureService.instance != null -> {
    stopService(Intent(this, CaptureService::class.java))
    updateStatus()
}
```

---

### Bug 4 — Status text and button label didn't reflect runtime state
**Root cause:** `updateStatus()` only checked permissions, not whether `CaptureService` was actually running. The button said "Stop service" even before the user ever tapped Start.

**Fix:** `updateStatus()` now checks `CaptureService.instance != null` to determine real running state, and sets both `tvStatus.text` and `btnToggleService.text` accordingly.

---

### Bug 5 — `android:canRequestMotionEventReporting` compile error
**Root cause:** This attribute does not exist in the Android XML schema. Caused an AAPT build error.

**Fix:** Removed the attribute. It was not needed once the hover overlay approach was implemented.

---

### Bug 6 — `FLAG_REQUEST_MOTION_EVENTS` unresolved reference
**Root cause:** `AccessibilityServiceInfo.FLAG_REQUEST_MOTION_EVENTS` was only added in API 33. The Kotlin compiler rejected it even inside an `if (SDK >= 33)` guard.

**Fix:** Replaced with the raw hex value `0x00001000`. Later made unnecessary by the hover overlay approach, so removed entirely.

---

### Bug 7 — `SPenHoverListener` was dead code that broke hover tracking
**Root cause:** `SPenHoverListener` was written but never instantiated. When wired up, it connected to Samsung's **Air Motion SDK** (pen waved in the air as a gesture remote), which is unrelated to screen hover position. Connecting it appeared to interfere with how Samsung routes hover events to the accessibility service, silencing hover events entirely.

**Fix:** `SPenHoverListener` left as-is (unused). Do not instantiate it in `HoverWatchService`. The hover overlay approach is the correct mechanism for tracking screen hover position.

---

## Setup Steps for a New Developer

1. Clone the repo
2. Open in Android Studio
3. Connect a Samsung Galaxy S Pen device (physical device required — emulator has no S Pen)
4. Run the app (`▶`)
5. In the app:
   - Tap **Grant overlay permission** → allow
   - Tap **Enable Accessibility Service** → find "S Pen OCR" in the list → enable
   - Tap **Start** → approve screen capture
6. Open any app with text
7. Hover the S Pen over a word and hold still for ~1 second
8. Translation card appears with speak (▶) and copy (⧉) buttons

To download a translation model for a new language, select the language in the spinner and tap **Download translation model** before using it.

---

## Known Limitations

- Requires physical Samsung S Pen device — emulator will not work
- First use of a new target language requires a model download (~30MB per language)
- OCR accuracy depends on screen content — stylized fonts, very small text, or low-contrast backgrounds may not recognize well
- Translation is one-way: source language is auto-detected; target language is selected in the app
- Screen capture requires user approval once per install; after the app is force-killed, approval is required again

---

## If You Are Starting a New Chat With an AI Assistant

Tell the assistant:

> "I have an Android S Pen hover-to-translate app. The two core services are `HoverWatchService` (AccessibilityService) and `CaptureService` (ForegroundService with MediaProjection). The pen position is tracked via a full-screen WindowManager overlay with `FLAG_NOT_TOUCHABLE` + `setOnHoverListener` — this captures S Pen hover generic motion events without blocking finger touches. OCR uses ML Kit TextRecognizer on screenshots from MediaProjection. Translation uses ML Kit Translate with language auto-detection. The main open bugs were: cursor only moved up/down (fixed by hover overlay), stop button didn't stop the service (fixed in MainActivity), and status UI didn't reflect runtime state (fixed by checking CaptureService.instance). Please read the README for full file structure and context."

Then paste the specific file you need help with.
