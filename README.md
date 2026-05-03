# ⚡ Pixel IMS

**Enable 5G, VoLTE, VoWiFi and VoNR on Google Pixel devices with unsupported carriers — without root.**

Built specifically for users in Sri Lanka (Dialog, Mobitel) and other regions where Google has not officially certified local carriers for 5G/IMS features. Works on any Pixel running Android 12+.

---

## What This Does

Google Pixel devices use a carrier whitelist enforced at the modem firmware level. If your carrier (e.g. Dialog Axiata, PLMN 413-02) is not on Google's approved list, your Pixel will refuse to connect to 5G and hide VoLTE settings — even when your carrier's 5G network is physically available.

This app overrides the `CarrierConfigManager` configuration at runtime using a privileged Instrumentation bypass, unlocking:

- **5G NR** — Both NSA (Non-Standalone, 5G anchored to 4G core) and SA (Standalone, full 5G)
- **VoLTE** — HD voice calls over 4G
- **VoWiFi** — Voice calls over WiFi
- **VoNR** — Voice calls over 5G
- **Video Calling (VT)**
- **Cross-SIM Calling**
- **UT Supplementary Services** — call forwarding, call waiting, etc.

---

## Requirements

| Requirement | Details |
|---|---|
| Device | Google Pixel 6 or later (Tensor chip) |
| Android | 12 (API 31) or higher |
| [Shizuku](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api) | v13+ — free on Play Store |
| Root | ❌ Not required |
| PC | ❌ Not required |

---

## How the Bypass Works

### Background

Google's carrier whitelist is enforced via `CarrierConfigManager.overrideConfig()`, a privileged Android API that requires the `MODIFY_PHONE_STATE` permission — only granted to system apps. Normal apps and even ADB shell cannot call it directly.

In October 2025, Google patched **CVE-2025-48617**, which had allowed calling `overrideConfig()` directly from the ADB shell user via Shizuku. That door was closed.

### The v3.1 Bypass (Instrumentation trick)

This app uses the approach from **vvb2060's Pixel IMS v3.1**. Instead of calling `overrideConfig()` from the shell user directly, we route through Android's `Instrumentation` framework:

1. `ShizukuProvider` receives the Shizuku binder when Shizuku starts
2. It calls `IActivityManager.startInstrumentation()` with flag `INSTR_FLAG_NO_RESTART`, passing our own PID as a security argument
3. The system launches `PrivilegedProcess` (which extends `Instrumentation`) as a runner
4. Inside `PrivilegedProcess`, we call `startDelegateShellPermissionIdentity()` to elevate our UID
5. We call `overrideConfig()` — the system sees it coming from the Instrumentation context, not directly from shell, bypassing the October 2025 restriction
6. We release delegation and exit

The PID check (`arguments.getInt("pid") == Process.myPid()`) is a security gate ensuring `PrivilegedProcess` only runs when launched by our own provider.

### Key Innovations from vvb2060 v3.1

**`canPersistent()` — Runtime patch-level detection**

Before calling `overrideConfig()`, the app probes private methods in `com.android.phone`'s `CarrierConfigLoader` class at runtime to determine whether persistent overrides are safe:

- No `isSystemApp()` method → old build → persistent = ✅ safe
- Has `isSystemApp()` but no `isSdkSandboxUidInternal()` → intermediate → persistent = ✅ safe  
- Has both → latest patch → persistent = ❌ blocked, uses session-only

**`showVoLTE()` — IMS Provisioning layer**

Some carriers block VoLTE at a second layer: IMS provisioning. Even after `CarrierConfigManager` is overridden, the `KEY_VOIMS_OPT_IN_STATUS` provisioning flag can still be set to disabled. This app fixes that by calling `ITelephony.setImsProvisioningInt()` directly. TurboIMS never touched this layer.

**Version fingerprinting**

The app stamps `vvb2060_config_version = VERSION_CODE` into the carrier config bundle. On Shizuku connect, it reads this key back to skip re-applying if the config is already current.

**SDK Sandbox support (Android 14+)**

On locked-bootloader devices on Android 14+, the app may run in an isolated SDK sandbox process. A separate Binder callback path handles this case.

---

## Setup Instructions

### Step 1 — Enable Wireless Debugging

1. Go to **Settings → About Phone**
2. Tap **Build Number** 7 times to enable Developer Options
3. Go to **Settings → System → Developer Options**
4. Enable **Wireless Debugging**

### Step 2 — Set Up Shizuku

1. Install [Shizuku](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api) from the Play Store
2. Open Shizuku
3. Tap **"Pair using Wireless Debugging"**
4. Follow the pairing dialog (no PC needed)
5. Shizuku status should show **"Running"**

### Step 3 — Install and Run Pixel IMS

1. Install `PixelIMS.apk`
2. Open the app
3. When prompted, grant Shizuku permission
4. Shizuku status in the app should show **✅ Ready**
5. (Optional) Toggle off any features you don't need
6. Tap **"Apply to All SIMs"**
7. When the success dialog appears, tap **"Open Network Settings"**
8. Enable 5G and VoLTE toggles in your SIM settings

