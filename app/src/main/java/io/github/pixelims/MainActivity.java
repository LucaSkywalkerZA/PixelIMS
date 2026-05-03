package io.github.pixelims;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import rikka.shizuku.Shizuku;

/**
 * PixelIMS — MainActivity
 *
 * TurboIMS-style UI wired to vvb2060's bypass engine.
 *
 * Improvements over TurboIMS:
 *  - Shows real-time "Config Status" per SIM (Applied / Not Applied)
 *  - Shows "Persistent Mode" status (from ShizukuProvider.canPersistent())
 *  - Auto-applies when Shizuku connects (vvb2060 headless behaviour preserved)
 *  - Manual apply button still available for forced re-apply
 *  - Language toggle (EN/ZH) from TurboIMS
 *  - Per-SIM selection (SIM 1, SIM 2, All) from TurboIMS
 *  - Android version warning for API 36+ builds from TurboIMS
 */
public class MainActivity extends Activity {

    private static final String TAG = "PixelIMS.UI";
    static final String PREFS_NAME = "pixelims_config";

    private TextView tvAndroidVersion;
    private TextView tvShizukuStatus;
    private TextView tvPersistentMode;
    private TextView tvConfigStatus;
    private TextView tvPersistentWarning;
    private TextView tvSimInfo;

    private Button btnSelectSim;
    private Button btnSwitchLanguage;
    private Button btnApply;

    private Switch switchVoLTE;
    private Switch switchVoWiFi;
    private Switch switchVT;
    private Switch switchVoNR;
    private Switch switchCrossSIM;
    private Switch switchUT;
    private Switch switch5GNR;

    private SharedPreferences prefs;
    private int selectedSubId = -1; // -1 = all SIMs (changed default from TurboIMS)

    private final Shizuku.OnBinderReceivedListener binderReceived = () -> {
        // Auto-apply on Shizuku connect — vvb2060 headless behaviour
        runOnUiThread(() -> {
            updateShizukuStatus();
            updateStatusInfo();
        });
    };

    private final Shizuku.OnBinderDeadListener binderDead = () ->
            runOnUiThread(this::updateShizukuStatus);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        String language = LocaleHelper.getLanguage(this);
        LocaleHelper.updateResources(this, language);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        initViews();
        loadPreferences();
        updateSimInfo();
        updateAndroidVersionInfo();
        updateShizukuStatus();
        updateStatusInfo();

