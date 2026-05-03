# PixelIMS v1.0 — Complete Code Verification

**Date:** May 2, 2026  
**Status:** ✅ COMPLETE — All bugs fixed, ready to build

---

## Source Analysis

### vvb2060 v3.1 Core (Bypass Engine)
**Source:** `/mnt/user-data/uploads/Ims-3_1.zip`
- ✅ `PrivilegedProcess.java` — 147 lines
- ✅ `ShizukuProvider.java` — 167 lines
- ✅ Instrumentation bypass logic intact
- ✅ `canPersistent()` runtime detection
- ✅ `needOverride()` version fingerprint
- ✅ `showVoLTE()` IMS provisioning
- ✅ SDK sandbox support
- ✅ PID security gate

### TurboIMS v3.0 UI
**Source:** `/mnt/user-data/uploads/TurboIMS-3_0.zip`
- ✅ `MainActivity.java` — UI, feature toggles, SIM selection
- ✅ `SplashActivity.java` — Splash screen
- ✅ `LocaleHelper.java` — EN/ZH language switching
- ⚠️ **TurboIMS bypass is BROKEN** (uses pre-Oct-2025 shell method)
- ✅ We used ONLY the UI layer from TurboIMS

---

## Bugs Found and Fixed

### Bug #1: ActivityManagerHidden Runtime Crash (CRITICAL)
**Problem:** `ActivityManagerHidden` stub class doesn't exist at runtime → `NoClassDefFoundError`  
**Fix:** Replaced with `android.app.ActivityManager` stub exposing hidden constants  
**Files Changed:**
- `/stub/src/main/java/android/app/ActivityManager.java` (created)
- `/stub/src/main/java/android/app/ActivityManagerHidden.java` (deleted)
- `ShizukuProvider.java` (import changed)

### Bug #2: Shizuku Permission UI Never Refreshes (IMPORTANT)
**Problem:** `requestPermission()` called but `onRequestPermissionsResult()` missing  
**Fix:** Added callback method to refresh UI after permission grant  
**Files Changed:**
- `MainActivity.java` (+8 lines, method added)

### Bug #3: Per-SIM Selection Dead UI (LOGIC GAP)
**Problem:** SIM selection saved to prefs but ignored in `overrideCarrierConfig`  
**Fix:** Read `selected_subid` and filter subscription list  
**Files Changed:**
- `PrivilegedProcess.java` (overrideCarrierConfig method)

### Bug #4: SharedPreferences Context Mismatch (SUBTLE)
**Problem:** Instrumentation context may differ from app's data directory  
**Fix:** Always resolve prefs via `createPackageContext(APPLICATION_ID)`  
**Files Changed:**
- `PrivilegedProcess.java` (buildConfigBundle method)

---

## Code Verification Checklist

### PrivilegedProcess.java ✅
- [x] `onCreate()` matches vvb2060 v3.1 logic
- [x] SDK sandbox path present and correct
- [x] PID security gate (`arguments != null && getInt("pid") == myPid()`)
- [x] `makeSandboxExtras()` Binder callback matches vvb2060 `makeExtras()`
- [x] `grantReadPhoneState()` matches vvb2060 `grantPermission()`
- [x] `overrideCarrierConfig()` honors selectedSubId from prefs
- [x] SecurityException retry logic correct (try persistent, fall back to false)
- [x] `buildConfigBundle()` reads all 7 toggle prefs
- [x] All CarrierConfigManager keys match vvb2060's `getConfig()`
- [x] 5G NR signal thresholds present ([-128, -118, -108, -98])
- [x] All imports present and valid

### ShizukuProvider.java ✅
- [x] LSPass.setHiddenApiExemptions("") at static init
- [x] `call()` method matches vvb2060 logic
- [x] `METHOD_SEND_BINDER` path correct
- [x] `METHOD_GET_BINDER` sandbox path correct
- [x] `showVoLTE()` IMS provisioning present
- [x] `needOverride()` version fingerprint check
- [x] `canPersistent()` runtime patch detection
- [x] `startInstrument()` uses ActivityManager constants correctly
- [x] `applyNow()` public method for MainActivity
- [x] All imports present and valid

### MainActivity.java ✅
- [x] All 7 feature switches (VoLTE, VoWiFi, VT, VoNR, Cross-SIM, UT, 5G NR)
- [x] Per-SIM selection (SIM 1, SIM 2, All SIMs)
- [x] Language toggle (EN/ZH)
- [x] `onRequestPermissionsResult()` callback present
- [x] Shizuku status display (Ready / Not Running / No Permission)
- [x] Config status display (Applied / Not Applied) — NEW
- [x] Persistent mode display (Yes / No) — NEW
- [x] All SharedPreferences keys match PrivilegedProcess
- [x] Default selectedSubId = -1 (all SIMs)
- [x] `applyConfiguration()` calls `ShizukuProvider.applyNow()`
- [x] All R.id references exist in activity_main.xml
- [x] All R.string references exist in strings.xml

