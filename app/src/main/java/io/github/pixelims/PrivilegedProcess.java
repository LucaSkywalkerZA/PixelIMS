package io.github.pixelims;

import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.app.IActivityManager;
import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.permission.PermissionManager;
import android.system.Os;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.util.Log;

import rikka.shizuku.ShizukuBinderWrapper;

import static rikka.shizuku.ShizukuProvider.METHOD_GET_BINDER;

/**
 * PixelIMS — PrivilegedProcess
 *
 * This is the core bypass engine, based on vvb2060's v3.1 Instrumentation trick.
 *
 * HOW THE BYPASS WORKS:
 * Google's October 2025 patch (CVE-2025-48617) blocked calling overrideConfig()
 * directly as the shell user. The fix: we use Android's Instrumentation framework
 * to launch this class as a runner. The system sees the call coming from an
 * Instrumentation context rather than shell directly, bypassing the restriction.
 *
 * The pid argument check is a security gate — it ensures this only runs when
 * launched by our own ShizukuProvider (same process), not by anything external.
 *
 * The SDK sandbox path handles Android 14+ where the app may run in an isolated
 * sandbox process, requiring a different Binder callback route.
 *
 * Feature toggles are read from SharedPreferences so the user's choices
 * from the UI (TurboIMS-style) are respected.
 */
public class PrivilegedProcess extends Instrumentation {

    static final String TAG = "PixelIMS";
    static final String PREFS_NAME = "pixelims_config";

    @Override
    public void onCreate(Bundle arguments) {
        var context = getContext();

        if (Process.isSdkSandbox()) {
            // Android 14+ SDK Sandbox path: we can't call overrideConfig directly
            // from the sandbox process. Instead, we pass a Binder back to
            // ShizukuProvider which holds the shell delegation and calls back into us.
            var extras = makeSandboxExtras(context);
            var cr = context.getContentResolver();
            cr.call(BuildConfig.APPLICATION_ID + ".shizuku", METHOD_GET_BINDER, null, extras);

        } else if (arguments != null && arguments.getInt("pid", 0) == Process.myPid()) {
            // Normal path: we were launched by our own ShizukuProvider (pid matches).
            // Delegate shell permissions to our UID, apply config, then release.
            var binder = ServiceManager.getService(Context.ACTIVITY_SERVICE);
            var am = IActivityManager.Stub.asInterface(new ShizukuBinderWrapper(binder));
            try {
                am.startDelegateShellPermissionIdentity(Os.getuid(), null);
                grantReadPhoneState(context);
                overrideCarrierConfig(context, false);
                am.stopDelegateShellPermissionIdentity();
            } catch (RemoteException e) {
                Log.e(TAG, "Failed in PrivilegedProcess main path", e);
            }
            finish(0, new Bundle());

        } else {
            // Launched by something other than our provider — refuse and exit cleanly.
            Log.w(TAG, "PrivilegedProcess: unexpected caller pid, aborting.");
            finish(0, new Bundle());
        }
    }

    /**
     * SDK Sandbox path: create a Binder that ShizukuProvider can call back on.
     * When transact(1) arrives, we apply config while the shell delegation
     * is already active in the provider's process.
     */
    private Bundle makeSandboxExtras(Context context) {
        var binder = new Binder() {
            @Override
            protected boolean onTransact(int code, @NonNull Parcel data,
                                         Parcel reply, int flags) throws RemoteException {
                if (code == 1) {
                    try {
                        grantReadPhoneState(context);
                        overrideCarrierConfig(context, true);
                    } catch (Exception e) {
                        Log.e(TAG, "Sandbox callback failed", e);
                    }
                    // Delay finish so the config call completes before we die
                    new Handler(Looper.getMainLooper())
                            .postDelayed(() -> finish(0, new Bundle()), 1000);
                    return true;
                }
                return super.onTransact(code, data, reply, flags);
            }
        };
        var extras = new Bundle();
        extras.putBinder("binder", binder);
        return extras;
    }

