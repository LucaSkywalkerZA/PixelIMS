package io.github.pixelims;

import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.IActivityManager;
import android.app.UiAutomationConnection;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.system.Os;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.telephony.ims.ProvisioningManager;
import android.util.Log;

import com.android.internal.telephony.ITelephony;

import org.lsposed.hiddenapibypass.LSPass;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;

import static io.github.pixelims.PrivilegedProcess.TAG;

/**
 * PixelIMS — ShizukuProvider
 *
 * This is the Shizuku integration layer, based on vvb2060's v3.1 ShizukuProvider.
 *
 * Responsibilities:
 *  1. Receive the Shizuku binder when Shizuku starts (METHOD_SEND_BINDER)
 *  2. On binder received: call showVoLTE() then check if config needs applying
 *  3. If needed: launch PrivilegedProcess via Instrumentation (the bypass)
 *  4. Handle the SDK sandbox path (Android 14+)
 *  5. Expose applyNow() for manual apply from MainActivity
 *
 * Key methods from vvb2060 v3.1:
 *  - canPersistent(): runtime-detects whether the current Android build allows
 *    persistent overrideConfig. Probes private methods in CarrierConfigLoader.
 *  - needOverride(): checks if config is already at current version (fingerprint).
 *  - showVoLTE(): directly sets IMS provisioning opt-in status via ITelephony.
 *    Fixes carriers that block VoLTE at the provisioning layer even after config override.
 */
public class ShizukuProvider extends rikka.shizuku.ShizukuProvider {

    // Unhide all hidden APIs at static init time — must run before any hidden API call
    static {
        LSPass.setHiddenApiExemptions("");
    }

    // Tracks whether we're in SDK sandbox mode (skip auto-apply, use sandbox path)
    private boolean isSandboxMode = false;

    @Override
    public Bundle call(@NonNull String method, String arg, Bundle extras) {
        // Only operate in the primary user profile
        if (UserHandle.myUserId() != UserHandle.USER_SYSTEM) {
            return new Bundle();
        }

        // Validate caller: must be our sandbox UID, shell, or root
        var sdkUid = Process.toSdkSandboxUid(Os.getuid());
        var callingUid = Binder.getCallingUid();
        if (callingUid != sdkUid
                && callingUid != Process.SHELL_UID
                && callingUid != Process.ROOT_UID) {
            return new Bundle();
        }

        if (METHOD_SEND_BINDER.equals(method)) {
            // Shizuku is delivering its binder — register a one-shot listener
            Shizuku.addBinderReceivedListener(() -> {
                if (!isSandboxMode
                        && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    showVoLTE();
                    var context = getContext();
                    if (context != null && needOverride(context)) {
                        startInstrument(context, canPersistent(context));
                    }
                }
            });

        } else if (METHOD_GET_BINDER.equals(method) && callingUid == sdkUid && extras != null) {
            // SDK Sandbox path: PrivilegedProcess is running in sandbox and sent us a binder.
            // We hold shell delegation here and transact back to it.
            isSandboxMode = true;
            Shizuku.addBinderReceivedListener(() -> {
                var binder = extras.getBinder("binder");
                if (binder != null
                        && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    startSandboxDelegate(binder, sdkUid);
                }
            });
        }

        return super.call(method, arg, extras);
    }

    /**
     * Called from MainActivity when the user taps "Apply Configuration".
     * Forces a re-apply regardless of the version fingerprint.
     */
    public static void applyNow(Context context) {
        showVoLTE();
        startInstrument(context, canPersistent(context));
    }

    // -------------------------------------------------------------------------
    // Instrumentation launcher
    // -------------------------------------------------------------------------

    /**
     * Launches PrivilegedProcess as an Instrumentation runner.
     *
     * This is the core of the bypass. By routing through startInstrumentation()
     * rather than calling overrideConfig() directly from shell, we avoid the
     * CVE-2025-48617 restriction that blocks shell from calling overrideConfig.
     *
     * The pid argument is a security gate — PrivilegedProcess checks that it was
     * launched by our own process and refuses to run otherwise.
     *
     * @param sdkSandbox if true, use INSTR_FLAG_INSTRUMENT_SDK_SANDBOX instead of NO_RESTART
     */
    static void startInstrument(Context context, boolean sdkSandbox) {
        try {
            var binder = ServiceManager.getService(Context.ACTIVITY_SERVICE);
            var am = IActivityManager.Stub.asInterface(new ShizukuBinderWrapper(binder));
            var name = new ComponentName(context, PrivilegedProcess.class);

            int flags = ActivityManager.INSTR_FLAG_DISABLE_HIDDEN_API_CHECKS;
            if (sdkSandbox) {
                flags |= ActivityManager.INSTR_FLAG_INSTRUMENT_SDK_SANDBOX;
            } else {
                flags |= ActivityManager.INSTR_FLAG_NO_RESTART;
            }

            // Pass our PID so PrivilegedProcess can verify its caller
            var args = new Bundle();
            args.putInt("pid", Process.myPid());

            var connection = new UiAutomationConnection();
            am.startInstrumentation(name, null, flags, args, null, connection, 0, null);
            Log.i(TAG, "Instrumentation started (sdkSandbox=" + sdkSandbox + ")");
        } catch (Exception e) {
            Log.e(TAG, "startInstrument failed", e);
        }
    }