### Step 4 — After Each Reboot

Check the **Config** status in the app. If it shows **⬜ Not yet applied**:
- Open Shizuku, tap Start (Wireless Debugging)
- Open Pixel IMS and tap Apply again

If **Persistent mode: Yes** is shown, the config survives reboots automatically.

---

## Building from Source

### Prerequisites

- Android Studio Hedgehog or newer
- JDK 17
- Android SDK 35

### Build

```bash
git clone https://github.com/YOUR_USERNAME/PixelIMS.git
cd PixelIMS
./gradlew assembleRelease
```

The signed APK will be at `app/build/outputs/apk/release/app-release.apk`.

---

## Project Structure

```
PixelIMS/
├── app/
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/io/github/pixelims/
│           ├── PrivilegedProcess.java   ← Core bypass engine (vvb2060 v3.1 logic)
│           ├── ShizukuProvider.java     ← Shizuku integration + canPersistent + showVoLTE
│           ├── MainActivity.java        ← UI (TurboIMS style + status improvements)
│           ├── SplashActivity.java      ← Splash screen
│           └── LocaleHelper.java        ← EN/ZH language switching
├── stub/
│   └── src/main/java/                  ← Hidden API stubs for compilation
│       ├── android/app/
│       │   ├── IActivityManager.java
│       │   ├── ActivityManager.java        ← Hidden INSTR_FLAG constants
│       │   ├── UiAutomationConnection.java
│       │   └── ...
│       ├── android/os/
│       │   └── ServiceManager.java
│       └── com/android/internal/telephony/
│           └── ITelephony.java
└── README.md
```

---

## Comparison: vvb2060 v3.1 vs TurboIMS v3.0 vs Pixel IMS v1.0

| Feature | vvb2060 v3.1 | TurboIMS v3.0 | **Pixel IMS v1.0** |
|---|:---:|:---:|:---:|
| Oct 2025 patch bypass | ✅ | ❌ Broken | ✅ |
| User interface | ❌ Headless | ✅ | ✅ |
| Per-feature toggles | ❌ | ✅ | ✅ |
| Per-SIM selection | ❌ | ✅ | ✅ |
| Language switching (EN/ZH) | ❌ | ✅ | ✅ |
| `showVoLTE()` IMS provisioning | ✅ | ❌ | ✅ |
| `canPersistent()` detection | ✅ | ❌ | ✅ |
| `needOverride()` fingerprint | ✅ | ❌ | ✅ |
| Config status shown in UI | ❌ | ❌ | ✅ **New** |
| Persistent mode shown in UI | ❌ | ❌ | ✅ **New** |
| Auto-apply on Shizuku connect | ✅ | ❌ | ✅ |
| SDK Sandbox support (Android 14+) | ✅ | ❌ | ✅ |
| 5G NR signal thresholds | ✅ | ✅ | ✅ |
| `KEY_SHOW_IMS_REGISTRATION_STATUS` | ✅ | ❌ | ✅ |
| Default SIM target | All (forced) | SIM 1 | All SIMs |
| Min Android version | API 34 | API 33 | **API 31** |
| Java version | 21 | 17 | 17 |

---

## Troubleshooting

**Shizuku shows "Not Running" after reboot**  
Wireless Debugging turns off on reboot. Go to Settings → Developer Options → Wireless Debugging → turn it on, then re-open Shizuku and tap Start.

**Config shows "Not yet applied" after tapping Apply**  
Wait 5 seconds and pull down to refresh. The Instrumentation runner takes a moment to complete. If it still shows Not Applied, force-stop the app and try again.

**5G toggle doesn't appear in SIM settings**  
Your carrier plan may not include 5G even after the config is unlocked. Confirm with Dialog that your plan supports 5G. Also ensure you're in a Dialog 5G coverage area (Colombo, Kandy, Galle metro areas as of May 2026).

**SecurityException in logcat**  
The October 2025 or newer patch may have closed the Instrumentation bypass. Check the GitHub issues page — a new bypass method may be available.

---

## Credits

- **[vvb2060](https://github.com/vvb2060/Ims)** — Original Pixel IMS, v3.1 bypass engine, `canPersistent()`, `showVoLTE()`, SDK sandbox support
- **[Turbo1123](https://github.com/Turbo1123/TurboIMS)** — TurboIMS v3.0 UI, per-feature toggles, per-SIM selection, language switching
- **[Rikka](https://github.com/RikkaApps/Shizuku)** — Shizuku framework
- **[LSPosed](https://github.com/LSPosed/LSPosed)** — LSPass hidden API bypass library

---

## Disclaimer

This app overrides carrier configuration at the framework level. It does not modify system partitions and is reversible by uninstalling the app. However, overriding carrier config technically violates carrier policies. Use at your own risk. The authors are not responsible for any issues arising from use of this software.
