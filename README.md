# ScriptEngine — Android Automation via Python Scripts

Automate any Android app using Python scripts with computer vision, click emulation, and ADB control.

## Architecture Overview

```
app/
├── service/
│   ├── ScriptService.kt       # Foreground Service — script lifecycle
│   └── AutoClickService.kt   # AccessibilityService — gestures
├── engine/
│   ├── ScriptEngine.kt        # Chaquopy Python runner
│   ├── ScreenCapture.kt       # MediaProjection + ImageReader
│   └── ImageFinder.kt         # JNI wrapper for OpenCV template matching
├── adb/
│   └── AdbManager.kt          # ADB over TCP (tananaev/adblib)
├── api/
│   └── ScriptAPI.kt           # All Python-accessible Kotlin methods
├── jni/
│   ├── screen_capture.cpp     # Native pixel access (RGBA, RGB565)
│   ├── image_finder.cpp       # OpenCV matchTemplate + NMS
│   └── CMakeLists.txt
└── assets/
    ├── bridge.py              # Python bridge — wires Python ↔ Kotlin
    ├── api.py                 # Public API re-exports for `from api import *`
    └── example_script.py      # Example automation script
```

## Prerequisites & Setup

### 1. Android NDK + CMake
In Android Studio: **SDK Manager → SDK Tools** → install:
- CMake 3.22.1
- NDK (Side by side) 25+

### 2. OpenCV Android SDK
1. Download from https://opencv.org/releases/ (choose Android SDK, e.g. 4.9.0)
2. Extract and copy/symlink the folder to:
   ```
   app/src/main/jni/OpenCV-android-sdk/
   ```
   The CMakeLists.txt expects `OpenCV-android-sdk/sdk/native/jni/`.

### 3. Chaquopy
Already configured in `app/build.gradle`. Requires a Chaquopy license for commercial use — free for personal/open source.
Sign up at https://chaquo.com/

### 4. ADB Library (tananaev/adblib)
Add to repositories if needed:
```groovy
maven { url 'https://jitpack.io' }
```
Then the dependency `com.tananaev:adblib:1.2` resolves automatically.

### 5. Build
```bash
./gradlew assembleDebug
```

## First Run

1. **Install APK** on emulator (MuMu/LDPlayer) or device.
2. **Enable Accessibility Service**: Settings → Accessibility → ScriptEngine AutoClick → Enable.
3. **Grant MediaProjection**: The app prompts when you press Start Script.
4. **ADB for emulators**: `adb connect 127.0.0.1:5555` (handled automatically on first command).

## Writing Scripts

Place `.py` files anywhere accessible (e.g. `/sdcard/scripts/`).
Template images can be in the same directory.

```python
from api import *
import threading

# Load template image from script directory
btn = load_image("button.png")

def watcher():
    while True:
        # Check pixel color
        if get_color(500, 300) == 0xFF00FF00:
            click(500, 300)
        sleep(100)

# Run watcher in background thread
threading.Thread(target=watcher, daemon=True).start()

# Main loop
while True:
    pos = find_img(btn, 0, 0, 1080, 1920)
    if pos:
        click(pos.x, pos.y)
        log(f"Found at {pos.x},{pos.y} confidence={pos.confidence:.2f}")
    sleep(500)
```

## Full API Reference

### Screen
| Function | Returns | Description |
|---|---|---|
| `get_color(x, y)` | `int` (ARGB) | Single pixel color |
| `get_colors([(x,y),...])` | `list[int]` | Multiple pixels — single JNI call |

### Images
| Function | Returns | Description |
|---|---|---|
| `load_image(name)` | `Bitmap` | Load from script dir, cached |
| `find_img(img, x1,y1,x2,y2)` | `Point\|None` | Best template match |
| `has_img(img, x1?,y1?,x2?,y2?)` | `bool` | Is template present? |
| `find_img_all(img, x1,y1,x2,y2)` | `list[Point]` | All matches |

`Point` has `.x`, `.y`, `.confidence` attributes.

### Clicks (AccessibilityService)
| Function | Description |
|---|---|
| `click(x, y)` | Tap |
| `click_rand(x, y, radius)` | Tap at random offset within radius |
| `swipe(x1,y1,x2,y2, duration?)` | Swipe gesture |
| `long_press(x, y, duration?)` | Long tap |
| `press_back()` | Back button |
| `press_home()` | Home button |
| `show_recents()` | Recents |

### Control
| Function | Description |
|---|---|
| `sleep(ms)` | Wait milliseconds |
| `log(msg)` | Print to app UI log |
| `get_screen_size()` | Returns `(width, height)` |

### ADB
| Function | Returns | Description |
|---|---|---|
| `shell(cmd)` | `str` | Execute shell command |
| `start_app(pkg, activity?)` | — | Launch app |
| `stop_app(pkg)` | — | Force-stop app |
| `send_key(keycode)` | — | Send keyevent |
| `get_installed_apps()` | `list[str]` | All package names |

## Performance Notes

- **Screen capture**: on-demand with 50ms cache; `ImageReader` with 2 buffers; single reusable `Bitmap`.
- **Pixel access**: batch via `get_colors()` to minimize JNI crossings.
- **Image search**: grayscale conversion (3× faster), search only in specified region, reused `Mat` objects.
- **Log UI**: throttled to max 10 updates/sec.
- **Template cache**: `SoftReference` — GC can reclaim if memory is tight.

## Troubleshooting

**"AccessibilityService not connected"** — Go to Settings → Accessibility and enable ScriptEngine.

**"Screen capture permission denied"** — Accept the MediaProjection dialog when prompted.

**OpenCV not found during build** — Check `app/src/main/jni/OpenCV-android-sdk/` path exists.

**ADB commands fail on real device** — Enable ADB over network: `adb tcpip 5555` then set host/port in AdbManager.

**Script not found** — Use full absolute path like `/sdcard/scripts/main.py`. Grant storage permission.
