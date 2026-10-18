# COMSCA Cuti – Aplikasi Android (hanya menu Pengajuan Cuti)

Pembungkus (WebView) untuk menu **Pengajuan Cuti**. Menu lain (Durasi, Callback, Absensi-Lembur) tidak disertakan.
Isi menu Pengajuan Cuti sama persis dengan aplikasi COMSCA versi 1.5 (termasuk kunci tanggal mundur).

## Cara mendapatkan APK (GitHub Actions)
1. Buat repository GitHub baru (terpisah dari repository COMSCA), unggah seluruh isi folder ini termasuk `.github`.
2. Buka tab **Actions** > **Build APK** (jalan otomatis saat push, atau *Run workflow*).
3. Setelah selesai, unduh artifact **COMSCA-Cuti-APK** > `app-debug.apk`, lalu pasang di HP.

## Beranda
- Satu kartu menu: Pengajuan Cuti.
- Teks di bawah kartu: "Aplikasi Monitoring dan Pengajuan Cuti" / "Command Center - SCADA" / "Papua dan Papua Barat".
- Bottom bar: Beranda, Cuti, Tema. Welcome screen "The Legacy of" ±3 detik.

## Catatan
- Package aplikasi `id.cc.comsca.cuti` (berbeda dari COMSCA), jadi kedua aplikasi bisa terpasang bersamaan.
- Nama aplikasi: `app/src/main/res/values/strings.xml`. Teks beranda: cari `class="about"` di `app/src/main/assets/index.html`.
- Durasi welcome screen: cari `3000-(Date.now()` di `index.html` (3000 = 3 detik).
- Butuh internet (Google Sheet dan pustaka CDN).
