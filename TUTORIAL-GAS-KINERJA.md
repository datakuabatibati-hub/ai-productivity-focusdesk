# Tutorial Google Sheets + Google Apps Script untuk Capture Kerjaan

## 1. Buat Google Sheet
Buat spreadsheet baru, misalnya `Laporan Kinerja Harian`.

Ambil ID spreadsheet dari URL:

`https://docs.google.com/spreadsheets/d/ID_SPREADSHEET/edit`

Bagian `ID_SPREADSHEET` adalah nilai yang diperlukan.

## 2. Buka Apps Script
Di spreadsheet: **Extensions / Ekstensi → Apps Script**.

Hapus kode bawaan lalu copy seluruh isi file `GAS-Kinerja.gs` dari project ini.

Ganti:

```javascript
const SHEET_ID = 'GANTI_DENGAN_ID_GOOGLE_SHEET_ANDA';
const API_KEY  = 'GANTI_DENGAN_KUNCI_RAHASIA_ANDA';
```

Contoh API key bebas tetapi buat cukup panjang, misalnya:

`focusdesk-2026-kinerja-X8p2m91`

## 3. Jalankan setup sekali
Di Apps Script pilih fungsi `setupKinerjaSheet` lalu **Run**.

Berikan izin Google jika diminta.

Script membuat dua sheet:
- `Log Kinerja`
- `Ringkasan Harian`

## 4. Deploy sebagai Web App
Pilih **Deploy → New deployment → Web app**.

Set:
- Execute as: **Me**
- Who has access: **Anyone** (jika kebijakan akun mengizinkan)

Deploy lalu salin URL yang berakhir `/exec`.

Contoh:
`https://script.google.com/macros/s/...../exec`

## 5. Masukkan ke aplikasi
Buka AI Productivity → bagian **GOOGLE SHEETS / GAS**.

Isi:
- Web App URL `/exec`
- API key yang sama dengan `GAS-Kinerja.gs`

Tekan **TES KONEKSI GOOGLE SHEET**.

Jika berhasil akan muncul `Koneksi GAS berhasil`.

## 6. Cara memakai
Tekan **KERJAAN → GOOGLE SHEET** lalu ucapkan misalnya:

- `Membuat surat rekomendasi nikah`
- `Mengarsipkan akta nikah bulan September`
- `Membuat laporan wakaf`
- `Melayani konsultasi calon pengantin`

Aplikasi menampilkan hasil speech untuk dikonfirmasi. Tekan **KIRIM**.

## 7. Jika internet mati
Catatan disimpan dalam antrean lokal.

Saat aplikasi dibuka lagi, aplikasi mencoba sync otomatis. Anda juga bisa tekan **SYNC ULANG CATATAN TERTUNDA**.

Setiap capture memiliki Event ID unik. GAS memeriksa Event ID sebelum menambah baris sehingga retry tidak membuat data dobel.

## Keamanan
- Jangan membagikan API key GAS.
- Jangan menaruh API key di screenshot publik.
- Jika key bocor, ganti `API_KEY` di Apps Script dan di aplikasi.
