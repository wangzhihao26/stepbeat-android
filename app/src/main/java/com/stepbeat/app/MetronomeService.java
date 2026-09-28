package com.stepbeat.app;

import android.app.*;
import android.content.*;
import android.media.*;
import android.os.*;

/** Owns all playback resources; timing and state changes run on one worker. */
public final class MetronomeService extends Service {
    public static final String START = "start", PAUSE = "pause", STOP = "stop", UPDATE = "update";
    private static final String CHANNEL = "cadence";
    public static volatile Snapshot state = new Snapshot(false, 0, 0, 0, false);
    public static volatile String message = "";
    public static volatile boolean alive;

    public static final class Snapshot {
        public final boolean running, complete;
        public final long elapsed, startedAt, beats;
        Snapshot(boolean running, long elapsed, long startedAt, long beats, boolean complete) {
            this.running = running; this.elapsed = elapsed; this.startedAt = startedAt;
            this.beats = beats; this.complete = complete;
        }
        public long elapsedNow() { return elapsed + (running ? SystemClock.elapsedRealtime() - startedAt : 0); }
    }

    private HandlerThread thread;
    private Handler worker;
    private Settings settings;
    private final BeatClock clock = new BeatClock();
    private SoundPool pool;
    private int clickSound;
    private boolean soundReady, focused, playing, closing;
    private Vibrator vibrator;
    private PowerManager.WakeLock wakeLock;
    private AudioManager audio;
    private AudioFocusRequest focusRequest;
    private long elapsed, startedAt, beats;

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            worker.post(() -> { if (playing && settings.sound) finish(false, "耳机已断开，节拍已暂停"); });
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        alive = true;
        settings = Settings.load(this);
        elapsed = state.elapsed;
        beats = state.beats;
        thread = new HandlerThread("StepBeat-clock", android.os.Process.THREAD_PRIORITY_AUDIO);
        thread.start(); worker = new Handler(thread.getLooper());
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes).setOnAudioFocusChangeListener(change -> {
                if (change < 0 && playing) finish(false, "音频被其他应用占用，节拍已暂停");
            }, worker).build();
        pool = new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attributes).build();
        pool.setOnLoadCompleteListener((p, id, status) -> worker.post(() -> {
            soundReady = status == 0;
            if (status != 0 && playing && settings.sound) finish(false, "提示音加载失败，请切换震动模式");
        }));
        clickSound = pool.load(this, R.raw.click, 1);
        wakeLock = ((PowerManager) getSystemService(POWER_SERVICE))
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StepBeat:cadence");
        NotificationChannel channel = new NotificationChannel(CHANNEL, "运动节拍", NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null); channel.enableVibration(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        registerReceiver(noisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
    }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        String action = intent == null ? STOP : intent.getAction();
        if (START.equals(action)) {
            // SPECIAL_USE = 1 << 30. Literal keeps the offline API 32 build usable.
            if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification(), 1 << 30);
            else startForeground(1, notification());
        }
        worker.post(() -> {
            if (closing) return;
            if (START.equals(action)) startPlaying();
            else if (UPDATE.equals(action)) updateSettings();
            else finish(STOP.equals(action), "");
        });
        return START_NOT_STICKY;
    }

    private void startPlaying() {
        if (playing) return;
        settings = Settings.load(this);
        if (!settings.sound && !settings.vibration) { finish(false, "请至少开启一种提示方式"); return; }
        if (!settings.sound && (vibrator == null || !vibrator.hasVibrator())) {
            finish(false, "设备不支持震动，请开启声音提示"); return;
        }
        if (settings.sound && !requestFocus()) { finish(false, "暂时无法播放声音，请稍后重试"); return; }
        Snapshot previous = state;
        elapsed = previous.complete ? 0 : previous.elapsed;
        beats = previous.complete ? 0 : previous.beats;
        if (settings.minutes > 0 && elapsed >= settings.minutes * 60_000L) { elapsed = 0; beats = 0; }
        startedAt = SystemClock.elapsedRealtime();
        playing = true; message = "";
        wakeLock.acquire();
        publish(false);
        clock.reset(SystemClock.elapsedRealtimeNanos() + 150_000_000L, settings.bpm);
        worker.postDelayed(tick, 150);
        worker.postDelayed(checkDuration, 100);
        updateNotification();
    }

    private boolean requestFocus() {
        if (focused) return true;
        focused = audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        return focused;
    }

    private void updateSettings() {
        int oldBpm = settings.bpm;
        settings = Settings.load(this);
        if (!playing) return;
        if (!settings.sound && !settings.vibration) { finish(false, "请至少开启一种提示方式"); return; }
        if (settings.sound && !requestFocus()) { finish(false, "暂时无法播放声音，请稍后重试"); return; }
        if (!settings.sound && focused) { audio.abandonAudioFocusRequest(focusRequest); focused = false; pool.autoPause(); }
        if (!settings.vibration && vibrator != null) vibrator.cancel();
        if (oldBpm != settings.bpm) {
            worker.removeCallbacks(tick);
            clock.reset(SystemClock.elapsedRealtimeNanos(), settings.bpm);
            worker.post(tick);
        }
        updateNotification();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!playing) return;
            if (expired()) { finish(false, "本次训练已完成"); return; }
            if (settings.sound && soundReady) {
                float gain = settings.volume / 100f;
                pool.play(clickSound, gain, gain, 1, 0, 1f);
            }
            if (settings.vibration && vibrator != null && vibrator.hasVibrator()) {
                int amplitude = vibrator.hasAmplitudeControl() ? Math.max(1, settings.strength * 255 / 100) : VibrationEffect.DEFAULT_AMPLITUDE;
                long duration = 15 + settings.strength * 45L / 100;
                vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude),
                    new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build());
            }
            beats++; publish(false);
            long now = SystemClock.elapsedRealtimeNanos();
            long deadline = clock.advance(now);
            worker.postDelayed(this, Math.max(1, (deadline - now + 999_999) / 1_000_000));
        }
    };

    private final Runnable checkDuration = new Runnable() {
        @Override public void run() {
            if (!playing) return;
            if (expired()) finish(false, "本次训练已完成");
            else worker.postDelayed(this, 100);
        }
    };

    private boolean expired() {
        return settings.minutes > 0 && elapsed + SystemClock.elapsedRealtime() - startedAt >= settings.minutes * 60_000L;
    }
    private void publish(boolean complete) { state = new Snapshot(playing, elapsed, startedAt, beats, complete); }
    private void finish(boolean reset, String reason) {
        if (closing) return;
        closing = true;
        if (playing) elapsed += SystemClock.elapsedRealtime() - startedAt;
        playing = false;
        worker.removeCallbacks(tick); worker.removeCallbacks(checkDuration);
        if (reset) { elapsed = 0; beats = 0; }
        message = reason;
        publish("本次训练已完成".equals(reason));
        releasePlayback();
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
    }
    private void releasePlayback() {
        if (wakeLock.isHeld()) wakeLock.release();
        if (vibrator != null) vibrator.cancel();
        pool.autoPause();
        if (focused) { audio.abandonAudioFocusRequest(focusRequest); focused = false; }
    }
    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent pause = PendingIntent.getService(this, 1, new Intent(this, MetronomeService.class).setAction(PAUSE), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 2, new Intent(this, MetronomeService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("步调 · " + Settings.MODES[settings.mode] + " · " + settings.bpm + " 步/分钟")
            .setContentText("跟随节拍，自在前行 · 点击返回控制")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(new Notification.Action.Builder(null, "暂停", pause).build())
            .addAction(new Notification.Action.Builder(null, "结束", stop).build()).build();
    }
    private void updateNotification() { getSystemService(NotificationManager.class).notify(1, notification()); }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        alive = false;
        unregisterReceiver(noisyReceiver);
        worker.post(() -> {
            if (playing) {
                elapsed += SystemClock.elapsedRealtime() - startedAt;
                playing = false; publish(false);
            }
            worker.removeCallbacks(tick); worker.removeCallbacks(checkDuration);
            releasePlayback(); pool.release(); thread.quitSafely();
        });
        super.onDestroy();
    }
}