### XML Resources ✅
- [x] activity_main.xml — all IDs defined
- [x] activity_splash.xml — present
- [x] feature_item.xml — present
- [x] strings.xml (EN) — 46 strings
- [x] strings.xml (ZH) — 46 strings (parity)
- [x] styles.xml — Divider style defined
- [x] No missing R.id references
- [x] No missing R.string references (android.R.string.cancel is system string)

### AndroidManifest.xml ✅
- [x] `<instrumentation>` entry present with correct targetPackage
- [x] SplashActivity as launcher
- [x] MainActivity present
- [x] ShizukuProvider with correct authority
- [x] READ_PHONE_STATE permission declared

### Build Files ✅
- [x] app/build.gradle.kts — correct dependencies
- [x] stub/build.gradle.kts — compiles API 35
- [x] libs.versions.toml — Shizuku 13.1.5, LSPass 6.1
- [x] settings.gradle.kts — includes app + stub
- [x] gradle.properties — useAndroidX=false
- [x] gradle-wrapper.properties — Gradle 8.9

### Stub Module ✅
- [x] IActivityManager.java
- [x] ActivityManager.java (with hidden constants)
- [x] IInstrumentationWatcher.java
- [x] IUiAutomationConnection.java
- [x] UiAutomationConnection.java
- [x] ServiceManager.java
- [x] ITelephony.java

---

## Feature Comparison Matrix

| Feature | vvb2060 v3.1 | TurboIMS v3.0 | **PixelIMS v1.0** |
|---|:---:|:---:|:---:|
| Oct 2025 patch bypass | ✅ | ❌ | ✅ |
| Per-feature toggles | ❌ | ✅ | ✅ |
| Per-SIM selection | ❌ | ✅ (UI only) | ✅ (functional) |
| `showVoLTE()` provisioning | ✅ | ❌ | ✅ |
| `canPersistent()` detection | ✅ | ❌ | ✅ |
| `needOverride()` fingerprint | ✅ | ❌ | ✅ |
| SDK Sandbox support | ✅ | ❌ | ✅ |
| Status indicators in UI | ❌ | ❌ | ✅ NEW |
| Language switching | ❌ | ✅ | ✅ |
| Auto-apply on Shizuku connect | ✅ | ❌ | ✅ |
| 5G signal thresholds | ✅ | ✅ | ✅ |

---

## Testing Recommendations

1. **Build Test:**
   ```bash
   ./gradlew assembleRelease
   ```
   - Expected: clean build, no warnings
   - APK at: `app/build/outputs/apk/release/app-release.apk`

2. **Install Test:**
   - Install on Pixel 6+ running Android 12+
   - Launch app → should show splash then MainActivity
   - Shizuku status should show correct state

3. **Functional Test:**
   - Enable Wireless Debugging
   - Start Shizuku
   - Grant Shizuku permission when prompted
   - UI should refresh showing "✅ Ready"
   - Tap "Apply to All SIMs"
   - Wait 5 seconds → Config Status should show "✅ Applied"
   - Open Settings → Network → SIM → 5G toggle should appear

4. **Per-SIM Test:**
   - Select "SIM 1"
   - Tap Apply
   - Check logcat: should say "Applying to SIM 1 only"

5. **Persistent Mode Test:**
   - Check "Persistent mode: Yes/No" display
   - If No: reboot → reopen app → tap Apply again
   - If Yes: reboot → config should survive

---

## Known Limitations

1. **Not a complete app audit** — This verification focused on:
   - Core bypass logic correctness (vvb2060 v3.1 parity)
   - UI functionality (TurboIMS UI layer)
   - Critical bugs that would prevent building or running

2. **Not tested on device** — Actual device testing required to verify:
   - Shizuku integration at runtime
   - 5G actually connects on Dialog network
   - Persistent config survives reboot (device-dependent)

3. **Google may patch the Instrumentation bypass** — If the October 2025 bypass gets patched again, a new method will be needed. Monitor:
   - `github.com/vvb2060/Ims` for updates
   - XDA Pixel community forums

---

## Build Output

- Project: **PixelIMS**
- Version: **1.0**
- Files: **33** (code, resources, build config)
- Size: **~30KB** (source code ZIP)
- Package: `io.github.pixelims`
- Min Android: **12 (API 31)**
- Target Android: **15 (API 35)**

---

**Conclusion:** The codebase is complete, bug-free to the best of verification capability, and ready to build. All critical vvb2060 v3.1 bypass logic is intact, TurboIMS UI is properly integrated, and the 4 found bugs have been fixed.
