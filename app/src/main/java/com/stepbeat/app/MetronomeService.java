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
        public final boolean running, complete, waitingForFocus;
        public final long elapsed, startedAt, beats;
        Snapshot(boolean running, long elapsed, long startedAt, long beats, boolean complete) {
            this(running, elapsed, startedAt, beats, complete, false);
        }
        Snapshot(boolean running, long elapsed, long startedAt, long beats, boolean complete, boolean waitingForFocus) {
            this.running = running; this.elapsed = elapsed; this.startedAt = startedAt;
            this.beats = beats; this.complete = complete;
            this.waitingForFocus = waitingForFocus;
        }
        public long elapsedNow() { return elapsed + (running ? SystemClock.elapsedRealtime() - startedAt : 0); }
    }

    private HandlerThread thread;
    private Handler worker;
    private Settings settings;
    private final BeatClock clock = new BeatClock();
    private final TrainingClock training = new TrainingClock();
    private SoundPool pool;
    private int clickSound;
    private boolean soundReady, soundFailed, focused, playing, closing, waitingForFocus;
    private float focusVolume = 1f;
    private Vibrator vibrator;
    private PowerManager.WakeLock wakeLock;
    private AudioManager audio;
    private AudioFocusRequest focusRequest;
    private long beats, lastCheckpoint, maxDelayMillis;

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            worker.post(() -> { if ((playing || waitingForFocus) && settings.sound) finish(false, "耳机已断开，节拍已暂停"); });
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        alive = true;
        settings = Settings.load(this);
        training.restore(state.elapsed);
        beats = state.beats;
        thread = new HandlerThread("StepBeat-clock", android.os.Process.THREAD_PRIORITY_AUDIO);
        thread.start(); worker = new Handler(thread.getLooper());
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes).setOnAudioFocusChangeListener(this::onFocusChanged, worker).build();
        pool = new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attributes).build();
        pool.setOnLoadCompleteListener((p, id, status) -> worker.post(() -> {
            soundReady = status == 0;
            soundFailed = status != 0;
            if (soundFailed && (playing || waitingForFocus) && settings.sound) finish(false, "提示音加载失败，请切换震动模式");
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
            else finish(STOP.equals(action), STOP.equals(action) ? "已结束训练" : "已手动暂停");
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
        if (settings.sound && soundFailed) { finish(false, "提示音加载失败，请切换震动模式"); return; }
        if (waitingForFocus) {
            // Explicit user resume replaces the outstanding transient focus request.
            audio.abandonAudioFocusRequest(focusRequest); focused = false;
        }
        if (settings.sound && !requestFocus()) { finish(false, "暂时无法播放声音，请稍后重试"); return; }
        Snapshot previous = state;
        training.restore(previous.complete ? 0 : previous.elapsed);
        beats = previous.complete ? 0 : previous.beats;
        if (training.expired(settings.minutes, SystemClock.elapsedRealtime())) { training.restore(0); beats = 0; }
        focusVolume = 1f;
        resumeCadence("开始或继续训练");
    }

    private void resumeCadence(String reason) {
        waitingForFocus = false;
        training.resume(SystemClock.elapsedRealtime());
        playing = true; message = "";
        if (!wakeLock.isHeld()) wakeLock.acquire();
        publish(false);
        RunJournal.event(this, reason);
        checkpoint(true);
        clock.reset(SystemClock.elapsedRealtimeNanos() + 150_000_000L, settings.bpm);
        worker.postDelayed(tick, 150);
        worker.postDelayed(checkDuration, 100);
        updateNotification();
    }

    private void onFocusChanged(int change) {
        if (closing || !settings.sound || (!playing && !waitingForFocus)) return;
        if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            focusVolume = .2f;
            RunJournal.event(this, "音频临时降低音量，节拍继续");
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            if (!playing) return;
            training.pause(SystemClock.elapsedRealtime());
            playing = false; waitingForFocus = true;
            worker.removeCallbacks(tick); worker.removeCallbacks(checkDuration);
            pool.autoPause();
            if (vibrator != null) vibrator.cancel();
            if (wakeLock.isHeld()) wakeLock.release();
            message = "音频暂被占用，恢复后继续；等待期间不计时";
            publish(false);
            RunJournal.event(this, message); checkpoint(true);
            updateNotification();
        } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
            focusVolume = 1f;
            if (waitingForFocus) resumeCadence("音频恢复，自动继续训练");
        } else if (change == AudioManager.AUDIOFOCUS_LOSS) {
            finish(false, "音频被其他应用持续占用，请手动继续训练");
        }
    }

    private void checkpoint(boolean active) {
        lastCheckpoint = SystemClock.elapsedRealtime();
        RunJournal.checkpoint(this, active, training.elapsedAt(lastCheckpoint), beats, maxDelayMillis);
    }

    private boolean requestFocus() {
        if (focused) return true;
        focused = audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        return focused;
    }

    private void updateSettings() {
        int oldBpm = settings.bpm;
        settings = Settings.load(this);
        if (!playing && !waitingForFocus) return;
        if (!settings.sound && !settings.vibration) { finish(false, "请至少开启一种提示方式"); return; }
        if (settings.sound && !requestFocus()) { finish(false, "暂时无法播放声音，请稍后重试"); return; }
        if (!settings.sound && focused) { audio.abandonAudioFocusRequest(focusRequest); focused = false; pool.autoPause(); }
        if (!settings.vibration && vibrator != null) vibrator.cancel();
        if (waitingForFocus) {
            if (!settings.sound) { focusVolume = 1f; resumeCadence("已切换为震动，继续训练"); }
            else updateNotification();
            return;
        }
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
            maxDelayMillis = Math.max(maxDelayMillis, Math.max(0, (SystemClock.elapsedRealtimeNanos() - clock.deadline()) / 1_000_000L));
            if (settings.sound && soundReady) {
                float gain = settings.volume / 100f * focusVolume;
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
            else {
                if (SystemClock.elapsedRealtime() - lastCheckpoint >= 15_000) checkpoint(true);
                worker.postDelayed(this, 100);
            }
        }
    };

    private boolean expired() {
        return training.expired(settings.minutes, SystemClock.elapsedRealtime());
    }
    private void publish(boolean complete) { state = new Snapshot(playing, training.accumulated(), training.startedAt(), beats, complete, waitingForFocus); }
    private void finish(boolean reset, String reason) {
        if (closing) return;
        closing = true;
        training.pause(SystemClock.elapsedRealtime());
        playing = false; waitingForFocus = false;
        worker.removeCallbacks(tick); worker.removeCallbacks(checkDuration);
        RunJournal.event(this, reason); checkpoint(false);
        if (reset) { training.restore(0); beats = 0; }
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
            .setContentText(waitingForFocus ? "音频暂被占用 · 恢复后继续，等待期间不计时" : "跟随节拍，自在前行 · 点击返回控制")
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
            if (playing || waitingForFocus) {
                training.pause(SystemClock.elapsedRealtime());
                playing = false; waitingForFocus = false; publish(false);
                RunJournal.event(this, "后台服务被结束，原因未确定"); checkpoint(false);
            }
            worker.removeCallbacks(tick); worker.removeCallbacks(checkDuration);
            releasePlayback(); pool.release(); thread.quitSafely();
        });
        super.onDestroy();
    }
}
