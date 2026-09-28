package com.stepbeat.app;

import android.content.Context;
import android.content.SharedPreferences;

final class Settings {
    int bpm = 120, mode = 1, volume = 65, strength = 70, minutes = 0;
    boolean sound = true, vibration = true;
    static final String[] MODES = {"走路", "快走", "跑步"};
    static final int[] PRESETS = {100, 120, 170};

    static Settings load(Context context) {
        SharedPreferences p = context.getSharedPreferences("stepbeat", Context.MODE_PRIVATE);
        Settings s = new Settings();
        s.bpm = BeatClock.clamp(p.getInt("bpm", 120));
        s.mode = Math.max(0, Math.min(2, p.getInt("mode", 1)));
        s.volume = Math.max(1, Math.min(100, p.getInt("volume", 65)));
        s.strength = Math.max(1, Math.min(100, p.getInt("strength", 70)));
        s.minutes = Math.max(0, Math.min(120, p.getInt("minutes", 0)));
        s.sound = p.getBoolean("sound", true);
        s.vibration = p.getBoolean("vibration", true);
        return s;
    }
    void save(Context c) {
        c.getSharedPreferences("stepbeat", Context.MODE_PRIVATE).edit()
            .putInt("bpm", bpm).putInt("mode", mode).putInt("volume", volume)
            .putInt("strength", strength).putInt("minutes", minutes)
            .putBoolean("sound", sound).putBoolean("vibration", vibration).apply();
    }
}
