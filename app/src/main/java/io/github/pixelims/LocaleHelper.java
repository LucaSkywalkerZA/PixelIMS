package io.github.pixelims;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;

import java.util.Locale;

public class LocaleHelper {

    private static final String PREF_NAME = "locale_config";
    private static final String KEY_LANGUAGE = "language";

    public static void setLocale(Context context, String languageCode) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_LANGUAGE, languageCode).apply();
        updateResources(context, languageCode);
    }

    public static String getLanguage(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        if (prefs.contains(KEY_LANGUAGE)) {
            return prefs.getString(KEY_LANGUAGE, "en");
        }
        // Auto-detect: Chinese system → zh, everything else → en
        return Locale.getDefault().getLanguage().startsWith("zh") ? "zh" : "en";
    }

    public static void updateResources(Context context, String languageCode) {
        Locale locale = new Locale(languageCode);
        Locale.setDefault(locale);
        Resources resources = context.getResources();
        Configuration config = new Configuration(resources.getConfiguration());
        config.setLocale(locale);
        context.createConfigurationContext(config);
        resources.updateConfiguration(config, resources.getDisplayMetrics());
    }

    public static String toggleLanguage(Context context) {
        String newLang = getLanguage(context).equals("zh") ? "en" : "zh";
        setLocale(context, newLang);
        return newLang;
    }
}