    // -------------------------------------------------------------------------
    // SDK Sandbox delegation
    // -------------------------------------------------------------------------

    private static void startSandboxDelegate(IBinder binder, int sdkUid) {
        try {
            var activity = ServiceManager.getService(Context.ACTIVITY_SERVICE);
            var am = IActivityManager.Stub.asInterface(new ShizukuBinderWrapper(activity));
            am.startDelegateShellPermissionIdentity(sdkUid, null);
            var data = Parcel.obtain();
            binder.transact(1, data, null, 0);
            data.recycle();
            am.stopDelegateShellPermissionIdentity();
        } catch (Exception e) {
            Log.e(TAG, "Sandbox delegate failed", e);
        }
    }

    // -------------------------------------------------------------------------
    // Version fingerprint check
    // -------------------------------------------------------------------------

    /**
     * Checks if the carrier config is already at the current app version.
     * Reads the "vvb2060_config_version" key we stamp into the config.
     * If it matches VERSION_CODE, the config is current and we skip re-applying.
     */
    @SuppressLint("MissingPermission")
    static boolean needOverride(Context context) {
        var cm = context.getSystemService(CarrierConfigManager.class);
        var sm = context.getSystemService(SubscriptionManager.class);
        try {
            var list = sm.getActiveSubscriptionInfoList();
            if (list == null || list.isEmpty()) return true;
            for (var sub : list) {
                var bundle = cm.getConfigForSubId(sub.getSubscriptionId(), "vvb2060_config_version");
                if (bundle.getInt("vvb2060_config_version", 0) != BuildConfig.VERSION_CODE) {
                    return true;
                }
            }
            Log.i(TAG, "Config is current, no override needed.");
            return false;
        } catch (SecurityException e) {
            return true;
        }
    }

    // -------------------------------------------------------------------------
    // Persistent mode detection (vvb2060 v3.1 innovation)
    // -------------------------------------------------------------------------

    /**
     * Runtime-detects whether persistent overrideConfig is safe on this build.
     *
     * Probes private methods in com.android.phone's CarrierConfigLoader to
     * fingerprint the security patch level:
     *
     *  - If isSystemApp() doesn't exist → old build, persistent is safe → true
     *  - If isSystemApp() exists but isSdkSandboxUidInternal() doesn't → persistent safe → true
     *  - If both exist → newest build, persistent is blocked → false
     *
     * This avoids blindly trying persistent=true and failing, which would
     * leave the config in an inconsistent state.
     */
    @SuppressLint("PrivateApi")
    static boolean canPersistent(Context context) {
        try {
            var phone = context.createPackageContext("com.android.phone",
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            var clazz = phone.getClassLoader()
                    .loadClass("com.android.phone.CarrierConfigLoader");
            try {
                clazz.getDeclaredMethod("isSystemApp");
            } catch (NoSuchMethodException e) {
                return true; // Old build — persistent is safe
            }
            clazz.getDeclaredMethod("secureOverrideConfig",
                    PersistableBundle.class, boolean.class);
            try {
                clazz.getDeclaredMethod("isSdkSandboxUidInternal", int.class);
                return false; // Newest patch — persistent is blocked
            } catch (NoSuchMethodException e) {
                return true; // Intermediate build — persistent is safe
            }
        } catch (Exception e) {
            return false; // Unknown build — play it safe
        }
    }

    // -------------------------------------------------------------------------
    // IMS Provisioning (vvb2060 v3.1 innovation, absent from TurboIMS)
    // -------------------------------------------------------------------------

    /**
     * Directly enables the IMS Voice Opt-In provisioning flag via ITelephony.
     *
     * Some carriers block VoLTE at the provisioning layer even after the
     * CarrierConfig override. This operates at a completely separate layer
     * (IMS provisioning, not carrier config), fixing a second potential blocker.
     *
     * TurboIMS never touched provisioning — this is exclusive to vvb2060 v3.1.
     */
    private static void showVoLTE() {
        var subId = SubscriptionManager.getDefaultVoiceSubscriptionId();
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return;
        var binder = ServiceManager.getService(Context.TELEPHONY_SERVICE);
        var phone = ITelephony.Stub.asInterface(new ShizukuBinderWrapper(binder));
        try {
            var value = phone.getImsProvisioningInt(subId,
                    ProvisioningManager.KEY_VOIMS_OPT_IN_STATUS);
            if (value == ProvisioningManager.PROVISIONING_VALUE_ENABLED) {
                Log.i(TAG, "IMS Voice Opt-In already enabled.");
                return;
            }
            phone.setImsProvisioningInt(subId,
                    ProvisioningManager.KEY_VOIMS_OPT_IN_STATUS,
                    ProvisioningManager.PROVISIONING_VALUE_ENABLED);
            Log.i(TAG, "IMS Voice Opt-In enabled.");
        } catch (RemoteException e) {
            Log.w(TAG, "showVoLTE failed (non-fatal): " + e.getMessage());
        }
    }
}