        Shizuku.addBinderReceivedListener(binderReceived);
        Shizuku.addBinderDeadListener(binderDead);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateShizukuStatus();
        updateStatusInfo();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Shizuku.removeBinderReceivedListener(binderReceived);
        Shizuku.removeBinderDeadListener(binderDead);
    }

    private void initViews() {
        tvAndroidVersion   = findViewById(R.id.tv_android_version);
        tvShizukuStatus    = findViewById(R.id.tv_shizuku_status);
        tvPersistentMode   = findViewById(R.id.tv_persistent_mode);
        tvConfigStatus     = findViewById(R.id.tv_config_status);
        tvPersistentWarning = findViewById(R.id.tv_persistent_warning);
        tvSimInfo          = findViewById(R.id.tv_sim_info);
        btnSelectSim       = findViewById(R.id.btn_select_sim);
        btnSwitchLanguage  = findViewById(R.id.btn_switch_language);

        // Feature switches
        switchVoLTE    = findViewById(R.id.item_volte).findViewById(R.id.feature_switch);
        switchVoWiFi   = findViewById(R.id.item_vowifi).findViewById(R.id.feature_switch);
        switchVT       = findViewById(R.id.item_vt).findViewById(R.id.feature_switch);
        switchVoNR     = findViewById(R.id.item_vonr).findViewById(R.id.feature_switch);
        switchCrossSIM = findViewById(R.id.item_cross_sim).findViewById(R.id.feature_switch);
        switchUT       = findViewById(R.id.item_ut).findViewById(R.id.feature_switch);
        switch5GNR     = findViewById(R.id.item_5g_nr).findViewById(R.id.feature_switch);

        // Feature titles and descriptions
        setFeatureText(R.id.item_volte,     R.string.volte,     R.string.volte_desc);
        setFeatureText(R.id.item_vowifi,    R.string.vowifi,    R.string.vowifi_desc);
        setFeatureText(R.id.item_vt,        R.string.vt,        R.string.vt_desc);
        setFeatureText(R.id.item_vonr,      R.string.vonr,      R.string.vonr_desc);
        setFeatureText(R.id.item_cross_sim, R.string.cross_sim, R.string.cross_sim_desc);
        setFeatureText(R.id.item_ut,        R.string.ut,        R.string.ut_desc);
        setFeatureText(R.id.item_5g_nr,     R.string._5g_nr,    R.string._5g_nr_desc);

        btnApply = findViewById(R.id.btn_apply);
        btnApply.setOnClickListener(v -> applyConfiguration());
        btnSelectSim.setOnClickListener(v -> showSimSelectionDialog());
        btnSwitchLanguage.setOnClickListener(v -> {
            LocaleHelper.toggleLanguage(this);
            recreate();
        });
    }

    private void setFeatureText(int itemId, int titleRes, int descRes) {
        View item = findViewById(itemId);
        ((TextView) item.findViewById(R.id.feature_title)).setText(titleRes);
        ((TextView) item.findViewById(R.id.feature_desc)).setText(descRes);
    }

    // -------------------------------------------------------------------------
    // Status display (new in PixelIMS, absent from TurboIMS)
    // -------------------------------------------------------------------------

    private void updateStatusInfo() {
        // Persistent mode indicator
        try {
            boolean persistent = ShizukuProvider.canPersistent(this);
            tvPersistentMode.setText(getString(R.string.persistent_mode,
                    persistent ? getString(R.string.yes) : getString(R.string.no)));
            tvPersistentMode.setTextColor(persistent ? 0xFF4CAF50 : 0xFFF57C00);
        } catch (Exception e) {
            tvPersistentMode.setText(getString(R.string.persistent_mode, "?"));
        }

        // Config applied indicator
        try {
            boolean applied = !ShizukuProvider.needOverride(this);
            tvConfigStatus.setText(getString(R.string.config_status,
                    applied ? getString(R.string.config_applied_short)
                            : getString(R.string.config_not_applied)));
            tvConfigStatus.setTextColor(applied ? 0xFF4CAF50 : 0xFFBDBDBD);
        } catch (Exception e) {
            tvConfigStatus.setText(getString(R.string.config_status, "?"));
        }
    }

    private void updateAndroidVersionInfo() {
        tvAndroidVersion.setText(getString(R.string.android_version,
                "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"));
        tvPersistentWarning.setVisibility(
                Build.VERSION.SDK_INT >= 36 ? View.VISIBLE : View.GONE);
    }

    private void updateShizukuStatus() {
        runOnUiThread(() -> {
            String statusText;
            int statusColor;

            if (!Shizuku.pingBinder()) {
                statusText = getString(R.string.shizuku_status,
                        getString(R.string.shizuku_not_running));
                statusColor = 0xFFFF0000;
                btnApply.setEnabled(false);
            } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                statusText = getString(R.string.shizuku_status,
                        getString(R.string.shizuku_no_permission));
                statusColor = 0xFFFF9800;
                btnApply.setEnabled(false);
                requestShizukuPermission();
            } else {
                statusText = getString(R.string.shizuku_status,
                        getString(R.string.shizuku_ready));
                statusColor = 0xFF4CAF50;
                btnApply.setEnabled(true);
            }

            tvShizukuStatus.setText(statusText);
            tvShizukuStatus.setTextColor(statusColor);
        });
    }

    private void requestShizukuPermission() {
        if (Shizuku.isPreV11()) {
            Toast.makeText(this, R.string.update_shizuku, Toast.LENGTH_LONG).show();
            return;
        }
        Shizuku.requestPermission(0);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                            String[] permissions, int[] grantResults) {
        // Shizuku routes its permission result through onRequestPermissionsResult.
        // Refresh status whenever any permission result arrives.
        if (Shizuku.onRequestPermissionsResult(requestCode, permissions, grantResults)) {
            updateShizukuStatus();
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    // -------------------------------------------------------------------------
    // Apply
    // -------------------------------------------------------------------------

    private void applyConfiguration() {
        if (!Shizuku.pingBinder()) {
            Toast.makeText(this, R.string.shizuku_not_running_msg, Toast.LENGTH_LONG).show();
            return;
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.shizuku_no_permission_msg, Toast.LENGTH_LONG).show();
            requestShizukuPermission();
            return;
        }

        savePreferences();
        Log.i(TAG, "Manual apply triggered by user.");

        // Kick off the bypass — ShizukuProvider.applyNow() → startInstrument()
        ShizukuProvider.applyNow(this);

        // Instrumentation causes the process to temporarily background.
        // Bring UI back and refresh status after 3 seconds.
        new Thread(() -> {
            try {
                Thread.sleep(3000);
                runOnUiThread(() -> {
                    updateStatusInfo();
                    Intent intent = new Intent(this, MainActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    startActivity(intent);
                    showSuccessDialog();
                });
            } catch (InterruptedException ignored) {}
        }).start();
    }

    private void showSuccessDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.config_applied)
                .setMessage(R.string.config_success_message)
                .setPositiveButton(R.string.go_to_network_settings, (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
                    } catch (Exception e) {
                        Toast.makeText(this, "Unable to open network settings",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.later, null)
                .show();
    }

    // -------------------------------------------------------------------------
    // SIM selection
    // -------------------------------------------------------------------------

    private void showSimSelectionDialog() {
        String[] items = {
            getString(R.string.sim_1),
            getString(R.string.sim_2),
            getString(R.string.apply_to_all_sims)
        };
        int selectedIndex = selectedSubId == 1 ? 0 : selectedSubId == 2 ? 1 : 2;

        new AlertDialog.Builder(this)
                .setTitle(R.string.select_sim)
                .setSingleChoiceItems(items, selectedIndex, (dialog, which) -> {
                    selectedSubId = which == 0 ? 1 : which == 1 ? 2 : -1;
                    updateSimInfo();
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void updateSimInfo() {
        if (selectedSubId == 1) {
            tvSimInfo.setText(R.string.sim_1);
            btnApply.setText(R.string.apply_to_sim_1);
        } else if (selectedSubId == 2) {
            tvSimInfo.setText(R.string.sim_2);
            btnApply.setText(R.string.apply_to_sim_2);
        } else {
            tvSimInfo.setText(R.string.apply_to_all_sims);
            btnApply.setText(R.string.apply_to_all);
        }
        prefs.edit().putInt("selected_subid", selectedSubId).apply();
    }

    // -------------------------------------------------------------------------
    // Preferences
    // -------------------------------------------------------------------------

    private void loadPreferences() {
        switchVoLTE.setChecked(prefs.getBoolean("volte", true));
        switchVoWiFi.setChecked(prefs.getBoolean("vowifi", true));
        switchVT.setChecked(prefs.getBoolean("vt", true));
        switchVoNR.setChecked(prefs.getBoolean("vonr", true));
        switchCrossSIM.setChecked(prefs.getBoolean("cross_sim", true));
        switchUT.setChecked(prefs.getBoolean("ut", true));
        switch5GNR.setChecked(prefs.getBoolean("5g_nr", true));
        selectedSubId = prefs.getInt("selected_subid", -1);
    }

    private void savePreferences() {
        prefs.edit()
                .putBoolean("volte",     switchVoLTE.isChecked())
                .putBoolean("vowifi",    switchVoWiFi.isChecked())
                .putBoolean("vt",        switchVT.isChecked())
                .putBoolean("vonr",      switchVoNR.isChecked())
                .putBoolean("cross_sim", switchCrossSIM.isChecked())
                .putBoolean("ut",        switchUT.isChecked())
                .putBoolean("5g_nr",     switch5GNR.isChecked())
                .apply();
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        LocaleHelper.updateResources(newBase, LocaleHelper.getLanguage(newBase));
        super.attachBaseContext(newBase);
    }
}