    /**
     * Self-grants READ_PHONE_STATE so getActiveSubscriptionIdList() works.
     * We can do this because we have shell identity delegated.
     */
    @SuppressLint("MissingPermission")
    private static void grantReadPhoneState(Context context) {
        try {
            var pm = context.getSystemService(PermissionManager.class);
            pm.grantRuntimePermission(BuildConfig.APPLICATION_ID,
                    android.Manifest.permission.READ_PHONE_STATE, Process.myUserHandle());
        } catch (Exception e) {
            Log.w(TAG, "grantReadPhoneState failed (non-fatal): " + e.getMessage());
        }
    }

    /**
     * The main config override method.
     * Reads selected_subid from SharedPreferences:
     *   -1 = all SIMs (default)
     *    1 = SIM slot 1 only (index 0 of active subscription list)
     *    2 = SIM slot 2 only (index 1 of active subscription list)
     *
     * Falls back from persistent=true to persistent=false if the system rejects it.
     */
    @SuppressLint("MissingPermission")
    static void overrideCarrierConfig(Context context, boolean persistent) {
        var cm = context.getSystemService(CarrierConfigManager.class);
        var sm = context.getSystemService(SubscriptionManager.class);
        var values = buildConfigBundle(context);

        int[] allSubIds;
        try {
            allSubIds = sm.getActiveSubscriptionIdList();
        } catch (SecurityException e) {
            Log.e(TAG, "Could not get subscription list", e);
            return;
        }

        if (allSubIds == null || allSubIds.length == 0) {
            Log.w(TAG, "No active subscriptions found.");
            return;
        }

        // Respect the SIM slot selection saved by MainActivity
        int selectedSlot = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt("selected_subid", -1);
        int[] subIds;
        if (selectedSlot == 1 && allSubIds.length >= 1) {
            subIds = new int[]{ allSubIds[0] };
            Log.i(TAG, "Applying to SIM 1 only (subId=" + allSubIds[0] + ")");
        } else if (selectedSlot == 2 && allSubIds.length >= 2) {
            subIds = new int[]{ allSubIds[1] };
            Log.i(TAG, "Applying to SIM 2 only (subId=" + allSubIds[1] + ")");
        } else {
            subIds = allSubIds;
            Log.i(TAG, "Applying to all " + allSubIds.length + " SIM(s)");
        }

        for (var subId : subIds) {
            values.putInt("vvb2060_config_version", BuildConfig.VERSION_CODE);
            try {
                cm.overrideConfig(subId, values, persistent);
            } catch (SecurityException e) {
                Log.w(TAG, "overrideConfig(persistent=" + persistent + ") rejected for subId="
                        + subId + ", retrying non-persistent");
                if (persistent) {
                    try {
                        cm.overrideConfig(subId, values, false);
                    } catch (SecurityException e2) {
                        Log.e(TAG, "overrideConfig failed entirely for subId=" + subId, e2);
                        continue;
                    }
                } else {
                    continue;
                }
            }
            // Verify it actually took effect
            var check = cm.getConfigForSubId(subId, "vvb2060_config_version");
            if (check.getInt("vvb2060_config_version", 0) == BuildConfig.VERSION_CODE) {
                Log.i(TAG, "✓ Config applied for subId=" + subId + " persistent=" + persistent);
            } else {
                Log.e(TAG, "✗ Config verification failed for subId=" + subId);
            }
        }
    }

