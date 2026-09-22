# AI Productivity Focus Desk V1

Aplikasi Android ringan untuk membantu kerja fokus dan rekap produktivitas, khususnya saat mudah terdistraksi.

## Fitur inti
- Today's 3: batasi prioritas harian menjadi maksimal 3.
- Pilih satu tugas aktif; layar utama selalu mengingatkan apa yang sedang dikerjakan.
- FOCUS / BREAK / DONE.
- CameraX + ML Kit mendeteksi keberadaan wajah secara lokal.
- Foreground Camera Service + partial wake lock: ditujukan untuk tetap memonitor saat layar dimatikan.
- Waktu AWAY tidak dihitung sebagai fokus efektif.
- Smart escalation: away dikonfirmasi lebih awal, Garmin/Telegram baru mengingatkan setelah ambang tertentu, lalu auto-break bila terlalu lama.
- DISTRAKSI: catat HP/Sosmed, Orang/Tamu, Pikiran/Ide, atau pekerjaan mendadak.
- STUCK / ADHD Rescue: memecah tugas menjadi langkah terkecil.
- CAPTURE: voice capture / input teks untuk menyimpan ide tanpa berpindah tugas.
- Inbox ide.
- Rekap fokus, sesi, away, waktu away, longest focus, distraksi, dan waktu per kategori.
- Coach lokal berbasis pola hari itu, tanpa API.
- ANALISIS DI CHATGPT: membuat prompt + rekap dan membagikannya lewat Android Share. Pilih aplikasi ChatGPT jika tersedia; tidak membutuhkan OpenAI API key.
- Telegram dapat diteruskan oleh iPhone ke Garmin melalui Smart Notifications.

## Default
- Scan kamera: 5 detik
- Konfirmasi AWAY: 20 detik
- Alert Garmin pertama: 120 detik
- Alert kedua: sekitar 5 menit
- Auto-break: 10 menit
- Target fokus: 240 menit / 4 jam

## Data
Semua statistik, task, log dan inbox disimpan lokal menggunakan SharedPreferences. Frame kamera tidak disimpan dan tidak dikirim ke server. Telegram hanya menerima notifikasi yang dibuat aplikasi.

## Build
Tidak membutuhkan Android Studio. Gunakan GitHub Actions. Baca `CLOUD-BUILD-GUIDE.md`.
