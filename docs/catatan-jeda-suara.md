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

## Cara pendekkan jeda nanti

Penyebab jeda di jalur asli: mikrofon mengantre sampel lama, lalu tiap callback hanya membaca `numFrames`, jadi selisih itu tidak pernah habis.

Yang aman dicoba, satu perubahan saja, lalu dengarkan sebelum menambah yang lain:

1. Buang antrean lama sekali di awal, hanya kalau antrean sudah empat callback atau lebih.
2. Sisakan dua callback. Jangan potong lagi selama main.
3. Jangan ganti preset, usage, atau ukuran callback pangkat dua.
4. Jangan turunkan buffer output di bawah dua burst.

Di bawah satu buffer (beberapa milidetik) suara pecah. Delay bawaan plugin kabinet tidak hilang dengan mengubah buffer.