    /**
     * Builds the PersistableBundle of carrier config keys to override.
     *
     * Reads user toggle preferences from SharedPreferences (set via MainActivity).
     * All features default to enabled (true) if prefs not yet written.
     *
     * Config keys are sourced from:
     *  - vvb2060 v3.1 (5G NR thresholds, VoNR, VoLTE, provisioning display)
     *  - TurboIMS v3.0 (VoWiFi, VT, UT, Cross-SIM, WFC format)
     */
    static PersistableBundle buildConfigBundle(Context context) {
        // When running as Instrumentation, getContext() returns our app's context
        // since targetPackage == applicationId. But to be safe, always resolve
        // prefs through the app package's own context using createPackageContext.
        SharedPreferences prefs;
        try {
            Context appContext = context.createPackageContext(
                    BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY);
            prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        } catch (Exception e) {
            // Fallback: use passed context directly (works in non-sandbox cases)
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }
        boolean enableVoLTE    = prefs.getBoolean("volte",     true);
        boolean enableVoWiFi   = prefs.getBoolean("vowifi",    true);
        boolean enableVT       = prefs.getBoolean("vt",        true);
        boolean enableVoNR     = prefs.getBoolean("vonr",      true);
        boolean enableCrossSIM = prefs.getBoolean("cross_sim", true);
        boolean enableUT       = prefs.getBoolean("ut",        true);
        boolean enable5GNR     = prefs.getBoolean("5g_nr",     true);

        var bundle = new PersistableBundle();

        // Always show IMS registration status in Settings
        bundle.putBoolean(CarrierConfigManager.KEY_SHOW_IMS_REGISTRATION_STATUS_BOOL, true);

        if (enableVoLTE) {
            bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL, true);
            bundle.putBoolean(CarrierConfigManager.KEY_EDITABLE_ENHANCED_4G_LTE_BOOL, true);
            bundle.putBoolean(CarrierConfigManager.KEY_HIDE_ENHANCED_4G_LTE_BOOL, false);
            bundle.putBoolean(CarrierConfigManager.KEY_HIDE_LTE_PLUS_DATA_ICON_BOOL, false);
        }

        if (enableVoWiFi) {
            bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL, true);
            bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_WFC_SUPPORTS_WIFI_ONLY_BOOL, true);
            bundle.putBoolean(CarrierConfigManager.KEY_EDITABLE_WFC_MODE_BOOL, true);
            bundle.putBoolean(CarrierConfigManager.KEY_EDITABLE_WFC_ROAMING_MODE_BOOL, true);
            bundle.putBoolean(CarrierConfigManager.KEY_SHOW_WIFI_CALLING_ICON_IN_STATUS_BAR_BOOL, true);
            bundle.putInt(CarrierConfigManager.KEY_WFC_SPN_FORMAT_IDX_INT, 6);
        }

        if (enableVT) {
            bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_VT_AVAILABLE_BOOL, true);
        }

        if (enableUT) {
            bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_SUPPORTS_SS_OVER_UT_BOOL, true);
        }

        if (enableCrossSIM) {
            bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_CROSS_SIM_IMS_AVAILABLE_BOOL, true);
            bundle.putBoolean(
                    CarrierConfigManager.KEY_ENABLE_CROSS_SIM_CALLING_ON_OPPORTUNISTIC_DATA_BOOL,
                    true);
        }

        if (enableVoNR) {
            bundle.putBoolean(CarrierConfigManager.KEY_VONR_ENABLED_BOOL, true);
            bundle.putBoolean(CarrierConfigManager.KEY_VONR_SETTING_VISIBILITY_BOOL, true);
        }

        if (enable5GNR) {
            // Enable both NSA (Non-Standalone, 5G anchored to 4G core) and SA (Standalone, full 5G)
            bundle.putIntArray(CarrierConfigManager.KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY,
                    new int[]{
                            CarrierConfigManager.CARRIER_NR_AVAILABILITY_NSA,
                            CarrierConfigManager.CARRIER_NR_AVAILABILITY_SA
                    });
            // Signal strength thresholds for 5G NR display bars.
            // Without these, the phone has no reference for Dialog's network
            // and may show incorrect signal bars even when 5G is connected.
            // Values are SSRSRP in dBm. Boundaries: [-140 dBm, -44 dBm]
            bundle.putIntArray(CarrierConfigManager.KEY_5G_NR_SSRSRP_THRESHOLDS_INT_ARRAY,
                    new int[]{
                            -128,   // SIGNAL_STRENGTH_POOR threshold
                            -118,   // SIGNAL_STRENGTH_MODERATE threshold
                            -108,   // SIGNAL_STRENGTH_GOOD threshold
                            -98     // SIGNAL_STRENGTH_GREAT threshold
                    });
        }

        return bundle;
    }
}
