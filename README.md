# FAIt_mobile_test

Aplikasi ini berfungsi untuk melakukan testing performa model YOLO yang telah dilatih untuk aplikasi FAIt (Food AI-based Tracker). Dengan aplikasi ini, Anda dapat menguji kecepatan inferensi dan ukuran model pada perangkat Android.

## Prasyarat

Sebelum memulai, pastikan Anda telah memenuhi beberapa persyaratan berikut:

*   **Akun Weights & Biases (W&B):** Anda memerlukan akun W&B untuk mengunduh model dan metadata yang telah dilatih.
*   **Model yang Sudah Dilatih dan Dikonversi:** Pastikan Anda memiliki model yang telah selesai dilatih dan dikonversi ke format yang sesuai untuk mobile (TFLite).

## Langkah-langkah Build dan Testing

Ikuti langkah-langkah di bawah ini untuk menyiapkan dan menjalankan aplikasi testing:

1.  **Instal Android Studio:** Jika Anda belum memilikinya, unduh dan instal Android Studio dari situs resminya.
2.  **Clone Repository:** Salin (clone) repository `FAIt_mobile_test` dari GitHub ke direktori lokal Anda.
3.  **Buka Project di Android Studio:** Buka folder `FAIt_mobile_test` yang telah Anda clone sebagai project baru di Android Studio.
4.  **Sync Gradle:** Setelah project terbuka, sinkronkan Gradle agar semua dependensi dapat terunduh dan terpasang dengan benar. Biasanya Android Studio akan melakukannya secara otomatis, atau Anda bisa memicunya secara manual melalui menu `File > Sync Project with Gradle Files`.
5.  **Unduh Model dan Metadata:**
    *   Akses bagian *Artifacts* di project Weights & Biases Anda.
    *   Unduh file model mobile (misalnya, dalam format `.tflite`) beserta file metadatanya. File metadata ini berisi informasi penting seperti label yang digunakan oleh model.
6.  **Tempatkan File ke Folder Aset:**
    *   Di dalam struktur project Android Studio Anda, navigasikan ke direktori `app/src/main/assets`.
    *   Tempatkan file model mobile dan file metadata yang telah Anda unduh ke dalam folder `assets` ini.
8.  **Jalankan Build:** Bangun (build) project Anda di Android Studio. Anda bisa melakukannya dengan mengklik tombol "Run" (ikon segitiga hijau).
9.  **Testing:** Setelah aplikasi berhasil di-build dan dijalankan di emulator atau perangkat fisik Anda, aplikasi siap digunakan untuk testing.

## Catatan Penting

*   **Dataset yang Sama:** Pastikan model yang Anda uji dilatih menggunakan dataset yang sama dengan yang digunakan untuk menghasilkan file metadata.
*   **Performa:** Hasil pengujian performa (kecepatan inferensi dan ukuran model) akan bervariasi tergantung pada spesifikasi perangkat Android yang Anda gunakan.

Selamat mencoba!

