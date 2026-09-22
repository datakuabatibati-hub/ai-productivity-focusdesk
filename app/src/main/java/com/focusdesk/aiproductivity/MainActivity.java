package com.focusdesk.aiproductivity;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.speech.RecognizerIntent;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS = ProductivityStore.PREFS;

    private TextView tvCoach, tvCurrentTask, tvMode, tvPresence, tvToday, tvStats, tvSession, tvInbox, tvStatus;
    private ProgressBar progressTarget;
    private EditText etTask1, etTask2, etTask3;
    private EditText etBotToken, etChatId, etInterval, etAwaySeconds, etAlertSeconds, etAutoBreakMinutes, etTargetMinutes;
    private Spinner spCategory;

    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

    private final ActivityResultLauncher<String> cameraPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                tvStatus.setText(granted ? "Izin kamera OK." : "Izin kamera ditolak. Monitoring presence tidak dapat berjalan.");
            });

    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {});

    private final ActivityResultLauncher<String> audioPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) launchSpeechRecognizer();
                else showTextCaptureDialog();
            });

    private final ActivityResultLauncher<Intent> speechLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), this::handleSpeechResult);

    private final Runnable uiTicker = new Runnable() {
        @Override public void run() {
            updateUi();
            handler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ProductivityStore.ensureDay(this);
        bindViews();
        setupCategorySpinner();
        loadSettings();
        setupButtons();
        requestNeededPermissions();
        updateUi();
        handler.post(uiTicker);
    }

    private void bindViews() {
        tvCoach = findViewById(R.id.tvCoach);
        tvCurrentTask = findViewById(R.id.tvCurrentTask);
        tvMode = findViewById(R.id.tvMode);
        tvPresence = findViewById(R.id.tvPresence);
        tvToday = findViewById(R.id.tvToday);
        tvStats = findViewById(R.id.tvStats);
        tvSession = findViewById(R.id.tvSession);
        tvInbox = findViewById(R.id.tvInbox);
        tvStatus = findViewById(R.id.tvStatus);
        progressTarget = findViewById(R.id.progressTarget);

        etTask1 = findViewById(R.id.etTask1);
        etTask2 = findViewById(R.id.etTask2);
        etTask3 = findViewById(R.id.etTask3);

        spCategory = findViewById(R.id.spCategory);

        etBotToken = findViewById(R.id.etBotToken);
        etChatId = findViewById(R.id.etChatId);
        etInterval = findViewById(R.id.etInterval);
        etAwaySeconds = findViewById(R.id.etAwaySeconds);
        etAlertSeconds = findViewById(R.id.etAlertSeconds);
        etAutoBreakMinutes = findViewById(R.id.etAutoBreakMinutes);
        etTargetMinutes = findViewById(R.id.etTargetMinutes);
    }

    private void setupCategorySpinner() {
        String[] categories = {
                "SIMKAH / layanan nikah",
                "Surat & arsip",
                "Laporan",
                "Layanan / tamu",
                "Lainnya"
        };
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, categories);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spCategory.setAdapter(adapter);
    }

    private void setupButtons() {
        findViewById(R.id.btnTask1).setOnClickListener(v -> selectTask(etTask1.getText().toString()));
        findViewById(R.id.btnTask2).setOnClickListener(v -> selectTask(etTask2.getText().toString()));
        findViewById(R.id.btnTask3).setOnClickListener(v -> selectTask(etTask3.getText().toString()));

        findViewById(R.id.btnFocus).setOnClickListener(v -> startFocus());
        findViewById(R.id.btnBreak).setOnClickListener(v -> sendServiceCommand(FocusMonitorService.ACTION_BREAK));
        findViewById(R.id.btnDone).setOnClickListener(v -> sendServiceCommand(FocusMonitorService.ACTION_DONE));

        findViewById(R.id.btnDistraction).setOnClickListener(v -> showDistractionDialog());
        findViewById(R.id.btnStuck).setOnClickListener(v -> showRescueDialog());
        findViewById(R.id.btnCapture).setOnClickListener(v -> startCapture());
        findViewById(R.id.btnInbox).setOnClickListener(v -> showInbox());
        findViewById(R.id.btnSummary).setOnClickListener(v -> showSummary());
        findViewById(R.id.btnChatGPT).setOnClickListener(v -> shareToChatGPT());

        findViewById(R.id.btnSaveSettings).setOnClickListener(v -> saveSettingsFromUi());
        findViewById(R.id.btnTestTelegram).setOnClickListener(v -> {
            saveSettingsFromUi();
            sendServiceCommand(FocusMonitorService.ACTION_TEST_TELEGRAM);
        });
    }

    private void selectTask(String text) {
        String task = text == null ? "" : text.trim();
        if (task.isEmpty()) {
            Toast.makeText(this, "Isi nama tugas dulu.", Toast.LENGTH_SHORT).show();
            return;
        }
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        p.edit()
                .putString("current_task", task)
                .putString("current_category", String.valueOf(spCategory.getSelectedItem()))
                .apply();
        ProductivityStore.addEvent(this, "TASK_SELECTED", task, String.valueOf(spCategory.getSelectedItem()), 0L, "");
        tvStatus.setText("Tugas aktif dipilih. Tekan FOCUS untuk mulai.");
        updateUi();
    }

    private void startFocus() {
        if (!hasCameraPermission()) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
            return;
        }
        saveSettingsFromUi();
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        String task = p.getString("current_task", "").trim();
        if (task.isEmpty()) {
            Toast.makeText(this, "Pilih salah satu Today's 3 terlebih dahulu.", Toast.LENGTH_LONG).show();
            return;
        }
        p.edit().putString("current_category", String.valueOf(spCategory.getSelectedItem())).apply();
        sendServiceCommand(FocusMonitorService.ACTION_FOCUS);
        tvStatus.setText("Focus Service aktif. Setelah service muncul, layar boleh dimatikan.");
    }

    private void showDistractionDialog() {
        String[] options = {"HP/Sosmed", "Orang/Tamu", "Pikiran/Ide lain", "Pekerjaan mendadak"};
        new AlertDialog.Builder(this)
                .setTitle("Apa yang menarik perhatianmu?")
                .setItems(options, (d, which) -> {
                    ProductivityStore.incrementDistraction(this, options[which]);
                    Toast.makeText(this, "Dicatat. Kembali ke satu langkah berikutnya.", Toast.LENGTH_SHORT).show();
                    updateUi();
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void showRescueDialog() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        String task = p.getString("current_task", "tugas ini");
        String category = p.getString("current_category", "Lainnya");
        String steps = buildMicroSteps(category, task);
        ProductivityStore.addEvent(this, "STUCK", task, category, 0L, "ADHD Rescue dibuka");

        new AlertDialog.Builder(this)
                .setTitle("ADHD Rescue")
                .setMessage("Jangan selesaikan semuanya. Cukup langkah pertama.\n\n" + steps)
                .setPositiveButton("MULAI SEKARANG", (d, w) -> startFocus())
                .setNeutralButton("CAPTURE IDE", (d, w) -> startCapture())
                .setNegativeButton("Tutup", null)
                .show();
    }

    private String buildMicroSteps(String category, String task) {
        String c = category == null ? "" : category.toLowerCase(Locale.ROOT);
        if (c.contains("simkah") || c.contains("nikah")) {
            return "Tugas: " + task + "\n\n1. Buka satu berkas saja.\n2. Input satu bagian wajib.\n3. Simpan.\n4. Baru ambil berkas berikutnya.";
        }
        if (c.contains("surat") || c.contains("arsip")) {
            return "Tugas: " + task + "\n\n1. Buka satu dokumen.\n2. Isi bagian wajib.\n3. Simpan / arsipkan.\n4. Tandai selesai.";
        }
        if (c.contains("laporan")) {
            return "Tugas: " + task + "\n\n1. Buka file laporan.\n2. Isi SATU bagian.\n3. Cek angka bagian itu.\n4. Simpan. Jangan pikirkan bagian lain dulu.";
        }
        if (c.contains("layanan") || c.contains("tamu")) {
            return "Tugas: " + task + "\n\n1. Catat kebutuhan orang tersebut.\n2. Kerjakan satu langkah paling jelas.\n3. Catat follow-up bila belum selesai.";
        }
        return "Tugas: " + task + "\n\n1. Buka alat/file yang dibutuhkan.\n2. Kerjakan hanya 5 menit.\n3. Selesaikan satu unit kecil.\n4. Evaluasi setelahnya, bukan sekarang.";
    }

    private void startCapture() {
        if (Build.VERSION.SDK_INT >= 23 && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
        } else {
            launchSpeechRecognizer();
        }
    }

    private void launchSpeechRecognizer() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID");
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Ucapkan ide / tugas yang ingin disimpan, lalu kembali fokus.");
        try {
            speechLauncher.launch(intent);
        } catch (Exception e) {
            showTextCaptureDialog();
        }
    }

    private void handleSpeechResult(ActivityResult result) {
        if (result.getResultCode() == RESULT_OK && result.getData() != null) {
            ArrayList<String> results = result.getData().getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty()) {
                ProductivityStore.addInbox(this, results.get(0));
                ProductivityStore.addEvent(this, "CAPTURE", getCurrentTask(), getCurrentCategory(), 0L, results.get(0));
                Toast.makeText(this, "Ide disimpan. Kembali ke tugas aktif.", Toast.LENGTH_SHORT).show();
                updateUi();
                return;
            }
        }
        showTextCaptureDialog();
    }

    private void showTextCaptureDialog() {
        EditText input = new EditText(this);
        input.setHint("Tulis ide singkat...");
        new AlertDialog.Builder(this)
                .setTitle("Capture ide")
                .setView(input)
                .setPositiveButton("Simpan", (d, w) -> {
                    String note = input.getText().toString().trim();
                    ProductivityStore.addInbox(this, note);
                    ProductivityStore.addEvent(this, "CAPTURE", getCurrentTask(), getCurrentCategory(), 0L, note);
                    updateUi();
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void showInbox() {
        JSONArray arr = ProductivityStore.getInbox(this);
        if (arr.length() == 0) {
            new AlertDialog.Builder(this).setTitle("Inbox ide").setMessage("Belum ada ide yang ditangkap.").setPositiveButton("OK", null).show();
            return;
        }
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            s.append(i + 1).append(". ").append(o.optString("note")).append("\n   ").append(o.optString("time")).append("\n\n");
        }
        new AlertDialog.Builder(this)
                .setTitle("Inbox ide (" + arr.length() + ")")
                .setMessage(s.toString())
                .setPositiveButton("Tutup", null)
                .setNegativeButton("Hapus semua", (d, w) -> {
                    ProductivityStore.clearInbox(this);
                    updateUi();
                })
                .show();
    }

    private void showSummary() {
        String summary = ProductivityStore.buildSummary(this, true);
        new AlertDialog.Builder(this)
                .setTitle("Rekap hari ini")
                .setMessage(summary)
                .setPositiveButton("Tutup", null)
                .setNeutralButton("Salin", (d, w) -> copyText(summary))
                .show();
    }

    private void shareToChatGPT() {
        String summary = ProductivityStore.buildSummary(this, true);
        String prompt = "Analisis rekap produktivitas saya berikut sebagai coach ADHD yang praktis. " +
                "Cari pola distraksi, waktu fokus, dan bottleneck. Jangan memberi terlalu banyak saran. " +
                "Berikan: (1) 3 temuan utama, (2) 1 perubahan sistem untuk besok, (3) urutan 3 prioritas besok, " +
                "(4) satu aturan rescue ketika saya mulai terdistraksi. Gunakan bahasa Indonesia yang ringkas.\n\n" + summary;

        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, prompt);
        try {
            startActivity(Intent.createChooser(send, "Kirim rekap ke ChatGPT"));
        } catch (Exception e) {
            copyText(prompt);
            Toast.makeText(this, "Prompt disalin. Tempel ke ChatGPT.", Toast.LENGTH_LONG).show();
        }
    }

    private void copyText(String text) {
        ClipboardManager cm = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("AI Productivity", text));
        Toast.makeText(this, "Disalin ke clipboard.", Toast.LENGTH_SHORT).show();
    }

    private void requestNeededPermissions() {
        if (!hasCameraPermission()) cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void sendServiceCommand(String action) {
        Intent intent = new Intent(this, FocusMonitorService.class);
        intent.setAction(action);
        ContextCompat.startForegroundService(this, intent);
    }

    private void saveSettingsFromUi() {
        long intervalSec = clamp(parseLong(etInterval.getText().toString(), 5L), 2L, 60L);
        long awaySec = clamp(parseLong(etAwaySeconds.getText().toString(), 20L), Math.max(10L, intervalSec * 2L), 600L);
        long alertSec = clamp(parseLong(etAlertSeconds.getText().toString(), 120L), awaySec, 3600L);
        long autoBreakMin = clamp(parseLong(etAutoBreakMinutes.getText().toString(), 10L), 2L, 120L);
        long targetMin = clamp(parseLong(etTargetMinutes.getText().toString(), 240L), 15L, 1440L);

        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("bot_token", etBotToken.getText().toString().trim())
                .putString("chat_id", etChatId.getText().toString().trim())
                .putLong("interval_sec", intervalSec)
                .putLong("away_sec", awaySec)
                .putLong("alert_sec", alertSec)
                .putLong("auto_break_min", autoBreakMin)
                .putLong("target_min", targetMin)
                .putString("task1", etTask1.getText().toString().trim())
                .putString("task2", etTask2.getText().toString().trim())
                .putString("task3", etTask3.getText().toString().trim())
                .putInt("category_index", spCategory.getSelectedItemPosition())
                .apply();

        etInterval.setText(String.valueOf(intervalSec));
        etAwaySeconds.setText(String.valueOf(awaySec));
        etAlertSeconds.setText(String.valueOf(alertSec));
        etAutoBreakMinutes.setText(String.valueOf(autoBreakMin));
        etTargetMinutes.setText(String.valueOf(targetMin));
        Toast.makeText(this, "Pengaturan dan Today's 3 tersimpan.", Toast.LENGTH_SHORT).show();
    }

    private void loadSettings() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        etBotToken.setText(p.getString("bot_token", ""));
        etChatId.setText(p.getString("chat_id", ""));
        etInterval.setText(String.valueOf(p.getLong("interval_sec", 5L)));
        etAwaySeconds.setText(String.valueOf(p.getLong("away_sec", 20L)));
        etAlertSeconds.setText(String.valueOf(p.getLong("alert_sec", 120L)));
        etAutoBreakMinutes.setText(String.valueOf(p.getLong("auto_break_min", 10L)));
        etTargetMinutes.setText(String.valueOf(p.getLong("target_min", 240L)));
        etTask1.setText(p.getString("task1", ""));
        etTask2.setText(p.getString("task2", ""));
        etTask3.setText(p.getString("task3", ""));
        spCategory.setSelection(Math.min(4, Math.max(0, p.getInt("category_index", 0))));
    }

    private void updateUi() {
        ProductivityStore.ensureDay(this);
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        String mode = p.getString("runtime_mode", "IDLE");
        boolean present = p.getBoolean("runtime_presence", false);
        boolean confirmedAway = p.getBoolean("runtime_confirmed_away", false);
        boolean serviceRunning = p.getBoolean("runtime_service_running", false);
        String currentTask = p.getString("current_task", "").trim();
        String currentCategory = p.getString("current_category", "");

        long dayFocus = p.getLong("day_focus", 0L);
        long sessionFocus = p.getLong("runtime_session_focus", 0L);
        long segmentStart = p.getLong("runtime_segment_started", 0L);
        if ("FOCUS".equals(mode) && !confirmedAway && segmentStart > 0L) {
            long extra = Math.max(0L, (SystemClock.elapsedRealtime() - segmentStart) / 1000L);
            dayFocus += extra;
            sessionFocus += extra;
        }

        int dayAway = p.getInt("day_away", 0);
        int daySessions = p.getInt("day_sessions", 0);
        int sessionAway = p.getInt("runtime_session_away", 0);
        long best = p.getLong("longest_session", 0L);
        long targetSec = Math.max(60L, p.getLong("target_min", 240L) * 60L);
        long awayElapsed = p.getLong("runtime_away_elapsed", 0L);

        String shownMode = "FOCUS".equals(mode) && confirmedAway ? "AWAY" : mode;
        tvMode.setText("Mode: " + shownMode + (serviceRunning ? "  •  Service ON" : ""));
        tvPresence.setText("Presence: " + (present ? "ADA" : "TIDAK ADA") + (confirmedAway ? "  • away " + awayElapsed + " dtk" : ""));
        tvCurrentTask.setText(currentTask.isEmpty() ? "Belum ada tugas aktif" : currentTask + (currentCategory.isEmpty() ? "" : "\n" + currentCategory));
        tvToday.setText("Hari ini: " + ProductivityStore.formatDuration(dayFocus) + " / " + ProductivityStore.formatDuration(targetSec));
        tvStats.setText("Sesi: " + daySessions + "  |  Away: " + dayAway + "  |  Best: " + ProductivityStore.formatDuration(best));
        tvSession.setText("Sesi sekarang: " + ProductivityStore.formatDuration(sessionFocus) + "  |  Away: " + sessionAway);
        int progress = (int)Math.round(Math.min(100.0, dayFocus * 100.0 / targetSec) * 10.0);
        progressTarget.setProgress(progress);
        tvInbox.setText("Inbox ide: " + ProductivityStore.getInbox(this).length());
        tvCoach.setText("Coach: " + buildCoach(p, mode, confirmedAway, dayFocus, sessionFocus, targetSec));
    }

    private String buildCoach(SharedPreferences p, String mode, boolean away, long dayFocus, long sessionFocus, long targetSec) {
        String task = p.getString("current_task", "").trim();
        if (task.isEmpty()) return "Tulis maksimal 3 target, lalu pilih SATU yang paling penting. Jangan merencanakan terlalu lama.";
        if ("FOCUS".equals(mode) && away) return "Kembali ke meja. Jangan mengejar waktu yang hilang—cukup lanjutkan satu langkah berikutnya pada “" + task + "”.";
        if (dayFocus >= targetSec) return "Target fokus harian sudah tercapai. Prioritaskan pekerjaan wajib dan tutup loop yang masih terbuka.";
        int social = p.getInt("d_social", 0);
        if (social >= 3) return "HP/Sosmed sudah mengganggu " + social + " kali. Untuk sesi berikutnya, jauhkan HP utama selama 20 menit.";
        int awayCount = p.getInt("day_away", 0);
        if (awayCount >= 5) return "Anda sering meninggalkan meja hari ini. Gunakan sprint 15 menit: selesaikan satu unit kerja sebelum berdiri.";
        int sessions = p.getInt("day_sessions", 0);
        long best = p.getLong("longest_session", 0L);
        if (sessions >= 3 && best < 15 * 60L) return "Sesi hari ini pendek-pendek. Turunkan target menjadi sprint 10 menit, bukan memaksa 25–50 menit.";
        if ("FOCUS".equals(mode) && sessionFocus >= 45 * 60L) return "Anda sudah fokus cukup lama. Selesaikan unit sekarang lalu ambil break 5–10 menit secara sengaja.";
        return "Jangan lihat semua pekerjaan. Kerjakan satu langkah berikutnya pada “" + task + "”.";
    }

    private String getCurrentTask() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString("current_task", "");
    }

    private String getCurrentCategory() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString("current_category", "");
    }

    private static long parseLong(String value, long fallback) {
        try { return Long.parseLong(value.trim()); }
        catch (Exception ignored) { return fallback; }
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(uiTicker);
        super.onDestroy();
    }
}
