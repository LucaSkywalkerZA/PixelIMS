package android.app;

/**
 * Stub for android.app.ActivityManager.
 * Exposes @hide constants needed for compilation.
 * At runtime the real ActivityManager class is used — LSPass.setHiddenApiExemptions("")
 * in ShizukuProvider ensures these hidden fields are accessible.
 *
 * Values sourced from AOSP frameworks/base/core/java/android/app/ActivityManager.java
 */
public class ActivityManager {

    /** @hide Disable hidden API checks when starting instrumentation. */
    public static final int INSTR_FLAG_DISABLE_HIDDEN_API_CHECKS = 0x00000002;

    /** @hide Do not kill/restart the target app when starting instrumentation. */
    public static final int INSTR_FLAG_NO_RESTART = 0x00000010;

    /** @hide Run instrumentation in the SDK sandbox process (Android 14+). */
    public static final int INSTR_FLAG_INSTRUMENT_SDK_SANDBOX = 0x00000080;
}
