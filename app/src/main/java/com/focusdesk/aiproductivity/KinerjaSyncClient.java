package com.focusdesk.aiproductivity;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

public final class KinerjaSyncClient {

    public interface Callback {
        void onResult(boolean success, String message);
    }

    private static final String KEY_PENDING = "pending_kinerja";

    private KinerjaSyncClient() {}

    public static void sendVoiceKinerja(Context context, String rawDescription, Callback callback) {
        final Context app = context.getApplicationContext();
        final String description = cleanVoiceCommand(rawDescription);

        if (description.isEmpty()) {
            deliver(callback, false, "Teks kinerja kosong.");
            return;
        }

        SharedPreferences p = ProductivityStore.prefs(app);
        String currentTask = p.getString("current_task", "").trim();
        String selectedCategory = p.getString("current_category", "").trim();
        String mode = p.getString("runtime_mode", "IDLE");
        String category = inferCategory(description, selectedCategory);
        long dayFocus = currentDayFocusSeconds(p);
        long sessionFocus = currentSessionFocusSeconds(p);
        int distractions = p.getInt("d_social", 0) + p.getInt("d_people", 0) +
                p.getInt("d_thought", 0) + p.getInt("d_urgent", 0);

        try {
            JSONObject json = new JSONObject();
            json.put("event_id", UUID.randomUUID().toString());
            json.put("api_key", p.getString("gas_api_key", "").trim());
            json.put("type", "kinerja");
            json.put("tanggal", new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()));
            json.put("jam", new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()));
            json.put("kinerja", description);
            json.put("kategori", category);
            json.put("tugas_aktif", currentTask);
            json.put("mode", mode);
            json.put("focus_hari_detik", dayFocus);
            json.put("focus_hari", ProductivityStore.formatDuration(dayFocus));
            json.put("focus_sesi_detik", sessionFocus);
            json.put("focus_sesi", ProductivityStore.formatDuration(sessionFocus));
            json.put("away_hari", p.getInt("day_away", 0));
            json.put("distraksi_hari", distractions);
            json.put("source", "VOICE");
            json.put("app", "AI Productivity Focus Desk V3");

            // Catat lokal dulu. Walaupun internet mati, jejak pekerjaan tetap ada.
            ProductivityStore.addEvent(app, "KINERJA", currentTask, category, 0L, description);
            p.edit()
                    .putString("last_kinerja_text", description)
                    .putString("last_kinerja_category", category)
                    .putString("last_kinerja_status", "MENUNGGU SYNC")
                    .putInt("day_kinerja_count", p.getInt("day_kinerja_count", 0) + 1)
                    .apply();

