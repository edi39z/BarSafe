import cv2
import os

# Konfigurasi
NAME = "Edi_Suranta" # Folder dataset kamu
SAVE_PATH = f"dataset/{NAME}"
MAX_IMAGES = 50

if not os.path.exists(SAVE_PATH):
    os.makedirs(SAVE_PATH)

cap = cv2.VideoCapture(0)
count = 0

print("Mulai mengambil foto. Gerakkan wajahmu perlahan (miring, tunduk, senyum)...")

while count < MAX_IMAGES:
    ret, frame = cap.read()
    if not ret: break

    # Simpan gambar
    img_name = f"{SAVE_PATH}/face_{count}.jpg"
    cv2.imwrite(img_name, frame)
    
    # Tampilkan progres di layar
    cv2.putText(frame, f"Captured: {count}/{MAX_IMAGES}", (10, 30), 
                cv2.FONT_HERSHEY_SIMPLEX, 1, (0, 255, 0), 2)
    cv2.imshow("Koleksi Wajah - Skripsi", frame)
    
    count += 1
    if cv2.waitKey(100) & 0xFF == ord('q'): # Jeda 100ms antar foto
        break

cap.release()
cv2.destroyAllWindows()
print(f"Selesai! {count} foto tersimpan di {SAVE_PATH}")