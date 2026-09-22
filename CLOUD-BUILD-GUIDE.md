# Build APK tanpa Android Studio

## 1. Buat repository GitHub
1. Login ke github.com.
2. New repository.
3. Nama misalnya: `ai-productivity-focusdesk`.
4. Private disarankan.
5. Create repository.

## 2. Upload source
1. Ekstrak ZIP project.
2. Di GitHub: Add file -> Upload files.
3. Upload seluruh ISI folder project, bukan folder ZIP sebagai satu file.
4. Di root repository harus terlihat: `.github`, `app`, `build.gradle`, `settings.gradle`, `README.md`.
5. Commit changes.

## 3. GitHub membuat APK
1. Buka tab Actions.
2. Pilih `Build AI Productivity APK`.
3. Jika belum berjalan, tekan `Run workflow`.
4. Tunggu sampai status hijau.
5. Buka run tersebut dan scroll ke Artifacts.
6. Download `AI-Productivity-FocusDesk-APK`.
7. Ekstrak artifact; di dalamnya ada `AI-Productivity-FocusDesk.apk`.

## 4. Install
1. Pindahkan APK ke Android lama.
2. Izinkan Install unknown apps untuk file manager/browser yang dipakai.
3. Install APK.
4. Buka aplikasi dan izinkan Camera, Notifications, dan Microphone (microphone hanya untuk Voice Capture).

## 5. Setup pertama
1. Isi Bot Token Telegram dan Chat ID bila ingin Garmin alert.
2. Tekan `TES GARMIN / TELEGRAM`.
3. Isi Today’s 3.
4. Pilih salah satu tugas melalui `KERJAKAN TUGAS 1/2/3`.
5. Pilih kategori.
6. Tekan FOCUS.
7. Setelah foreground service aktif, layar boleh dimatikan.

## 6. Agar Android tidak mematikan service
Buka Settings -> Apps -> AI Productivity -> Battery dan pilih `Unrestricted`, `No restrictions`, atau `Don't optimize` bila tersedia. Nama menu berbeda antar merek Android.

## Alur ADHD yang disarankan
- Pagi: isi maksimal 3 tugas.
- Pilih hanya 1 tugas aktif.
- FOCUS.
- Ada ide lain -> CAPTURE, jangan pindah tugas.
- Terdistraksi -> DISTRAKSI, lalu kembali ke task aktif.
- Tidak tahu harus mulai dari mana -> STUCK.
- Istirahat sengaja -> BREAK.
- Tugas/sesi selesai -> DONE.
- Akhir hari -> LIHAT REKAP, lalu ANALISIS DI CHATGPT.