            sendOrQueue(app, json, callback);
        } catch (Exception e) {
            deliver(callback, false, "Gagal menyiapkan laporan: " + e.getMessage());
        }
    }

    private static void sendOrQueue(Context app, JSONObject json, Callback callback) {
        SharedPreferences p = ProductivityStore.prefs(app);
        String gasUrl = p.getString("gas_url", "").trim();
        if (gasUrl.isEmpty()) {
            enqueue(app, json);
            p.edit().putString("last_kinerja_status", "TERTUNDA").apply();
            deliver(callback, false, "GAS URL belum diisi. Laporan masuk antrean lokal dan tidak hilang.");
            return;
        }

        new Thread(() -> {
            PostResult r = postJsonBlocking(gasUrl, json);
            if (r.success) {
                p.edit().putString("last_kinerja_status", "TERKIRIM").apply();
                deliver(callback, true, "Kinerja tersimpan ke Google Sheet.");
            } else {
                enqueue(app, json);
                p.edit().putString("last_kinerja_status", "TERTUNDA").apply();
                deliver(callback, false, r.message + " Laporan masuk antrean lokal.");
            }
        }, "KinerjaGasSend").start();
    }

    public static int getPendingCount(Context context) {
        return readPending(ProductivityStore.prefs(context)).length();
    }

    public static void retryPending(Context context, Callback callback) {
        final Context app = context.getApplicationContext();
        final SharedPreferences p = ProductivityStore.prefs(app);
        final String gasUrl = p.getString("gas_url", "").trim();
        final JSONArray original = readPending(p);

        if (original.length() == 0) {
            if (callback != null) deliver(callback, true, "Tidak ada laporan tertunda.");
            return;
        }
        if (gasUrl.isEmpty()) {
            if (callback != null) deliver(callback, false, "GAS URL belum diisi. " + original.length() + " laporan masih aman di antrean.");
            return;
        }

        new Thread(() -> {
            JSONArray remaining = new JSONArray();
            int sent = 0;
            for (int i = 0; i < original.length(); i++) {
                JSONObject item = original.optJSONObject(i);
                if (item == null) continue;
                try {
                    // API key mengikuti setting terbaru, agar antrean lama tetap bisa dikirim setelah key diperbaiki.
                    item.put("api_key", p.getString("gas_api_key", "").trim());
                } catch (Exception ignored) {}
                PostResult r = postJsonBlocking(gasUrl, item);
                if (r.success) sent++;
                else remaining.put(item);
            }
            p.edit().putString(KEY_PENDING, remaining.toString()).apply();
            if (remaining.length() == 0) p.edit().putString("last_kinerja_status", "TERKIRIM").apply();
            String msg = "Sync selesai: " + sent + " terkirim, " + remaining.length() + " masih tertunda.";
            deliver(callback, remaining.length() == 0, msg);
        }, "KinerjaRetryQueue").start();
    }

    public static void testConnection(Context context, Callback callback) {
        Context app = context.getApplicationContext();
        SharedPreferences p = ProductivityStore.prefs(app);
        String gasUrl = p.getString("gas_url", "").trim();
        String apiKey = p.getString("gas_api_key", "").trim();
        if (gasUrl.isEmpty()) {
            deliver(callback, false, "GAS Web App URL belum diisi.");
            return;
        }
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("api_key", apiKey);
                json.put("type", "test");
                json.put("app", "AI Productivity Focus Desk V3");
                PostResult r = postJsonBlocking(gasUrl, json);
                deliver(callback, r.success, r.success ? "Koneksi GAS berhasil." : r.message);
            } catch (Exception e) {
                deliver(callback, false, "Tes GAS gagal: " + e.getMessage());
            }
        }, "KinerjaGasTest").start();
    }

    private static synchronized void enqueue(Context app, JSONObject json) {
        SharedPreferences p = ProductivityStore.prefs(app);
        JSONArray arr = readPending(p);
        String eventId = json.optString("event_id", "");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject existing = arr.optJSONObject(i);
            if (existing != null && eventId.equals(existing.optString("event_id", ""))) return;
        }
        arr.put(json);
        // Batasi antrean agar SharedPreferences tidak tumbuh tanpa batas.
        while (arr.length() > 200) arr.remove(0);
        p.edit().putString(KEY_PENDING, arr.toString()).apply();
    }

    private static JSONArray readPending(SharedPreferences p) {
        try {
            return new JSONArray(p.getString(KEY_PENDING, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static PostResult postJsonBlocking(String gasUrl, JSONObject json) {
        HttpURLConnection conn = null;
        try {
            byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
            URL url = new URL(gasUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setDoOutput(true);
            // ContentService dapat merespons redirect sesudah doPost selesai.
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Accept", "application/json,text/plain,*/*");
            conn.setFixedLengthStreamingMode(body.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }
            int code = conn.getResponseCode();
            boolean ok = code >= 200 && code < 400;
            return new PostResult(ok, ok ? "OK" : "GAS merespons HTTP " + code + ".");
        } catch (Exception e) {
            return new PostResult(false, "Gagal kirim ke GAS: " + e.getMessage() + ".");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static long currentDayFocusSeconds(SharedPreferences p) {
        long day = p.getLong("day_focus", 0L);
        long segmentStart = p.getLong("runtime_segment_started", 0L);
        boolean focused = "FOCUS".equals(p.getString("runtime_mode", "IDLE"));
        boolean away = p.getBoolean("runtime_confirmed_away", false);
        if (focused && !away && segmentStart > 0L) {
            day += Math.max(0L, (SystemClock.elapsedRealtime() - segmentStart) / 1000L);
        }
        return day;
    }

    private static long currentSessionFocusSeconds(SharedPreferences p) {
        long session = p.getLong("runtime_session_focus", 0L);
        long segmentStart = p.getLong("runtime_segment_started", 0L);
        boolean focused = "FOCUS".equals(p.getString("runtime_mode", "IDLE"));
        boolean away = p.getBoolean("runtime_confirmed_away", false);
        if (focused && !away && segmentStart > 0L) {
            session += Math.max(0L, (SystemClock.elapsedRealtime() - segmentStart) / 1000L);
        }
        return session;
    }

    private static String cleanVoiceCommand(String input) {
        if (input == null) return "";
        String s = input.trim();
        String lower = s.toLowerCase(Locale.ROOT);
        String[] prefixes = {
                "catat kinerja ", "catat pekerjaan ", "catat kerja ",
                "kinerja saya ", "saya sedang ", "saya sudah ",
                "saat ini saya sedang ", "sekarang saya sedang "
        };
        for (String prefix : prefixes) {
            if (lower.startsWith(prefix)) return s.substring(prefix.length()).trim();
        }
        return s;
    }

    private static String inferCategory(String description, String fallback) {
        String d = description.toLowerCase(Locale.ROOT);
        if (containsAny(d, "simkah", "nikah", "catin", "buku nikah", "akta nikah", "pengumuman kehendak")) {
            return "SIMKAH / layanan nikah";
        }
        if (containsAny(d, "surat", "rekomendasi", "legalisasi", "arsip", "disposisi", "scan", "digitalisasi")) {
            return "Surat & arsip";
        }
        if (containsAny(d, "laporan", "rekap", "statistik", "lkh", "lkb", "wakaf", "pn bp", "pnbp")) {
            return "Laporan";
        }
        if (containsAny(d, "melayani", "pelayanan", "tamu", "konsultasi", "pemohon")) {
            return "Layanan / tamu";
        }
        return fallback == null || fallback.trim().isEmpty() ? "Lainnya" : fallback;
    }

    private static boolean containsAny(String text, String... words) {
        for (String w : words) if (text.contains(w)) return true;
        return false;
    }

    private static void deliver(Callback callback, boolean success, String message) {
        if (callback == null) return;
        new Handler(Looper.getMainLooper()).post(() -> callback.onResult(success, message));
    }

    private static final class PostResult {
        final boolean success;
        final String message;
        PostResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }
}
