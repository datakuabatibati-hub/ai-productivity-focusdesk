# Update repository lama ke V3

Jika repository AI Productivity Anda sudah ada dan sudah bisa build, cara termudah:

1. Download `AIProductivityVoiceKinerjaV3_PATCH.zip`.
2. Extract.
3. Copy seluruh isi folder patch ke folder Git lokal project Anda.
4. Pilih **Replace files in destination**.
5. Buka Git Bash di folder project.
6. Jalankan:

```bash
git status
git add .
git commit -m "Upgrade AI Productivity V3 voice kinerja"
git push
```

7. Buka GitHub > Actions.
8. Workflow `Build AI Productivity APK` akan berjalan otomatis.
9. Setelah hijau, download Artifact `AI-Productivity-FocusDesk-APK`.

## Sesudah install V3
- Buka aplikasi.
- Isi GAS URL `/exec` dan API key.
- Tekan `TES KONEKSI GOOGLE SHEET`.
- Gunakan `IDE — PARKIR DULU` untuk pikiran/ide.
- Gunakan `KERJAAN → GOOGLE SHEET` untuk laporan kinerja harian.
