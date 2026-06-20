INSTRUKSI PENTING — MODEL TENSORFLOW LITE
==========================================

File ini menandai direktori assets yang diperlukan untuk face recognition.

LANGKAH YANG DIPERLUKAN:
1. Download model MobileFaceNet TFLite dari:
   https://github.com/sirius-ai/MobileFaceNet_TF

   Atau dari HuggingFace / sumber lain yang menyediakan:
   - Input: [1, 112, 112, 3] float32 (normalized to [-1, 1])
   - Output: [1, 128] float32 (face embedding vector)

2. Rename file menjadi: mobilefacenet.tflite

3. Letakkan file tersebut DI FOLDER INI:
   app/src/main/assets/mobilefacenet.tflite

4. Ukuran file yang diharapkan: ~3-5 MB

TANPA FILE INI:
   - App akan tetap berjalan
   - Halaman FaceUnlockScreen akan menampilkan pesan "Model Tidak Ditemukan"
   - Fitur daftar wajah dan buka brankas via wajah tidak akan berfungsi
   - Semua fitur lain (inventaris, logs, dashboard) tetap berjalan normal
