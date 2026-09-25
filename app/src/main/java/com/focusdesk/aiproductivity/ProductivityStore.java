package com.focusdesk.aiproductivity;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class ProductivityStore {
    public static final String PREFS = "focus_desk";

    private ProductivityStore() {}

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    public static String nowText() {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
    }

    public static void ensureDay(Context c) {
        SharedPreferences p = prefs(c);
        String today = today();
        String old = p.getString("stats_date", "");
        boolean serviceRunning = p.getBoolean("runtime_service_running", false);
        if (old.isEmpty()) {
            p.edit().putString("stats_date", today).apply();
            return;
        }
        if (!old.equals(today) && !serviceRunning) {
            p.edit()
                    .putString("stats_date", today)
                    .putLong("day_focus", 0L)
                    .putLong("day_away_time", 0L)
                    .putInt("day_away", 0)
                    .putInt("day_sessions", 0)
                    .putLong("longest_session", 0L)
                    .putInt("d_social", 0)
                    .putInt("d_people", 0)
                    .putInt("d_thought", 0)
                    .putInt("d_urgent", 0)
                    .putLong("focus_simkah", 0L)
                    .putLong("focus_surat", 0L)
                    .putLong("focus_laporan", 0L)
                    .putLong("focus_layanan", 0L)
                    .putLong("focus_lain", 0L)
                    .putInt("day_kinerja_count", 0)
                    .putString("day_events", "[]")
                    .apply();
        }
    }

    public static void addEvent(Context c, String type, String task, String category, long durationSec, String note) {
        SharedPreferences p = prefs(c);
        JSONArray arr = readArray(p.getString("day_events", "[]"));
        JSONObject o = new JSONObject();
        try {
            o.put("time", nowText());
            o.put("type", safe(type));
            o.put("task", safe(task));
            o.put("category", safe(category));
            o.put("duration_sec", durationSec);
            o.put("note", safe(note));
            arr.put(o);
            trimArray(arr, 200);
            p.edit().putString("day_events", arr.toString()).apply();
        } catch (JSONException ignored) {}
    }

    public static JSONArray getEvents(Context c) {
        return readArray(prefs(c).getString("day_events", "[]"));
    }

    public static void addInbox(Context c, String note) {
        if (note == null || note.trim().isEmpty()) return;
        SharedPreferences p = prefs(c);
        JSONArray arr = readArray(p.getString("inbox_notes", "[]"));
        JSONObject o = new JSONObject();
        try {
            o.put("time", new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date()));
            o.put("note", note.trim());
            arr.put(o);
            trimArray(arr, 100);
            p.edit().putString("inbox_notes", arr.toString()).apply();
        } catch (JSONException ignored) {}
    }

    public static JSONArray getInbox(Context c) {
        return readArray(prefs(c).getString("inbox_notes", "[]"));
    }

    public static void clearInbox(Context c) {
        prefs(c).edit().putString("inbox_notes", "[]").apply();
    }

    public static void incrementDistraction(Context c, String kind) {
        SharedPreferences p = prefs(c);
        String key;
        if ("HP/Sosmed".equals(kind)) key = "d_social";
        else if ("Orang/Tamu".equals(kind)) key = "d_people";
        else if ("Pikiran/Ide lain".equals(kind)) key = "d_thought";
        else key = "d_urgent";
        p.edit().putInt(key, p.getInt(key, 0) + 1).apply();
        addEvent(c, "DISTRACTION", p.getString("current_task", ""), p.getString("current_category", ""), 0L, kind);
    }

    public static void addCategoryFocus(SharedPreferences p, String category, long sec) {
        String key = categoryKey(category);
        p.edit().putLong(key, p.getLong(key, 0L) + Math.max(0L, sec)).apply();
    }

    public static String categoryKey(String category) {
        if (category == null) return "focus_lain";
        String c = category.toLowerCase(Locale.ROOT);
        if (c.contains("simkah")) return "focus_simkah";
        if (c.contains("surat") || c.contains("arsip")) return "focus_surat";
        if (c.contains("laporan")) return "focus_laporan";
        if (c.contains("layanan") || c.contains("tamu")) return "focus_layanan";
        return "focus_lain";
    }

    public static String formatDuration(long sec) {
        long h = sec / 3600L;
        long m = (sec % 3600L) / 60L;
        long s = sec % 60L;
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s);
    }

    public static String buildSummary(Context c, boolean includeEvents) {
        ensureDay(c);
        SharedPreferences p = prefs(c);
        long focus = p.getLong("day_focus", 0L);
        long segmentStart = p.getLong("runtime_segment_started", 0L);
        boolean focused = "FOCUS".equals(p.getString("runtime_mode", "IDLE"));
        boolean away = p.getBoolean("runtime_confirmed_away", false);
        if (focused && !away && segmentStart > 0L) {
            long extra = Math.max(0L, (android.os.SystemClock.elapsedRealtime() - segmentStart) / 1000L);
            focus += extra;
        }

        long target = Math.max(60L, p.getLong("target_min", 240L) * 60L);
        long awayTime = p.getLong("day_away_time", 0L);
        int sessions = p.getInt("day_sessions", 0);
        int awayCount = p.getInt("day_away", 0);
        long best = p.getLong("longest_session", 0L);
        int social = p.getInt("d_social", 0);
        int people = p.getInt("d_people", 0);
        int thought = p.getInt("d_thought", 0);
        int urgent = p.getInt("d_urgent", 0);
        int pct = (int)Math.round(Math.min(999.0, focus * 100.0 / target));

        StringBuilder s = new StringBuilder();
        s.append("AI PRODUCTIVITY — ").append(today()).append("\n\n");
        s.append("Fokus efektif : ").append(formatDuration(focus)).append("\n");
        s.append("Target         : ").append(formatDuration(target)).append("\n");
        s.append("Pencapaian     : ").append(pct).append("%\n");
        s.append("Sesi           : ").append(sessions).append("\n");
        s.append("Away           : ").append(awayCount).append("x\n");
        s.append("Waktu away     : ").append(formatDuration(awayTime)).append("\n");
        s.append("Longest focus  : ").append(formatDuration(best)).append("\n");
        s.append("Capture kerja  : ").append(p.getInt("day_kinerja_count", 0)).append("\n");
        s.append("Sync tertunda  : ").append(KinerjaSyncClient.getPendingCount(c)).append("\n\n");

        s.append("Distraksi\n");
        s.append("- HP/Sosmed      : ").append(social).append("\n");
        s.append("- Orang/Tamu     : ").append(people).append("\n");
        s.append("- Pikiran/Ide    : ").append(thought).append("\n");
        s.append("- Mendesak lain  : ").append(urgent).append("\n\n");

        s.append("Waktu per kategori\n");
        s.append("- SIMKAH/Nikah   : ").append(formatDuration(p.getLong("focus_simkah", 0L))).append("\n");
        s.append("- Surat/Arsip    : ").append(formatDuration(p.getLong("focus_surat", 0L))).append("\n");
        s.append("- Laporan        : ").append(formatDuration(p.getLong("focus_laporan", 0L))).append("\n");
        s.append("- Layanan/Tamu   : ").append(formatDuration(p.getLong("focus_layanan", 0L))).append("\n");
        s.append("- Lainnya        : ").append(formatDuration(p.getLong("focus_lain", 0L))).append("\n");

        if (includeEvents) {
            JSONArray events = getEvents(c);
            s.append("\nLog ringkas\n");
            for (int i = 0; i < events.length(); i++) {
                JSONObject o = events.optJSONObject(i);
                if (o == null) continue;
                String type = o.optString("type");
                String time = o.optString("time");
                String task = o.optString("task");
                String note = o.optString("note");
                long dur = o.optLong("duration_sec", 0L);
                s.append(time).append(" ").append(type);
                if (!task.isEmpty()) s.append(" — ").append(task);
                if (dur > 0) s.append(" (").append(formatDuration(dur)).append(")");
                if (!note.isEmpty()) s.append(" — ").append(note);
                s.append("\n");
            }
        }
        return s.toString();
    }

    private static JSONArray readArray(String raw) {
        try { return new JSONArray(raw == null ? "[]" : raw); }
        catch (Exception ignored) { return new JSONArray(); }
    }

    private static void trimArray(JSONArray arr, int max) throws JSONException {
        while (arr.length() > max) arr.remove(0);
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
