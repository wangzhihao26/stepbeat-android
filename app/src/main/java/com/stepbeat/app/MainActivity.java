package com.stepbeat.app;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.util.ArrayList;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(16,23,19), CARD = Color.rgb(28,38,32);
    private static final int GREEN = Color.rgb(197,243,107), WHITE = Color.rgb(241,246,236), MUTED = Color.rgb(145,162,149);
    private Settings settings;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ArrayList<Long> taps = new ArrayList<>();
    private final Button[] modes = new Button[3], timers = new Button[4];
    private final int[] durations = {0, 10, 20, 30};
    private DialView dial;
    private SeekBar bpmSlider;
    private Button play, reset, tap;
    private TextView status, elapsed, beats, durationLabel, backgroundStatus;
    private Switch soundSwitch, vibrationSwitch;
    private boolean syncing;
    private long seenBeat = -1;
    private String seenMessage = "";
    private boolean hasVibrator;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        settings = Settings.load(this);
        Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        hasVibrator = vibrator != null && vibrator.hasVibrator();
        if (!hasVibrator) { settings.vibration = false; settings.sound = true; settings.save(this); }
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        buildUi();
        refreshSettings();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        LinearLayout content = column(); content.setPadding(dp(24), dp(20), dp(24), dp(20));
        scroll.addView(content); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout header = row();
        TextView brand = text("▥  步调", 26, WHITE); brand.setTypeface(null, Typeface.BOLD);
        header.addView(brand, new LinearLayout.LayoutParams(0, dp(44), 1));
        TextView badge = text("STEP / BEAT", 11, GREEN); badge.setLetterSpacing(.14f); header.addView(badge);
        content.addView(header); content.addView(text("找到你的节奏，每一步都刚刚好。", 13, MUTED));
        space(content, 26);
        LinearLayout modeRow = row(); modeRow.setPadding(dp(4), dp(4), dp(4), dp(4)); modeRow.setBackground(shape(CARD, 18));
        for (int i = 0; i < 3; i++) {
            final int index = i;
            modes[i] = button(Settings.MODES[i], CARD, MUTED);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1); lp.setMargins(dp(2), 0, dp(2), 0);
            modeRow.addView(modes[i], lp);
            modes[i].setOnClickListener(v -> { settings.mode = index; settings.bpm = Settings.PRESETS[index]; changed(); refreshSettings(); });
        }
        content.addView(modeRow); space(content, 16);
        status = text("●  准备开始", 12, GREEN); status.setGravity(Gravity.CENTER); content.addView(status);
        dial = new DialView(); content.addView(dial, new LinearLayout.LayoutParams(-1, dp(242)));
        dial.setOnClickListener(v -> enterBpm());

        LinearLayout adjust = row();
        Button minus = button("−", CARD, WHITE), plus = button("+", CARD, WHITE);
        minus.setTextSize(24); plus.setTextSize(24);
        minus.setContentDescription("步频减少 1"); plus.setContentDescription("步频增加 1");
        adjust.addView(minus, new LinearLayout.LayoutParams(dp(52), dp(48)));
        bpmSlider = new SeekBar(this); bpmSlider.setMax(BeatClock.MAX_BPM - BeatClock.MIN_BPM);
        bpmSlider.setContentDescription("调整每分钟步频，40 至 220"); tintSlider(bpmSlider);
        adjust.addView(bpmSlider, new LinearLayout.LayoutParams(0, dp(48), 1));
        adjust.addView(plus, new LinearLayout.LayoutParams(dp(52), dp(48))); content.addView(adjust);
        minus.setOnClickListener(v -> changeBpm(settings.bpm - 1)); plus.setOnClickListener(v -> changeBpm(settings.bpm + 1));
        minus.setOnLongClickListener(v -> { changeBpm(settings.bpm - 5); return true; });
        plus.setOnLongClickListener(v -> { changeBpm(settings.bpm + 5); return true; });
        bpmSlider.setOnSeekBarChangeListener(slider(value -> { if (!syncing) changeBpm(value + BeatClock.MIN_BPM); }));
        LinearLayout scale = row();
        TextView low = text("40  慢一些", 11, MUTED), high = text("快一些  220", 11, MUTED); high.setGravity(Gravity.END);
        scale.addView(low, new LinearLayout.LayoutParams(0, dp(26), 1)); scale.addView(high, new LinearLayout.LayoutParams(0, dp(26), 1)); content.addView(scale);
        tap = button("轻点测速  ·  按你的步伐连续点按", BG, MUTED);
        tap.setTextSize(12); content.addView(tap, new LinearLayout.LayoutParams(-1, dp(44)));
        tap.setOnClickListener(v -> tapTempo()); space(content, 18);

        LinearLayout feedback = card(); feedback.addView(text("节拍提示", 15, WHITE)); space(feedback, 12);
        soundSwitch = option(feedback, "声音", "清脆的短音，跟随每一步");
        addLevel(feedback, "音量", settings.volume, value -> { settings.volume = value; changed(); });
        space(feedback, 10);
        vibrationSwitch = option(feedback, "震动", hasVibrator ? "把手机放进口袋，感受节奏" : "当前设备不支持震动");
        vibrationSwitch.setEnabled(hasVibrator);
        addLevel(feedback, "强度", settings.strength, value -> { settings.strength = value; changed(); });
        soundSwitch.setOnCheckedChangeListener((b, checked) -> {
            if (syncing) return;
            if (!checked && !settings.vibration) { toast("请至少保留一种提示方式"); refreshSettings(); return; }
            settings.sound = checked; changed();
        });
        vibrationSwitch.setOnCheckedChangeListener((b, checked) -> {
            if (syncing) return;
            if (!checked && !settings.sound) { toast("请至少保留一种提示方式"); refreshSettings(); return; }
            settings.vibration = checked; changed();
        });
        content.addView(feedback); space(content, 14);
        LinearLayout timerCard = card();
        LinearLayout timerTitle = row(); timerTitle.addView(text("训练定时", 15, WHITE), new LinearLayout.LayoutParams(0, -2, 1));
        durationLabel = text("自由练习", 12, MUTED); timerTitle.addView(durationLabel); timerCard.addView(timerTitle); space(timerCard, 12);
        LinearLayout timerRow = row();
        for (int i = 0; i < durations.length; i++) {
            final int minutes = durations[i]; timers[i] = button(minutes == 0 ? "不限" : minutes + " 分钟", BG, MUTED); timers[i].setTextSize(12);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(42), 1); if (i > 0) p.leftMargin = dp(6);
            timerRow.addView(timers[i], p); timers[i].setOnClickListener(v -> { settings.minutes = minutes; changed(); refreshSettings(); });
        }
        timerCard.addView(timerRow); content.addView(timerCard); space(content, 16);
        LinearLayout stats = row(); elapsed = text("00:00", 23, WHITE); beats = text("0", 23, WHITE);
        stats.addView(stat(elapsed, "训练时长"), new LinearLayout.LayoutParams(0, -2, 1));
        stats.addView(stat(beats, "已发出节拍"), new LinearLayout.LayoutParams(0, -2, 1)); content.addView(stats);
        space(content, 16); TextView note = text("一拍一步 · 以上为目标步频，不是实际步数\n可锁屏使用，声音大小同时受系统媒体音量控制", 11, MUTED);
        note.setGravity(Gravity.CENTER); note.setLineSpacing(dp(4), 1); content.addView(note);
        space(content, 18);
        LinearLayout background = card(); background.addView(text("锁屏与后台运行", 15, WHITE));
        backgroundStatus = text("", 12, MUTED); backgroundStatus.setLineSpacing(dp(4), 1); space(background, 8); background.addView(backgroundStatus);
        LinearLayout backgroundButtons = row();
        Button powerSettings = button("后台运行设置", BG, GREEN), diagnostics = button("运行诊断", BG, MUTED);
        LinearLayout.LayoutParams powerParams = new LinearLayout.LayoutParams(0, dp(48), 1); powerParams.rightMargin = dp(8);
        backgroundButtons.addView(powerSettings, powerParams); backgroundButtons.addView(diagnostics, new LinearLayout.LayoutParams(0, dp(48), 1));
        space(background, 12); background.addView(backgroundButtons); content.addView(background);
        powerSettings.setOnClickListener(v -> showBackgroundSettings());
        diagnostics.setOnClickListener(v -> showDiagnostics());

        LinearLayout footer = row(); footer.setPadding(dp(24), dp(12), dp(24), dp(14));
        reset = button("结束", CARD, MUTED); footer.addView(reset, new LinearLayout.LayoutParams(dp(72), dp(56)));
        play = button("▶  开始节拍", GREEN, BG); play.setTextSize(17); play.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(0, dp(56), 1); pp.leftMargin = dp(12); footer.addView(play, pp); root.addView(footer);
        play.setOnClickListener(v -> togglePlayback());
        reset.setOnClickListener(v -> {
            if (MetronomeService.alive) send(MetronomeService.STOP);
            else { MetronomeService.state = new MetronomeService.Snapshot(false, 0, 0, 0, false); MetronomeService.message = ""; }
        });
        setContentView(root); root.requestApplyInsets();
    }

    private void togglePlayback() {
        if (MetronomeService.state.running || MetronomeService.state.waitingForFocus) { send(MetronomeService.PAUSE); return; }
        if (MetronomeService.alive) return;
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED
                && !getPreferences(MODE_PRIVATE).getBoolean("notificationAsked", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notificationAsked", true).apply();
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 101);
            return;
        }
        begin();
    }
    private void begin() {
        settings.save(this);
        try { startForegroundService(new Intent(this, MetronomeService.class).setAction(MetronomeService.START)); }
        catch (RuntimeException e) { toast("无法启动后台节拍，请重新打开应用后重试"); }
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 101) {
            if (results.length == 0) return;
            if (results[0] != PackageManager.PERMISSION_GRANTED) toast("通知未开启，仍可在应用内暂停或结束");
            begin();
        }
    }
    private void send(String action) { startService(new Intent(this, MetronomeService.class).setAction(action)); }
    private void changed() { settings.save(this); if (MetronomeService.alive) send(MetronomeService.UPDATE); }
    private void changeBpm(int value) {
        int next = BeatClock.clamp(value); if (next == settings.bpm) return;
        settings.bpm = next; changed(); refreshSettings();
    }
    private void refreshSettings() {
        syncing = true;
        for (int i = 0; i < modes.length; i++) style(modes[i], i == settings.mode ? GREEN : CARD, i == settings.mode ? BG : MUTED);
        for (int i = 0; i < timers.length; i++) style(timers[i], durations[i] == settings.minutes ? GREEN : BG, durations[i] == settings.minutes ? BG : MUTED);
        bpmSlider.setProgress(settings.bpm - BeatClock.MIN_BPM);
        soundSwitch.setChecked(settings.sound); vibrationSwitch.setChecked(settings.vibration);
        durationLabel.setText(settings.minutes == 0 ? "自由练习" : "到时自动停止");
        dial.setContentDescription("目标步频 " + settings.bpm + " 步每分钟，点击输入数值"); dial.invalidate(); syncing = false;
    }
    private void enterBpm() {
        EditText input = new EditText(this); input.setInputType(InputType.TYPE_CLASS_NUMBER); input.setText(String.valueOf(settings.bpm)); input.selectAll(); input.setSingleLine(true);
        LinearLayout box = column(); box.setPadding(dp(24), dp(8), dp(24), 0); box.addView(input);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("设置目标步频").setMessage("每分钟 40–220 拍，一拍对应一步")
            .setView(box).setNegativeButton("取消", null).setPositiveButton("确定", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                int value = Integer.parseInt(input.getText().toString());
                if (value < 40 || value > 220) { input.setError("请输入 40–220"); return; }
                changeBpm(value); dialog.dismiss();
            } catch (NumberFormatException e) { input.setError("请输入有效整数"); }
        })); dialog.show();
    }
    private void tapTempo() {
        long now = SystemClock.elapsedRealtime();
        if (!taps.isEmpty() && now - taps.get(taps.size() - 1) > 2000) taps.clear();
        if (!taps.isEmpty() && now - taps.get(taps.size() - 1) < 180) return;
        taps.add(now); if (taps.size() > 6) taps.remove(0);
        if (taps.size() > 1) {
            changeBpm((int) Math.round(60_000.0 * (taps.size() - 1) / (now - taps.get(0))));
            tap.setText("已匹配 " + settings.bpm + " 步/分钟 · 继续点按");
        } else tap.setText("继续按步伐点按…");
    }
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            MetronomeService.Snapshot s = MetronomeService.state;
            elapsed.setText(time(s.elapsedNow())); beats.setText(String.valueOf(s.beats));
            play.setText(s.waitingForFocus ? "Ⅱ  取消自动继续" : s.running ? "Ⅱ  暂停节拍" : (s.elapsed > 0 && !s.complete ? "▶  继续训练" : "▶  开始节拍"));
            play.setEnabled(s.running || s.waitingForFocus || !MetronomeService.alive);
            reset.setEnabled(s.beats > 0 || s.running || s.waitingForFocus); reset.setAlpha(reset.isEnabled() ? 1 : .4f);
            status.setText(s.waitingForFocus ? "Ⅱ  音频暂被占用，等待恢复" : s.running ? "●  " + Settings.MODES[settings.mode] + "中 · 跟随节拍" : s.complete ? "✓  本次训练已完成" : s.elapsed > 0 ? "Ⅱ  已暂停，随时继续" : "●  准备开始");
            if (s.running && settings.minutes > 0) durationLabel.setText("剩余 " + time(Math.max(0, settings.minutes * 60_000L - s.elapsedNow())));
            else durationLabel.setText(settings.minutes == 0 ? "自由练习" : "到时自动停止");
            if (s.beats != seenBeat) { if (s.running) dial.pulseAt = SystemClock.uptimeMillis(); seenBeat = s.beats; }
            String message = MetronomeService.message;
            if (!message.isEmpty() && !message.equals(seenMessage)) toast(message);
            seenMessage = message; dial.invalidate(); ui.postDelayed(this, 50);
        }
    };
    @Override protected void onResume() {
        super.onResume();
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        backgroundStatus.setText(power.isIgnoringBatteryOptimizations(getPackageName())
            ? "已豁免系统电池优化。若锁屏后仍停止，请检查手机的后台运行限制，并查看运行诊断。"
            : "当前未豁免电池优化。若锁屏十几分钟后停止，可在系统设置中允许后台运行。仅有常驻通知无法保证持续运行。");
        ui.post(refresh);
    }
    @Override protected void onPause() { ui.removeCallbacks(refresh); super.onPause(); }
    private String time(long millis) { long seconds = millis / 1000; return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60); }

    private void showBackgroundSettings() {
        new AlertDialog.Builder(this).setTitle("允许锁屏后持续运行")
            .setMessage("在系统设置中找到“步调”，将电池策略改为“不限制”或允许后台运行。部分手机还需要在应用详情中允许自启动。\n\n不同手机名称可能不同，这可能增加耗电；应用不会自动修改这些设置。若仍停止，请打开“运行诊断”查看记录。")
            .setPositiveButton("电池优化设置", (d, w) -> openSystemSettings(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)))
            .setNeutralButton("应用详情", (d, w) -> openSystemSettings(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + getPackageName()))))
            .setNegativeButton("暂不设置", null).show();
    }
    private void openSystemSettings(Intent intent) {
        try { startActivity(intent); }
        catch (ActivityNotFoundException | SecurityException e) { toast("请在手机设置中打开：应用 → 步调 → 电池或后台运行"); }
    }
    private void showDiagnostics() {
        String report = RunJournal.describe(this);
        TextView details = text(report, 13, WHITE); details.setTextIsSelectable(true); details.setPadding(dp(20), dp(8), dp(20), dp(8));
        ScrollView scroll = new ScrollView(this); scroll.addView(details);
        new AlertDialog.Builder(this).setTitle("运行诊断").setView(scroll).setNegativeButton("关闭", null)
            .setPositiveButton("复制记录", (d, w) -> {
                ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("步调运行诊断", report));
                toast("诊断记录已复制");
            }).show();
    }

    private interface ValueChanged { void accept(int value); }
    private SeekBar.OnSeekBarChangeListener slider(ValueChanged changed) {
        return new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int value, boolean fromUser) { if (fromUser) changed.accept(value); }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) { }
        };
    }
    private void addLevel(LinearLayout parent, String label, int initial, ValueChanged change) {
        LinearLayout line = row(); TextView title = text(label, 11, MUTED); line.addView(title, new LinearLayout.LayoutParams(dp(36), -2));
        SeekBar slider = new SeekBar(this); slider.setMax(99); slider.setProgress(initial - 1); slider.setContentDescription(label); tintSlider(slider);
        line.addView(slider, new LinearLayout.LayoutParams(0, dp(36), 1));
        TextView value = text(initial + "%", 11, MUTED); value.setGravity(Gravity.END); line.addView(value, new LinearLayout.LayoutParams(dp(40), -2));
        slider.setOnSeekBarChangeListener(slider(v -> { value.setText((v + 1) + "%"); change.accept(v + 1); })); parent.addView(line);
    }
    private Switch option(LinearLayout parent, String title, String subtitle) {
        LinearLayout line = row(), labels = column(); labels.addView(text(title, 15, WHITE)); labels.addView(text(subtitle, 11, MUTED));
        line.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        Switch toggle = new Switch(this); toggle.setContentDescription(title + "提示");
        toggle.setThumbTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}}, new int[]{GREEN,MUTED}));
        toggle.setTrackTintList(ColorStateList.valueOf(Color.rgb(67,83,62)));
        line.addView(toggle, new LinearLayout.LayoutParams(dp(56), dp(48))); parent.addView(line); return toggle;
    }
    private LinearLayout stat(TextView value, String label) {
        LinearLayout c = column(); c.setGravity(Gravity.CENTER); value.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        c.addView(value); c.addView(text(label, 11, MUTED)); return c;
    }
    private void tintSlider(SeekBar bar) { bar.setProgressTintList(ColorStateList.valueOf(GREEN)); bar.setThumbTintList(ColorStateList.valueOf(GREEN)); bar.setProgressBackgroundTintList(ColorStateList.valueOf(0xff40503b)); }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private LinearLayout card() { LinearLayout l = column(); l.setPadding(dp(18), dp(16), dp(18), dp(16)); l.setBackground(shape(CARD, 20)); return l; }
    private TextView text(String value, int size, int color) { TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setGravity(Gravity.CENTER_VERTICAL); return t; }
    private Button button(String label, int bg, int fg) {
        Button b = new Button(this); b.setText(label); b.setTextSize(14); b.setAllCaps(false); b.setMinWidth(0); b.setMinimumWidth(0); b.setMinHeight(0); b.setMinimumHeight(0); b.setPadding(dp(4), 0, dp(4), 0); b.setStateListAnimator(null); style(b, bg, fg); return b;
    }
    private void style(Button b, int bg, int fg) {
        b.setTextColor(fg); b.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x307f9971), shape(bg, 14), null));
    }
    private GradientDrawable shape(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private void space(LinearLayout l, int height) { l.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height))); }
    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private final class DialView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        long pulseAt;
        DialView() { super(MainActivity.this); setFocusable(true); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth()/2f, cy = getHeight()/2f, r = Math.min(dp(110), getWidth()/2f - dp(10));
            float pulse = Math.max(0, 1 - (SystemClock.uptimeMillis() - pulseAt) / 200f);
            paint.setStyle(Paint.Style.FILL); paint.setColor(Color.argb((int)(10 + 16 * pulse), 197,243,107)); canvas.drawCircle(cx,cy,r-dp(12),paint);
            for (int i = 0; i < 60; i++) {
                double a = Math.toRadians(i * 6 - 90); float inner = r - dp(i % 5 == 0 ? 11 : 5);
                paint.setColor(i < (settings.bpm - 40) / 3f ? GREEN : 0xff354335); paint.setStrokeWidth(dp(i % 5 == 0 ? 2 : 1));
                canvas.drawLine(cx + inner * (float)Math.cos(a),cy + inner * (float)Math.sin(a),cx + r * (float)Math.cos(a),cy + r * (float)Math.sin(a),paint);
            }
            paint.setTextAlign(Paint.Align.CENTER); paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL)); paint.setTextSize(dp(11)); paint.setColor(MUTED);
            canvas.drawText("目 标 步 频", cx, cy - dp(43), paint);
            paint.setColor(WHITE); paint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL)); paint.setTextSize(dp(68));
            canvas.drawText(String.valueOf(settings.bpm), cx, cy + dp(21), paint);
            paint.setTextSize(dp(12)); paint.setColor(MUTED); canvas.drawText("步 / 分钟", cx, cy + dp(47), paint);
            for (int i = 0; i < 4; i++) {
                paint.setColor(MetronomeService.state.running && (MetronomeService.state.beats - 1) % 4 == i ? GREEN : 0xff40503b);
                canvas.drawCircle(cx + dp((i - 1.5f) * 14), cy + dp(70), dp(2.5f), paint);
            }
        }
    }
}
