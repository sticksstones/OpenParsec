package com.example.parsecdemo;

import android.app.Application;

import androidx.appcompat.app.AppCompatDelegate;

import com.google.android.material.color.DynamicColors;

public final class ParsecApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        applyThemeFromSettings();
        // Opt into dynamic Material You colors where the platform supports it.
        DynamicColors.applyToActivitiesIfAvailable(this);
    }

    private void applyThemeFromSettings() {
        Settings s = new Settings(this);
        AppCompatDelegate.setDefaultNightMode(nightModeFor(s.themeMode()));
    }

    static int nightModeFor(String mode) {
        switch (mode) {
            case Settings.THEME_LIGHT: return AppCompatDelegate.MODE_NIGHT_NO;
            case Settings.THEME_DARK:  return AppCompatDelegate.MODE_NIGHT_YES;
            default:                   return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        }
    }
}
