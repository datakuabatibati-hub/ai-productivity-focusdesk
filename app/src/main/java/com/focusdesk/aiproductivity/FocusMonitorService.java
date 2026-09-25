package com.focusdesk.aiproductivity;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.NotificationCompat;
import androidx.lifecycle.LifecycleService;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FocusMonitorService extends LifecycleService {

    public static final String ACTION_FOCUS = "com.focusdesk.ai.action.FOCUS";
    public static final String ACTION_BREAK = "com.focusdesk.ai.action.BREAK";
    public static final String ACTION_DONE = "com.focusdesk.ai.action.DONE";
    public static final String ACTION_TEST_TELEGRAM = "com.focusdesk.ai.action.TEST";
    public static final String ACTION_STOP_SERVICE = "com.focusdesk.ai.action.STOP";

    private static final String CHANNEL_ID = "ai_productivity_monitor";
    private static final int NOTIFICATION_ID = 41;

    private enum Mode { IDLE, FOCUS, BREAK }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();

    private SharedPreferences prefs;
    private FaceDetector faceDetector;
    private ProcessCameraProvider cameraProvider;
    private PowerManager.WakeLock wakeLock;

    private Mode mode = Mode.IDLE;
    private boolean presence = false;
    private boolean confirmedAway = false;
    private boolean cameraStarted = false;
    private boolean alert1Sent = false;
    private boolean alert2Sent = false;

    private long detectionIntervalMs = 5000L;
    private long awayDetectMs = 20_000L;
    private long alert1Ms = 120_000L;
    private long alert2Ms = 300_000L;
    private long autoBreakMs = 600_000L;
    private long targetFocusSec = 4L * 60L * 60L;
    private long sprintTargetSec = 0L;

    private long dayFocusSec = 0L;
    private long dayAwayTimeSec = 0L;
    private int dayAway = 0;
    private int daySessions = 0;
    private long longestSessionSec = 0L;

    private long sessionFocusSec = 0L;
    private int sessionAway = 0;
    private String sessionTask = "";
    private String sessionCategory = "";

    private long segmentStartedAt = 0L;
    private long absenceStartedAt = 0L;
    private long lastAnalysisAt = 0L;
    private long lastCheckpointAt = 0L;
    private String storedDate = "";

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            checkDateRollover();
            checkpointIfNeeded();
            checkAwayEscalation();
            checkSprintTarget();
            persistRuntime();
            updateNotification();
            handler.postDelayed(this, 5000L);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(ProductivityStore.PREFS, MODE_PRIVATE);
        createNotificationChannel();
        startForegroundCompat(buildNotification("AI Productivity siap"));
        prefs.edit().putBoolean("runtime_service_running", true).apply();
        loadSettings();
        loadStats();
        setupFaceDetector();
        setupWakeLock();
        handler.post(ticker);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        String action = intent != null ? intent.getAction() : null;
        loadSettings();

        if (ACTION_FOCUS.equals(action)) startFocus();
        else if (ACTION_BREAK.equals(action)) startBreak(false);
        else if (ACTION_DONE.equals(action)) finishSession();
        else if (ACTION_TEST_TELEGRAM.equals(action)) {
            sendTelegram("⌚ AI PRODUCTIVITY TEST\n\nJika pesan ini muncul di Garmin, jalur notifikasi sudah bekerja.");
        } else if (ACTION_STOP_SERVICE.equals(action)) stopSelfSafely();
        else if (action == null && "FOCUS".equals(prefs.getString("runtime_mode", "IDLE"))) restoreFocusAfterRestart();

        return START_STICKY;
    }

    private void restoreFocusAfterRestart() {
        mode = Mode.FOCUS;
        sessionFocusSec = prefs.getLong("runtime_session_focus", 0L);
        sessionAway = prefs.getInt("runtime_session_away", 0);
        sessionTask = prefs.getString("runtime_session_task", prefs.getString("current_task", ""));
        sessionCategory = prefs.getString("runtime_session_category", prefs.getString("current_category", "Lainnya"));
        confirmedAway = false;
        absenceStartedAt = 0L;
        segmentStartedAt = SystemClock.elapsedRealtime();
        acquireWakeLock();
        startCamera();
    }

    private void setupFaceDetector() {
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.15f)
                .build();
        faceDetector = FaceDetection.getClient(options);
    }

    private void setupWakeLock() {
        PowerManager pm = (PowerManager)getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AIProductivity::MonitorWakeLock");
        wakeLock.setReferenceCounted(false);
    }

    private void acquireWakeLock() {
        if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    private void startCamera() {
        if (cameraStarted) return;
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(cameraExecutor, this::analyzeFrame);
                CameraSelector selector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                        .build();
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, selector, analysis);
                cameraStarted = true;
                updateNotification();
            } catch (Exception e) {
                cameraStarted = false;
                sendTelegram("⚠️ AI Productivity: kamera gagal dimulai. Buka aplikasi dan cek izin kamera.");
            }
        }, command -> handler.post(command));
    }

    private void stopCamera() {
        if (cameraProvider != null) cameraProvider.unbindAll();
        cameraStarted = false;
        presence = false;
        persistRuntime();
    }

    private void analyzeFrame(@NonNull ImageProxy imageProxy) {
        long now = SystemClock.elapsedRealtime();
        if (mode != Mode.FOCUS || now - lastAnalysisAt < detectionIntervalMs) {
            imageProxy.close();
            return;
        }
        lastAnalysisAt = now;
        if (imageProxy.getImage() == null) {
            imageProxy.close();
            return;
        }
        InputImage input = InputImage.fromMediaImage(imageProxy.getImage(), imageProxy.getImageInfo().getRotationDegrees());
        faceDetector.process(input)
                .addOnSuccessListener(faces -> handler.post(() -> onPresenceResult(!faces.isEmpty())))
                .addOnCompleteListener(task -> imageProxy.close());
    }

    private void onPresenceResult(boolean detected) {
        presence = detected;
        long now = SystemClock.elapsedRealtime();
        if (mode != Mode.FOCUS) return;

        if (detected) {
            if (confirmedAway) {
                long awaySec = Math.max(0L, (now - absenceStartedAt) / 1000L);
                dayAwayTimeSec += awaySec;
                ProductivityStore.addEvent(this, "RETURN", sessionTask, sessionCategory, awaySec, "Kembali setelah away");
                prefs.edit().putBoolean("recovery_pending", true).apply();
                confirmedAway = false;
                alert1Sent = false;
                alert2Sent = false;
                absenceStartedAt = 0L;
                segmentStartedAt = now;
            } else {
                absenceStartedAt = 0L;
                if (segmentStartedAt == 0L) segmentStartedAt = now;
            }
            saveStats();
            persistRuntime();
            return;
        }

        if (absenceStartedAt == 0L) {
            absenceStartedAt = now;
            persistRuntime();
            return;
        }

        long awayElapsed = now - absenceStartedAt;
        if (!confirmedAway && awayElapsed >= awayDetectMs) {
            closeFocusSegment(absenceStartedAt);
            confirmedAway = true;
            dayAway++;
            sessionAway++;
            ProductivityStore.addEvent(this, "AWAY", sessionTask, sessionCategory, 0L, "Meninggalkan meja");
            saveStats();
            persistRuntime();
        }
    }

    private void checkAwayEscalation() {
        if (mode != Mode.FOCUS || !confirmedAway || absenceStartedAt == 0L) return;
        long elapsed = SystemClock.elapsedRealtime() - absenceStartedAt;

        if (!alert1Sent && elapsed >= alert1Ms) {
            alert1Sent = true;
            sendTelegram("⌚ KEMBALI FOKUS\n\nTugas: " + safeTask() + "\nCukup lakukan SATU langkah berikutnya. Tidak perlu mengejar waktu yang hilang.");
        }

        if (!alert2Sent && elapsed >= alert2Ms) {
            alert2Sent = true;
            sendTelegram("⌚ AI PRODUCTIVITY\n\nAnda sudah away sekitar " + Math.max(1L, elapsed / 60_000L) + " menit.\nJika memang istirahat, biarkan sesi dipause. Jika tidak, kembali ke: " + safeTask());
        }

        if (elapsed >= autoBreakMs) {
            long awaySec = Math.max(0L, elapsed / 1000L);
            dayAwayTimeSec += awaySec;
            ProductivityStore.addEvent(this, "AUTO_BREAK", sessionTask, sessionCategory, awaySec, "Away terlalu lama; sesi dipause otomatis");
            saveStats();
            mode = Mode.BREAK;
            confirmedAway = false;
            absenceStartedAt = 0L;
            segmentStartedAt = 0L;
            stopCamera();
            releaseWakeLock();
            persistRuntime();
            sendTelegram("⏸️ AUTO BREAK\n\nSesi dipause setelah Anda away terlalu lama.\nTugas terakhir: " + safeTask() + "\nBuka AI Productivity dan tekan FOCUS saat siap kembali.");
        }
    }

    private void startFocus() {
        checkDateRollover();
        loadSettings();
        String selectedTask = prefs.getString("current_task", "").trim();
        String selectedCategory = prefs.getString("current_category", "Lainnya");
        if (selectedTask.isEmpty()) return;

        // Jangan reset segment timer bila tombol FOCUS terpencet lagi saat sudah fokus.
        if (mode == Mode.FOCUS) {
            updateNotification();
            return;
        }

        if (mode == Mode.IDLE) {
            sessionFocusSec = 0L;
            sessionAway = 0;
            sessionTask = selectedTask;
            sessionCategory = selectedCategory;
            ProductivityStore.addEvent(this, "FOCUS_START", sessionTask, sessionCategory, 0L, "");
        } else if (mode == Mode.BREAK) {
            sessionTask = selectedTask;
            sessionCategory = selectedCategory;
            ProductivityStore.addEvent(this, "FOCUS_RESUME", sessionTask, sessionCategory, 0L, "");
        }

        mode = Mode.FOCUS;
        confirmedAway = false;
        alert1Sent = false;
        alert2Sent = false;
        absenceStartedAt = 0L;
        segmentStartedAt = SystemClock.elapsedRealtime();
        prefs.edit().putBoolean("recovery_pending", false).apply();
        acquireWakeLock();
        startCamera();
        persistRuntime();
        updateNotification();
    }

    private void startBreak(boolean automatic) {
        if (mode != Mode.FOCUS) return;
        long now = SystemClock.elapsedRealtime();
        if (confirmedAway && absenceStartedAt > 0L) {
            long awaySec = Math.max(0L, (now - absenceStartedAt) / 1000L);
            dayAwayTimeSec += awaySec;
        } else if (segmentStartedAt > 0L) {
            closeFocusSegment(now);
        }
        mode = Mode.BREAK;
        confirmedAway = false;
        absenceStartedAt = 0L;
        segmentStartedAt = 0L;
        ProductivityStore.addEvent(this, automatic ? "AUTO_BREAK" : "BREAK", sessionTask, sessionCategory, 0L, "");
        stopCamera();
        releaseWakeLock();
        saveStats();
        persistRuntime();
        updateNotification();
    }

    private void finishSession() {
        if (mode == Mode.IDLE) return;
        long now = SystemClock.elapsedRealtime();
        if (mode == Mode.FOCUS) {
            if (confirmedAway && absenceStartedAt > 0L) {
                dayAwayTimeSec += Math.max(0L, (now - absenceStartedAt) / 1000L);
            } else if (segmentStartedAt > 0L) {
                closeFocusSegment(now);
            }
        }

        mode = Mode.IDLE;
        daySessions++;
        longestSessionSec = Math.max(longestSessionSec, sessionFocusSec);
        saveStats();
        ProductivityStore.addEvent(this, "DONE", sessionTask, sessionCategory, sessionFocusSec, "Away sesi: " + sessionAway + "x");

        long progress = Math.round(Math.min(999.0, dayFocusSec * 100.0 / Math.max(1L, targetFocusSec)));
        sendTelegram("✅ SELESAI\n\nTugas: " + safeTask() + "\nFokus sesi: " + ProductivityStore.formatDuration(sessionFocusSec) +
                "\nFokus hari ini: " + ProductivityStore.formatDuration(dayFocusSec) +
                "\nAway sesi: " + sessionAway + "x\nProgress: " + progress + "%");

        sessionFocusSec = 0L;
        sessionAway = 0;
        sessionTask = "";
        sessionCategory = "";
        segmentStartedAt = 0L;
        absenceStartedAt = 0L;
        confirmedAway = false;
        alert1Sent = false;
        alert2Sent = false;
        prefs.edit().putLong("sprint_target_min", 0L).putBoolean("runtime_sprint_alerted", false).apply();
        sprintTargetSec = 0L;
        stopCamera();
        releaseWakeLock();
        persistRuntime();
        updateNotification();
    }

    private void closeFocusSegment(long endElapsed) {
        if (segmentStartedAt <= 0L || endElapsed <= segmentStartedAt) {
            segmentStartedAt = 0L;
            return;
        }
        long sec = (endElapsed - segmentStartedAt) / 1000L;
        dayFocusSec += sec;
        sessionFocusSec += sec;
        ProductivityStore.addCategoryFocus(prefs, sessionCategory, sec);
        segmentStartedAt = 0L;
    }

    private void checkpointIfNeeded() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastCheckpointAt < 60_000L) return;
        lastCheckpointAt = now;
        if (mode == Mode.FOCUS && !confirmedAway && absenceStartedAt == 0L && segmentStartedAt > 0L) {
            closeFocusSegment(now);
            segmentStartedAt = now;
        }
        saveStats();
    }


    private void checkSprintTarget() {
        if (mode != Mode.FOCUS || sprintTargetSec <= 0L) return;
        if (prefs.getBoolean("runtime_sprint_alerted", false)) return;

        long current = sessionFocusSec;
        if (!confirmedAway && segmentStartedAt > 0L) {
            current += Math.max(0L, (SystemClock.elapsedRealtime() - segmentStartedAt) / 1000L);
        }
        if (current >= sprintTargetSec) {
            prefs.edit().putBoolean("runtime_sprint_alerted", true).apply();
            long minutes = Math.max(1L, sprintTargetSec / 60L);
            sendTelegram("⌚ SPRINT SELESAI\n\n" + minutes + " menit tercapai.\nTugas: " + safeTask() +
                    "\n\nPilih: DONE jika unit selesai, BREAK jika butuh jeda, atau lanjutkan dengan sadar.");
            ProductivityStore.addEvent(this, "SPRINT_DONE", sessionTask, sessionCategory, sprintTargetSec, minutes + " menit");
        }
    }

    private void loadSettings() {
        detectionIntervalMs = clamp(prefs.getLong("interval_sec", 5L), 2L, 60L) * 1000L;
        awayDetectMs = clamp(prefs.getLong("away_sec", 20L), 10L, 600L) * 1000L;
        alert1Ms = Math.max(awayDetectMs, clamp(prefs.getLong("alert_sec", 120L), 20L, 3600L) * 1000L);
        alert2Ms = Math.max(alert1Ms + 60_000L, 300_000L);
        autoBreakMs = Math.max(alert2Ms + 60_000L, clamp(prefs.getLong("auto_break_min", 10L), 2L, 120L) * 60_000L);
        targetFocusSec = clamp(prefs.getLong("target_min", 240L), 15L, 1440L) * 60L;
        sprintTargetSec = Math.max(0L, prefs.getLong("sprint_target_min", 0L)) * 60L;
    }

    private void loadStats() {
        storedDate = prefs.getString("stats_date", ProductivityStore.today());
        dayFocusSec = prefs.getLong("day_focus", 0L);
        dayAwayTimeSec = prefs.getLong("day_away_time", 0L);
        dayAway = prefs.getInt("day_away", 0);
        daySessions = prefs.getInt("day_sessions", 0);
        longestSessionSec = prefs.getLong("longest_session", 0L);
        checkDateRollover();
    }

    private void saveStats() {
        prefs.edit()
                .putString("stats_date", storedDate)
                .putLong("day_focus", dayFocusSec)
                .putLong("day_away_time", dayAwayTimeSec)
                .putInt("day_away", dayAway)
                .putInt("day_sessions", daySessions)
                .putLong("longest_session", longestSessionSec)
                .apply();
    }

    private void persistRuntime() {
        long awayElapsed = (mode == Mode.FOCUS && absenceStartedAt > 0L)
                ? Math.max(0L, (SystemClock.elapsedRealtime() - absenceStartedAt) / 1000L) : 0L;
        prefs.edit()
                .putBoolean("runtime_service_running", true)
                .putString("runtime_mode", mode.name())
                .putBoolean("runtime_presence", presence)
                .putBoolean("runtime_confirmed_away", confirmedAway)
                .putLong("runtime_away_elapsed", awayElapsed)
                .putLong("runtime_session_focus", sessionFocusSec)
                .putInt("runtime_session_away", sessionAway)
                .putString("runtime_session_task", sessionTask)
                .putString("runtime_session_category", sessionCategory)
                .putLong("runtime_segment_started", segmentStartedAt)
                .apply();
    }

    private void checkDateRollover() {
        String today = ProductivityStore.today();
        if (storedDate == null || storedDate.isEmpty()) {
            storedDate = today;
            saveStats();
            return;
        }
        if (!storedDate.equals(today)) {
            mode = Mode.IDLE;
            dayFocusSec = 0L;
            dayAwayTimeSec = 0L;
            dayAway = 0;
            daySessions = 0;
            longestSessionSec = 0L;
            sessionFocusSec = 0L;
            sessionAway = 0;
            segmentStartedAt = 0L;
            absenceStartedAt = 0L;
            confirmedAway = false;
            storedDate = today;
            stopCamera();
            releaseWakeLock();
            prefs.edit()
                    .putInt("d_social", 0).putInt("d_people", 0).putInt("d_thought", 0).putInt("d_urgent", 0)
                    .putLong("focus_simkah", 0L).putLong("focus_surat", 0L).putLong("focus_laporan", 0L)
                    .putLong("focus_layanan", 0L).putLong("focus_lain", 0L)
                    .putInt("day_kinerja_count", 0)
                    .putLong("sprint_target_min", 0L).putBoolean("runtime_sprint_alerted", false)
                    .putString("day_events", "[]")
                    .apply();
            saveStats();
            persistRuntime();
        }
    }

    private String safeTask() {
        return sessionTask == null || sessionTask.trim().isEmpty() ? "tugas aktif" : sessionTask.trim();
    }

    private void sendTelegram(String message) {
        String token = prefs.getString("bot_token", "").trim();
        String chatId = prefs.getString("chat_id", "").trim();
        if (token.isEmpty() || chatId.isEmpty()) return;
        networkExecutor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL("https://api.telegram.org/bot" + token + "/sendMessage");
                connection = (HttpURLConnection)url.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(10_000);
                connection.setReadTimeout(10_000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                String body = "chat_id=" + URLEncoder.encode(chatId, "UTF-8") + "&text=" + URLEncoder.encode(message, "UTF-8");
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
                connection.getResponseCode();
            } catch (Exception ignored) {
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "AI Productivity Monitor", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Monitoring fokus saat layar mati");
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(this, 0, open,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT : PendingIntent.FLAG_UPDATE_CURRENT);
        Intent breakIntent = new Intent(this, FocusMonitorService.class).setAction(ACTION_BREAK);
        PendingIntent breakPending = PendingIntent.getService(this, 1, breakIntent,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT : PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_focus)
                .setContentTitle("AI Productivity")
                .setContentText(text)
                .setContentIntent(openPending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(0, "BREAK", breakPending)
                .build();
    }

    private void updateNotification() {
        String text;
        if (mode == Mode.FOCUS) {
            if (confirmedAway) text = "AWAY • kembali ke " + safeTask();
            else text = "FOCUS • " + safeTask();
        } else if (mode == Mode.BREAK) text = "BREAK • kamera berhenti";
        else text = "IDLE • pilih satu tugas";
        NotificationManager nm = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(NOTIFICATION_ID, buildNotification(text));
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        else startForeground(NOTIFICATION_ID, notification);
    }

    private void stopSelfSafely() {
        if (mode == Mode.FOCUS && !confirmedAway && segmentStartedAt > 0L) closeFocusSegment(SystemClock.elapsedRealtime());
        saveStats();
        stopCamera();
        releaseWakeLock();
        prefs.edit().putBoolean("runtime_service_running", false).putString("runtime_mode", "IDLE").apply();
        stopForeground(true);
        stopSelf();
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(ticker);
        stopCamera();
        releaseWakeLock();
        cameraExecutor.shutdown();
        networkExecutor.shutdown();
        if (faceDetector != null) faceDetector.close();
        prefs.edit().putBoolean("runtime_service_running", false).apply();
        super.onDestroy();
    }
}
