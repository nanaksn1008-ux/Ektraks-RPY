# RenPy Tools (Android)

Aplikasi Android untuk mengerjakan file Ren'Py langsung di HP.

## Fitur
- **Ekstrak RPA** – RPA-2.0, 3.0, 3.2 → folder pilihan (path berbahaya seperti `../` dilewati).
- **Buat RPA** – kemas satu folder (termasuk subfolder) menjadi `.rpa` (RPA-3.0).
- **Editor RPY** – buat/buka/edit/simpan `.rpy`, dengan tombol snippet (label, dialog, menu, scene, dst).
- **RPYC → RPY** – dekompilasi best-effort, satu file atau satu folder penuh.
- **Ganti background** – 5 preset gradien, acak, atau gambar dari galeri (tombol "Background" di kanan atas).
- Ikon adaptive launcher (kotak arsip + panah), mendukung themed icon Android 13+.

## Cara build
**Android Studio:** File → Open folder ini → tunggu sync → Build → Build APK(s).
APK ada di `app/build/outputs/apk/debug/app-debug.apk`.

**GitHub Actions (tanpa PC):** upload folder ini ke repo GitHub, buka tab *Actions* →
*Build APK* → *Run workflow*. APK diunduh dari bagian *Artifacts*.

Syarat: Android 8.0 (API 26) ke atas. Tidak memerlukan izin storage (memakai Storage Access Framework).

## Batasan
- RPA tidak mengompres isi file; yang dikompres hanya indeksnya (sama seperti Ren'Py asli).
- RPYC → RPY adalah rekonstruksi, bukan salinan persis skrip asli: komentar, format, dan node
  tertentu (screen, transform, ATL, translate) belum didukung dan ditulis sebagai komentar `# [belum didukung: ...]`.
  Untuk hasil paling lengkap pada game besar, pakai `unrpyc` di PC.
- Pembuatan `.rpyc` dari `.rpy` tidak ada: itu harus dilakukan mesin Ren'Py sendiri (otomatis saat game dijalankan).
- Gunakan hanya pada game milik sendiri atau yang izin modifikasinya jelas.
