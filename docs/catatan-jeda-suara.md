# Catatan jeda petikan

Acuan suara yang benar adalah mesin di `app/src/main/cpp/engine/AudioEngine.cpp` seperti commit `6c9a675`, sebelum percobaan jeda. Itu yang dipasang di Play internal **1.0.0 kode 6**.

Yang masih kurang: jeda kecil antara petikan dan suara ampli. Jangan sentuh jalur suara lagi sebelum jeda itu diukur di ponsel yang suaranya sudah kembali normal.

## Yang bikin suara rusak

Jangan diulang.

- Membuang sampel mikrofon di setiap buffer. Cadangan normal ikut terbuang, baca berikutnya kurang, sisanya diisi senyap, nada patah-patah. Terjadi di kode 4.
- Preset mikrofon `VoicePerformance`. Jalur itu untuk suara percakapan dan mengubah warna gitar. Tetap `VoiceRecognition`.
- Output `Usage::Media`. Jalur yang bersih adalah `Usage::Game`, tanpa `ContentType::Music`.
- Callback mengikuti burst mentah ponsel, bukan pangkat dua. Konvolver (zita) butuh kuantum pangkat dua. Tanpa itu suara kacau. Terjadi di kode 5. Kode asli membuka ulang stream kalau `framesPerBurst` bukan pangkat dua.
- Antrean output dipaksa satu burst. Oboe sendiri memakai dua burst. Satu burst putus-putus, dan menaikkannya setelah xrun tidak mengembalikan nada semula.

## Yang boleh tetap

Layanan latar `EffectKeepAliveService`: notifikasi, wake lock, tipe `microphone|mediaPlayback`. Ini yang menahan efek saat layar mati, layar kunci, dan aplikasi di belakang. Bukan bagian dari rantai sampel.

## Cara pendekkan jeda

Penyebab jeda di jalur asli: mikrofon mengantre sampel lama, lalu tiap callback hanya membaca `numFrames`, jadi selisih itu tidak pernah habis.

Mikrofon dibuka dan dijalankan lebih dulu, baru stream keluaran. Selama jeda itu mikrofon sudah merekam. Saat callback pertama membaca, antrean itu sudah berisi, dan karena setiap pembacaan mengambil sampel paling lama, kepala antrean itu dibawa terus sampai mesin dimatikan.

### Sudah dipasang, nyala secara bawaan

`AudioEngine::serviceInputStartup()`, menyalin urutan resmi Oboe di `FullDuplexStream.h`. Selama urutan ini jalan, `inputBuffer_` diisi senyap, jadi rantai efek memproses diam dan tidak ada bunyi aneh yang sampai ke ampli:

1. **Drain**, 16 callback: baca mikrofon berulang sampai antrean benar-benar kosong.
2. **Cushion**, 1 callback: tidak membaca sama sekali, biarkan antrean terisi lagi seperlunya.
3. **Settle**, 24 callback: baca normal sampai masukan dan keluaran seimbang.
4. **Live**: rantai efek mulai terdengar.

Totalnya sekitar 40 callback, kira-kira 0,2 detik saat mesin start, jadi tidak terdengar.

Bedanya dengan percobaan lama: cadangan tidak lagi ditebak dengan angka mati. Antrean dikosongkan lalu perangkat sendiri yang menentukan titik seimbangnya, dan kalau masih kurang, `noteInputUnderrun()` menambah satu callback cadangan tiap empat kali tersendat sampai maksimal empat, lalu mengulang tahap Cushion. Jadi kalau perangkatnya butuh cadangan lebih, dia menaikkannya sendiri tanpa perlu diutak-atik.

Saklarnya: **Pengaturan audio → Pangkas jeda petikan**, bawaannya nyala sejak perubahan ini. Tersimpan di `audio_settings/shortenInputDelay`, dikirim ke mesin lewat `nativeSetInputShedEnabled` sebelum start, dan mengubahnya memicu restart mesin. Ponsel yang sudah pernah mematikan saklarnya tetap mati, karena nilai itu tersimpan. Mematikannya mengembalikan jalur suara kode 6 dan 8.

### Batas yang tidak bisa dihilangkan

Jeda tidak mungkin nol. Yang tersisa setelah antrean mikrofon beres: buffer keluaran dua burst, cadangan mikrofon satu callback, delay bawaan plugin kabinet atau IR, dan konversi ADC/DAC perangkat yang di luar kendali aplikasi. Di Android itu biasanya belasan sampai puluhan milidetik.

Pengaturan audio menampilkan "Antrean mikrofon sekarang" dan "Suara tersendat". Kalau antrean kecil tapi jeda masih terasa, sumbernya plugin, bukan buffer.

### Kalau masih terasa

Jangan ganti preset, usage, atau ukuran callback pangkat dua. Jangan turunkan buffer output di bawah dua burst. Di bawah satu buffer (beberapa milidetik) suara pecah. Delay bawaan plugin kabinet tidak hilang dengan mengubah buffer: coba lepas kabinet atau IR dulu untuk memisahkan sumber jeda.
