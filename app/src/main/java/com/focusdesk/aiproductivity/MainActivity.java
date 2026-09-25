package com.focusdesk.aiproductivity;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.speech.RecognizerIntent;
import android.widget.ArrayAdapter;
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
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS = ProductivityStore.PREFS;

    private TextView tvCoach, tvCurrentTask, tvMode, tvPresence, tvToday, tvStats, tvSession;
    private TextView tvInbox, tvStatus, tvKinerjaStatus, tvSyncQueue;
    private ProgressBar progressTarget;
    private EditText etTask1, etTask2, etTask3;
    private EditText etBotToken, etChatId, etInterval, etAwaySeconds, etAlertSeconds;
    private EditText etAutoBreakMinutes, etTargetMinutes, etGasUrl, etGasApiKey;
    private Spinner spCategory;

    private enum SpeechMode { CAPTURE_IDE, CAPTURE_KINERJA }
    private SpeechMode pendingSpeechMode = SpeechMode.CAPTURE_IDE;
    private boolean recoveryDialogVisible = false;

    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

    private final ActivityResultLauncher<String> cameraPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted ->
                    tvStatus.setText(granted
                            ? "Izin kamera OK."
                            : "Izin kamera ditolak. Monitoring presence tidak dapat berjalan."));

    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {});

    private final ActivityResultLauncher<String> audioPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) launchSpeechRecognizer();
                else Toast.makeText(this, "Izin mikrofon diperlukan untuk capture suara.", Toast.LENGTH_LONG).show();
            });

    private final ActivityResultLauncher<Intent> speechLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), this::handleSpeechResult);

    private final Runnable uiTicker = new Runnable() {
        @Override public void run() {
            updateUi();
            maybeShowRecoveryPrompt();
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

        // Coba kirim ulang catatan kinerja yang tertunda tanpa mengganggu user.
        KinerjaSyncClient.retryPending(this, null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        KinerjaSyncClient.retryPending(this, null);
        handler.postDelayed(this::maybeShowRecoveryPrompt, 350L);
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
        tvKinerjaStatus = findViewById(R.id.tvKinerjaStatus);
        tvSyncQueue = findViewById(R.id.tvSyncQueue);
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
        etGasUrl = findViewById(R.id.etGasUrl);
        etGasApiKey = findViewById(R.id.etGasApiKey);
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

        findViewById(R.id.btnFocus).setOnClickListener(v -> {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putLong("sprint_target_min", 0L).apply();
            startFocus();
        });
        findViewById(R.id.btnBreak).setOnClickListener(v -> sendServiceCommand(FocusMonitorService.ACTION_BREAK));
        findViewById(R.id.btnDone).setOnClickListener(v -> sendServiceCommand(FocusMonitorService.ACTION_DONE));

        findViewById(R.id.btnSprint10).setOnClickListener(v -> startSprint(10));
        findViewById(R.id.btnSprint25).setOnClickListener(v -> startSprint(25));
        findViewById(R.id.btnSprint45).setOnClickListener(v -> startSprint(45));

        findViewById(R.id.btnDistraction).setOnClickListener(v -> showDistractionDialog());
        findViewById(R.id.btnStuck).setOnClickListener(v -> showRescueDialog());
        findViewById(R.id.btnCaptureIdea).setOnClickListener(v -> startVoiceCapture(SpeechMode.CAPTURE_IDE));
        findViewById(R.id.btnVoiceKinerja).setOnClickListener(v -> startVoiceCapture(SpeechMode.CAPTURE_KINERJA));
        findViewById(R.id.btnInbox).setOnClickListener(v -> showInbox());
        findViewById(R.id.btnSummary).setOnClickListener(v -> showSummary());
        findViewById(R.id.btnChatGPT).setOnClickListener(v -> shareToChatGPT());

        findViewById(R.id.btnRetrySync).setOnClickListener(v -> {
            tvKinerjaStatus.setText("Mencoba sinkronisasi ulang…");
            KinerjaSyncClient.retryPending(this, (success, message) -> {
                tvKinerjaStatus.setText((success ? "✅ " : "⚠️ ") + message);
                updateUi();
            });
        });

        findViewById(R.id.btnSaveSettings).setOnClickListener(v -> saveSettingsFromUi());
        findViewById(R.id.btnTestGas).setOnClickListener(v -> {
            saveSettingsFromUi();
            tvKinerjaStatus.setText("Menguji koneksi GAS…");
            KinerjaSyncClient.testConnection(this, (success, message) -> {
                tvKinerjaStatus.setText((success ? "✅ " : "⚠️ ") + message);
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            });
        });
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
        tvStatus.setText("Tugas aktif dipilih. Kerjakan hanya tugas ini sampai DONE/BREAK.");
        updateUi();
    }

    private void startSprint(int minutes) {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        if ("FOCUS".equals(p.getString("runtime_mode", "IDLE"))) {
            Toast.makeText(this, "Selesaikan / BREAK sesi aktif dulu sebelum memilih sprint baru.", Toast.LENGTH_LONG).show();
            return;
        }
        p.edit()
                .putLong("sprint_target_min", minutes)
                .putBoolean("runtime_sprint_alerted", false)
                .apply();
        Toast.makeText(this, "Sprint " + minutes + " menit dipilih.", Toast.LENGTH_SHORT).show();
        startFocus();
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
        tvStatus.setText("Focus Service aktif. Setelah notifikasi service muncul, layar boleh dimatikan.");
    }

    private void showDistractionDialog() {
        String[] options = {"HP/Sosmed", "Orang/Tamu", "Pikiran/Ide lain", "Pekerjaan mendadak"};
        new AlertDialog.Builder(this)
                .setTitle("Apa yang menarik perhatianmu?")
                .setItems(options, (d, which) -> {
                    ProductivityStore.incrementDistraction(this, options[which]);
                    Toast.makeText(this, "Dicatat. Kembali ke SATU langkah berikutnya.", Toast.LENGTH_SHORT).show();
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
                .setPositiveButton("MULAI 10 MENIT", (d, w) -> startSprint(10))
                .setNeutralButton("PARKIR IDE", (d, w) -> startVoiceCapture(SpeechMode.CAPTURE_IDE))
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
        return "Tugas: " + task + "\n\n1. Buka alat/file yang dibutuhkan.\n2. Kerjakan hanya 5–10 menit.\n3. Selesaikan satu unit kecil.\n4. Evaluasi setelahnya, bukan sekarang.";
    }

    private void startVoiceCapture(SpeechMode mode) {
        pendingSpeechMode = mode;
        if (Build.VERSION.SDK_INT >= 23 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
        } else {
            launchSpeechRecognizer();
        }
    }

    private void launchSpeechRecognizer() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID");
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "id-ID");
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT,
                pendingSpeechMode == SpeechMode.CAPTURE_KINERJA
                        ? "Ucapkan pekerjaan yang baru/sedang dikerjakan. Contoh: Membuat surat rekomendasi nikah."
                        : "Ucapkan ide yang ingin diparkir. Setelah tersimpan, kembali fokus.");
        try {
            speechLauncher.launch(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Pengenal suara tidak tersedia di HP ini.", Toast.LENGTH_LONG).show();
        }
    }

    private void handleSpeechResult(ActivityResult result) {
        if (result.getResultCode() == RESULT_OK && result.getData() != null) {
            ArrayList<String> results = result.getData().getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty()) {
                confirmVoiceResult(results.get(0).trim());
                return;
            }
        }
        Toast.makeText(this, "Suara belum terbaca. Tekan tombol capture dan coba lagi.", Toast.LENGTH_SHORT).show();
    }

    private void confirmVoiceResult(String spoken) {
        if (spoken.isEmpty()) return;
        boolean work = pendingSpeechMode == SpeechMode.CAPTURE_KINERJA;
        new AlertDialog.Builder(this)
                .setTitle(work ? "Kirim laporan kerja?" : "Simpan ide?")
                .setMessage("Hasil suara:\n\n“" + spoken + "”")
                .setPositiveButton(work ? "KIRIM" : "SIMPAN", (d, w) -> {
                    if (work) saveVoiceWork(spoken);
                    else saveVoiceIdea(spoken);
                })
                .setNeutralButton("ULANGI", (d, w) -> handler.postDelayed(this::launchSpeechRecognizer, 250L))
                .setNegativeButton("Batal", null)
                .show();
    }

    private void saveVoiceIdea(String spoken) {
        ProductivityStore.addInbox(this, spoken);
        ProductivityStore.addEvent(this, "CAPTURE_IDEA", getCurrentTask(), getCurrentCategory(), 0L, spoken);
        Toast.makeText(this, "Ide diparkir. Kembali ke tugas aktif.", Toast.LENGTH_SHORT).show();
        updateUi();
    }

    private void saveVoiceWork(String spoken) {
        saveSettingsFromUi();
        tvKinerjaStatus.setText("Mengirim laporan kerja…\n" + spoken);
        KinerjaSyncClient.sendVoiceKinerja(this, spoken, (success, message) -> {
            SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
            String clean = p.getString("last_kinerja_text", spoken);
            String category = p.getString("last_kinerja_category", "");
            tvKinerjaStatus.setText((success ? "✅ " : "⚠️ ") + message +
                    "\n" + clean + (category.isEmpty() ? "" : "\nKategori: " + category));
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            updateUi();
        });
    }

    private void maybeShowRecoveryPrompt() {
        if (recoveryDialogVisible) return;
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!p.getBoolean("recovery_pending", false)) return;

        p.edit().putBoolean("recovery_pending", false).apply();
        recoveryDialogVisible = true;
        String task = p.getString("current_task", "tugas aktif");
        new AlertDialog.Builder(this)
                .setTitle("Kembali ke jalur")
                .setMessage("Anda sudah kembali. Jangan evaluasi semuanya sekarang.\n\nLanjutkan satu langkah pada:\n“" + task + "”")
                .setPositiveButton("LANJUT", (d, w) -> recoveryDialogVisible = false)
                .setNeutralButton("STUCK", (d, w) -> {
                    recoveryDialogVisible = false;
                    showRescueDialog();
                })
                .setNegativeButton("BREAK", (d, w) -> {
                    recoveryDialogVisible = false;
                    sendServiceCommand(FocusMonitorService.ACTION_BREAK);
                })
                .setOnDismissListener(d -> recoveryDialogVisible = false)
                .show();
    }

    private void showInbox() {
        JSONArray arr = ProductivityStore.getInbox(this);
        if (arr.length() == 0) {
            new AlertDialog.Builder(this).setTitle("Inbox ide").setMessage("Belum ada ide yang diparkir.").setPositiveButton("OK", null).show();
            return;
        }
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            s.append(i + 1).append(". ").append(o.optString("note"))
                    .append("\n   ").append(o.optString("time")).append("\n\n");
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
                "Cari pola distraksi, waktu fokus, kinerja yang tercatat, dan bottleneck. Jangan memberi terlalu banyak saran. " +
                "Berikan: (1) 3 temuan utama, (2) 1 perubahan sistem untuk besok, (3) urutan 3 prioritas besok, " +
                "(4) satu aturan rescue ketika saya mulai terdistraksi, (5) apakah catatan kinerja harian sudah cukup representatif. " +
                "Gunakan bahasa Indonesia yang ringkas.\n\n" + summary;

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
        if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
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
                .putString("gas_url", etGasUrl.getText().toString().trim())
                .putString("gas_api_key", etGasApiKey.getText().toString().trim())
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
        Toast.makeText(this, "Pengaturan tersimpan.", Toast.LENGTH_SHORT).show();
    }

    private void loadSettings() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        etBotToken.setText(p.getString("bot_token", ""));
        etChatId.setText(p.getString("chat_id", ""));
        etGasUrl.setText(p.getString("gas_url", ""));
        etGasApiKey.setText(p.getString("gas_api_key", ""));
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
        long sprintMin = p.getLong("sprint_target_min", 0L);

        String shownMode = "FOCUS".equals(mode) && confirmedAway ? "AWAY" : mode;
        String sprintLabel = sprintMin > 0 && "FOCUS".equals(mode) ? " • Sprint " + sprintMin + "m" : "";
        tvMode.setText("Mode: " + shownMode + sprintLabel + (serviceRunning ? " • Service ON" : ""));
        tvPresence.setText("Presence: " + (present ? "ADA" : "TIDAK ADA") +
                (confirmedAway ? " • away " + awayElapsed + " dtk" : ""));
        tvCurrentTask.setText(currentTask.isEmpty() ? "Belum ada tugas aktif" : currentTask +
                (currentCategory.isEmpty() ? "" : "\n" + currentCategory));
        tvToday.setText("Hari ini: " + ProductivityStore.formatDuration(dayFocus) + " / " + ProductivityStore.formatDuration(targetSec));
        tvStats.setText("Sesi: " + daySessions + "  |  Away: " + dayAway + "  |  Best: " + ProductivityStore.formatDuration(best));
        tvSession.setText("Sesi sekarang: " + ProductivityStore.formatDuration(sessionFocus) + "  |  Away: " + sessionAway);
        int progress = (int)Math.round(Math.min(100.0, dayFocus * 100.0 / targetSec) * 10.0);
        progressTarget.setProgress(progress);
        tvInbox.setText("Inbox ide: " + ProductivityStore.getInbox(this).length());

        int pending = KinerjaSyncClient.getPendingCount(this);
        int workCount = p.getInt("day_kinerja_count", 0);
        tvSyncQueue.setText("Laporan kerja hari ini: " + workCount + " • Sinkronisasi tertunda: " + pending);
        tvCoach.setText("Coach: " + buildCoach(p, mode, confirmedAway, dayFocus, sessionFocus, targetSec, pending));
    }

    private String buildCoach(SharedPreferences p, String mode, boolean away, long dayFocus, long sessionFocus, long targetSec, int pending) {
        String task = p.getString("current_task", "").trim();
        if (pending > 0) return pending + " laporan kerja belum tersinkron. Tidak hilang—lanjut kerja, sync saat koneksi stabil.";
        if (task.isEmpty()) return "Isi maksimal 3 target, lalu pilih SATU. Hindari merencanakan lebih lama daripada mengerjakan.";
        if ("FOCUS".equals(mode) && away) return "Kembali ke meja. Jangan mengejar waktu yang hilang—cukup lanjut satu langkah pada “" + task + "”.";
        if (dayFocus >= targetSec) return "Target fokus tercapai. Prioritaskan menutup pekerjaan wajib dan catat kinerja yang sudah selesai.";
        int social = p.getInt("d_social", 0);
        if (social >= 3) return "HP/Sosmed sudah mengganggu " + social + " kali. Jalankan sprint 10 menit dengan HP utama dijauhkan.";
        int awayCount = p.getInt("day_away", 0);
        if (awayCount >= 5) return "Anda sering meninggalkan meja hari ini. Pilih sprint 10 menit dan selesaikan satu unit sebelum berdiri.";
        if ("FOCUS".equals(mode) && sessionFocus >= 45 * 60L) return "Fokus sudah panjang. Tutup unit kerja sekarang, Capture Kerjaan, lalu BREAK 5–10 menit.";
        return "Satu tugas, satu langkah. Ide lain → Capture Ide. Pekerjaan selesai → Capture Kerjaan.";
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
