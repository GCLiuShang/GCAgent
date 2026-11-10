package com.gcagent;

import android.content.Context;
import android.content.SharedPreferences;

/** Small, UI-only preference store. */
public final class Prefs {
    private static final String NAME = "ui";
    private static final String GLASS = "glass_opacity";
    private static final String SPLASH = "splash_enabled";
    public static final int DEFAULT_GLASS = 72;

    private Prefs() {}

    public static int glassOpacity(Context c) {
        return prefs(c).getInt(GLASS, DEFAULT_GLASS);
    }

    public static void setGlassOpacity(Context c, int value) {
        prefs(c).edit().putInt(GLASS, Math.max(0, Math.min(100, value))).apply();
    }

    public static boolean splashEnabled(Context c) {
        return prefs(c).getBoolean(SPLASH, true);
    }

    public static void setSplashEnabled(Context c, boolean enabled) {
        prefs(c).edit().putBoolean(SPLASH, enabled).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }
}
