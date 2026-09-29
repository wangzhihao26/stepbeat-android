package com.stepbeat.app;

import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Build;
import android.os.PowerManager;
import java.text.DateFormat;
import java.util.Date;

/** One local diagnostic record, never uploaded; survives a killed process. */
final class RunJournal {
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("last_run", Context.MODE_PRIVATE); }

    static void event(Context c, String reason) {
        prefs(c).edit().putString("event", reason).putLong("eventAt", System.currentTimeMillis()).apply();
    }

    static void checkpoint(Context c, boolean active, long elapsed, long beats, long maxDelay) {
        Settings settings = Settings.load(c);
        prefs(c).edit().putBoolean("active", active).putLong("elapsed", elapsed).putLong("beats", beats)
            .putInt("bpm", settings.bpm).putInt("minutes", settings.minutes)
            .putBoolean("sound", settings.sound).putBoolean("vibration", settings.vibration)
            .putLong("maxDelay", maxDelay).putLong("savedAt", System.currentTimeMillis()).apply();
    }

    static String describe(Context c) {
        SharedPreferences p = prefs(c);
        PowerManager power = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        AudioManager audio = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        String event = p.getString("event", "尚无训练记录");
        if (p.getBoolean("active", false) && !MetronomeService.alive) {
            event = "上次训练未记录正常结束，进程可能被系统或用户终止；无法仅凭此记录确定原因。\n最后事件：" + event;
        }
        long saved = p.getLong("savedAt", 0), eventAt = p.getLong("eventAt", 0);
        return "设备：" + Build.MANUFACTURER + " " + Build.MODEL + "\nAndroid " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT
            + "\n省电模式：" + (power.isPowerSaveMode() ? "开启" : "关闭")
            + "\n电池优化：" + (power.isIgnoringBatteryOptimizations(c.getPackageName()) ? "已豁免" : "未豁免")
            + "\n系统空闲模式：" + (power.isDeviceIdleMode() ? "是" : "否")
            + "\n通知：" + (c.getSystemService(NotificationManager.class).areNotificationsEnabled() ? "允许" : "未允许")
            + "\n媒体音量：" + audio.getStreamVolume(AudioManager.STREAM_MUSIC) + "/" + audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            + "\n\n" + event + (eventAt == 0 ? "" : "\n事件时间：" + DateFormat.getDateTimeInstance().format(new Date(eventAt)))
            + "\n最近保存的目标步频：" + p.getInt("bpm", 0)
            + "\n定时：" + (p.getInt("minutes", 0) == 0 ? "不限时" : p.getInt("minutes", 0) + " 分钟")
            + "\n提示：声音" + (p.getBoolean("sound", false) ? "开" : "关") + " / 震动" + (p.getBoolean("vibration", false) ? "开" : "关")
            + "\n最近保存时长：" + p.getLong("elapsed", 0) / 1000 + " 秒"
            + "\n最近保存节拍数：" + p.getLong("beats", 0)
            + "\n最大调度迟到：" + p.getLong("maxDelay", 0) + " 毫秒"
            + (saved == 0 ? "" : "\n保存时间：" + DateFormat.getDateTimeInstance().format(new Date(saved)))
            + "\n\n运行中每 15 秒保存一次。异常退出时只能看到最后记录，不代表精确停止时间。记录仅存于本机。";
    }
}
