# Build Fix

Perubahan utama:
- compileSdk 34 -> 35
- Android Gradle Plugin 8.5.2 -> 8.7.3
- Gradle 8.7 -> 8.9
- GitHub runner menggunakan Android SDK 35
- setup-java v5 / checkout v5
- menghapus input `cache-provider` yang sudah tidak didukung
- menambahkan `--stacktrace` agar error berikutnya lebih jelas

Setelah mengganti file di repository, commit + push lalu buka Actions dan jalankan ulang workflow.
