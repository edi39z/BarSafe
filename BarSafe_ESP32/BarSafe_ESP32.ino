// ================================================================
//  SMART SAFE — IoT Node v6.0 (TESTING METRICS + LATENCY MONITOR)
//  Peran: Pengawasan (IoT) & Eksekusi Perintah Buka (HP -> Firebase)
//  v6.0: Tambah pengukuran latency untuk keperluan pengujian skripsi
// ================================================================

#include "Arduino.h"
#include "soc/soc.h"
#include "soc/rtc_cntl_reg.h"

#define CAMERA_MODEL_AI_THINKER
#include "camera_pins.h"
#include "esp_camera.h"

#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <time.h>
#include <Firebase_ESP_Client.h>
#include <addons/TokenHelper.h>
#include <addons/RTDBHelper.h>

// ================================================================
//  KREDENSIAL WIFI & CLOUD
// ================================================================
const char *ssid = "Barnibar";
const char *password = "34213421";
#define DATABASE_URL "barfit-e25ef-default-rtdb.asia-southeast1.firebasedatabase.app"
#define API_KEY "AIzaSyCHsKXT7fW6fhkvq8wzFunlr6U5a1bTRCE"

#define USER_EMAIL    "ediginting3421@gmail.com"
#define USER_PASSWORD "dodifire39z"

const char *cloudName = "dmdmyrpve";
const char *uploadPreset = "Skripsi";

FirebaseData fbdo;
FirebaseAuth auth;
FirebaseConfig fbConfig;
bool firebaseReady = false;

// ================================================================
//  PINOUT HARDWARE
// ================================================================
#define FLASH_PIN  4    
#define PIR_PIN    13   
#define RELAY_PIN  14   
#define MC38_PIN   15   

#define RELAY_AKTIF LOW  
#define RELAY_MATI  HIGH 
#define FLASH_INTENSITY 255 

// ================================================================
//  VARIABEL TIMER & STATE
// ================================================================
bool isSolenoidTerbuka = false;

// Variabel PIR
unsigned long waktuMulaiGerak = 0;
unsigned long waktuTerakhirGerak = 0; 
bool sedangDeteksiPIR = false;
bool statusPirTerakhir = false;
unsigned long pirCooldownSampai = 0;  // Cooldown setelah foto/reset agar tidak re-trigger
unsigned long pirLastSampleTime = 0;  // Waktu terakhir sampling PIR
int pirHighCount = 0;                 // Counter berapa kali PIR HIGH berturut-turut
#define PIR_SAMPLE_INTERVAL 200       // Sample PIR setiap 200ms
#define PIR_REQUIRED_COUNT  15        // Harus HIGH 15x berturut = 3 detik kontinu
#define PIR_COOLDOWN_MS     5000      // Cooldown 5 detik setelah event

// Variabel Pintu MC-38
unsigned long waktuPintuDibuka = 0;
unsigned long waktuPintuMulaiRapat = 0;
bool sedangProsesRapat = false;
bool notifPintuTerbukaTerkirim = false;
unsigned long waktuPeringatanTerakhir = 0;
unsigned long waktuCekFirebase = 0;

bool lastMc38Read = LOW;
unsigned long lastMc38DebounceTime = 0;
bool pintuRapatStabil = true; 
bool notifIntrusiTerkirim = false; 

// ================================================================
//  VARIABEL PENGUJIAN / TESTING METRICS
// ================================================================
unsigned long t_commandReceived   = 0;  // Saat perintah UNLOCK diterima dari Firebase
unsigned long t_solenoidOpened    = 0;  // Saat relay solenoid diaktifkan
unsigned long t_ackSent           = 0;  // Saat ACK = true dikirim ke Firebase
unsigned long t_statusUpdated     = 0;  // Saat status UNLOCKED dikirim
unsigned long t_logPushed         = 0;  // Saat log unlock di-push ke Firebase
unsigned long latency_cmdToSolenoid  = 0;
unsigned long latency_solenoidToAck  = 0;
unsigned long latency_cmdToAck       = 0;
unsigned long latency_totalUnlock    = 0;
int totalUnlockCount = 0;  // Jumlah unlock berhasil (untuk statistik)


// ================================================================
//  FUNGSI BANTUAN FIREBASE
// ================================================================
void pushLog(String type, String level, String message, String imageUrl = "") {
  FirebaseJson logJson;
  logJson.set("type", type);
  logJson.set("level", level);
  logJson.set("personName", "Sistem ESP32");
  logJson.set("description", message);
  if (imageUrl != "") {
      logJson.set("imageUrl", imageUrl);
  }
  
  double timestamp_ms = (double)time(nullptr) * 1000.0;
  logJson.set("timestamp", timestamp_ms);
  
  if (Firebase.RTDB.pushJSON(&fbdo, "/logs", &logJson)) {
     Serial.println("[Firebase] Berhasil mengirim log: " + type);
  } else {
     Serial.printf("[Firebase] Gagal mengirim log: %s\n", fbdo.errorReason().c_str());
  }
}

// ================================================================
//  FUNGSI UPLOAD GAMBAR DARI PSRAM KE CLOUDINARY
// ================================================================
String uploadFotoBurst(uint8_t *imageBuf, size_t imageLen) {
  if (imageLen == 0) return "gagal_buffer";

  WiFiClientSecure client;
  client.setInsecure(); 
  if (!client.connect("api.cloudinary.com", 443)) {
    Serial.println("Koneksi ke Cloudinary Gagal!");
    return "gagal_konek";
  }

  String boundary = "Esp32Boundary";
  String head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"upload_preset\"\r\n\r\n" + String(uploadPreset) + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"anomali.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n";
  String tail = "\r\n--" + boundary + "--\r\n";
  uint32_t totalLen = head.length() + imageLen + tail.length();

  client.println("POST /v1_1/" + String(cloudName) + "/image/upload HTTP/1.1");
  client.println("Host: api.cloudinary.com");
  client.println("Content-Type: multipart/form-data; boundary=" + boundary);
  client.println("Content-Length: " + String(totalLen));
  client.println("Connection: close\r\n");
  
  client.print(head);
  for (size_t n = 0; n < imageLen; n += 1024) {
    client.write(imageBuf + n, ((n + 1024) < imageLen) ? 1024 : (imageLen - n));
  }
  client.print(tail);

  String secureUrl = "gagal";
  unsigned long timeout = millis();
  while (client.connected() && millis() - timeout < 10000) {
    if (client.available()) {
      String line = client.readStringUntil('\n');
      int idx = line.indexOf("\"secure_url\":\"");
      if (idx >= 0) {
        secureUrl = line.substring(idx + 14, line.indexOf("\"", idx + 14));
        secureUrl.replace("\\/", "/");
        break;
      }
    }
  }
  client.stop();
  return secureUrl;
}

// ================================================================
//  SETUP
// ================================================================
void setup() {
  WRITE_PERI_REG(RTC_CNTL_BROWN_OUT_REG, 0); 
  Serial.begin(115200);

  pinMode(PIR_PIN, INPUT);
  pinMode(MC38_PIN, INPUT_PULLUP); 
  pinMode(FLASH_PIN, OUTPUT);
  pinMode(RELAY_PIN, OUTPUT);
  
  digitalWrite(FLASH_PIN, LOW); 
  digitalWrite(RELAY_PIN, RELAY_MATI); 

  camera_config_t cam;
  cam.ledc_channel = LEDC_CHANNEL_0; cam.ledc_timer = LEDC_TIMER_0;
  cam.pin_d0 = Y2_GPIO_NUM; cam.pin_d1 = Y3_GPIO_NUM; cam.pin_d2 = Y4_GPIO_NUM;
  cam.pin_d3 = Y5_GPIO_NUM; cam.pin_d4 = Y6_GPIO_NUM; cam.pin_d5 = Y7_GPIO_NUM;
  cam.pin_d6 = Y8_GPIO_NUM; cam.pin_d7 = Y9_GPIO_NUM; cam.pin_xclk = XCLK_GPIO_NUM;
  cam.pin_pclk = PCLK_GPIO_NUM; cam.pin_vsync = VSYNC_GPIO_NUM; cam.pin_href = HREF_GPIO_NUM;
  cam.pin_sccb_sda = SIOD_GPIO_NUM; cam.pin_sccb_scl = SIOC_GPIO_NUM; cam.pin_pwdn = PWDN_GPIO_NUM;
  cam.pin_reset = RESET_GPIO_NUM; cam.xclk_freq_hz = 20000000; 
  cam.pixel_format = PIXFORMAT_JPEG;
  
  if(psramFound()){
    cam.frame_size = FRAMESIZE_VGA; 
    cam.jpeg_quality = 10; 
    cam.fb_count = 2;      
  } else {
    cam.frame_size = FRAMESIZE_SVGA;
    cam.jpeg_quality = 12;
    cam.fb_count = 1;
  }
  esp_camera_init(&cam);

  // --- PEMANASAN KAMERA & FLASH ---
  Serial.println("Memanaskan Kamera...");
  analogWrite(FLASH_PIN, 10); // Nyala redup
  delay(200);
  for(int i=0; i<3; i++){ camera_fb_t *fb = esp_camera_fb_get(); if(fb) esp_camera_fb_return(fb); }
  analogWrite(FLASH_PIN, 0);

  WiFi.begin(ssid, password);
  Serial.print("Menghubungkan ke WiFi");
  while (WiFi.status() != WL_CONNECTED) { delay(500); Serial.print("."); }
  Serial.println("\n✅ WiFi Terhubung!");
  
  configTime(7 * 3600, 0, "pool.ntp.org", "time.nist.gov");

  // --- PEMANASAN JALUR CLOUDINARY (SOLUSI FOTO PERTAMA GAGAL) ---
  Serial.print("Memanaskan Jalur Cloudinary... ");
  WiFiClientSecure dummyClient;
  dummyClient.setInsecure();
  if (dummyClient.connect("api.cloudinary.com", 443)) {
      Serial.println("OK!");
      dummyClient.stop();
  } else {
      Serial.println("Gagal, tapi akan dicoba lagi nanti.");
  }

  fbConfig.api_key = API_KEY; 
  fbConfig.database_url = DATABASE_URL;
  auth.user.email = USER_EMAIL; 
  auth.user.password = USER_PASSWORD;
  
  Firebase.begin(&fbConfig, &auth);
  Firebase.reconnectWiFi(true);

  Serial.print("Menghubungkan Firebase");
  while (!Firebase.ready()) { delay(500); Serial.print("."); }
  firebaseReady = true;
  Serial.println("\n✅ Firebase Ready!");
  
  Firebase.RTDB.setString(&fbdo, "/safe/command", "IDLE");
  Firebase.RTDB.setString(&fbdo, "/safe/status", "LOCKED");
  Firebase.RTDB.setBool(&fbdo, "/safe/ack", false);
  Firebase.RTDB.setBool(&fbdo, "/sensors/wifi", true);
  Firebase.RTDB.setBool(&fbdo, "/sensors/pir", false);
  Firebase.RTDB.setBool(&fbdo, "/sensors/doorClosed", true);
  
  Serial.println("");
  Serial.println("============================================================");
  Serial.println("  SISTEM IoT PENGAMAN BRANKAS SIAP — v6.0 TESTING MODE");
  Serial.println("============================================================");
  Serial.println("  [METRICS] Pengukuran latency aktif pada Serial Monitor");
  Serial.println("  Format: [T+xxxx ms] EVENT | Latency: xxx ms");
  Serial.println("------------------------------------------------------------");
  Serial.printf("  WiFi RSSI  : %d dBm\n", WiFi.RSSI());
  Serial.printf("  IP Address : %s\n", WiFi.localIP().toString().c_str());
  Serial.printf("  Threshold  : Threshold face recognition ada di Android\n");
  Serial.println("============================================================");
  Serial.println("");
}

// ================================================================
//  LOOP UTAMA
// ================================================================
// Variabel static untuk WiFi reconnect
static unsigned long wifiLostTime = 0;
static bool wifiWasConnected = true;
static unsigned long lastWifiLog = 0;

void loop() {
  if (WiFi.status() != WL_CONNECTED) {
    if (wifiWasConnected) {
      // WiFi baru saja terputus, catat waktunya
      wifiLostTime = millis();
      wifiWasConnected = false;
      Serial.println("⚠️ WiFi status bukan CONNECTED, menunggu auto-reconnect...");
    }

    // Log setiap 5 detik supaya tidak spam serial
    if (millis() - lastWifiLog >= 5000) {
      lastWifiLog = millis();
      Serial.printf("⏳ WiFi belum terhubung... (%lu detik)\n", (millis() - wifiLostTime) / 1000);
    }

    // Baru lakukan hard reconnect kalau sudah 10 detik tidak tersambung
    if (millis() - wifiLostTime >= 10000) {
      Serial.println("❌ WiFi gagal reconnect otomatis. Melakukan hard reconnect...");
      WiFi.disconnect(true);  // true = hapus stored config
      delay(1000);
      WiFi.begin(ssid, password);
      wifiLostTime = millis(); // Reset timer supaya tunggu 10 detik lagi
    }

    delay(500);
    return; 
  }

  // WiFi terhubung kembali
  if (!wifiWasConnected) {
    wifiWasConnected = true;
    Serial.printf("✅ WiFi Terhubung kembali! RSSI: %d dBm\n", WiFi.RSSI());
    Firebase.RTDB.setBool(&fbdo, "/sensors/wifi", true);
  }

  if (!firebaseReady) return;

  // ==========================================================
  // 1. LISTENER PERINTAH BUKA DARI HP 
  // ==========================================================
  static double lastProcessedTimestamp = 0;
  if (millis() - waktuCekFirebase > 1000) {
    waktuCekFirebase = millis();
    
    if (Firebase.RTDB.getString(&fbdo, "/safe/command")) {
      String command = fbdo.stringData();
      
      if (command == "UNLOCK") {
        double currentTimestamp = 0;
        if (Firebase.RTDB.getDouble(&fbdo, "/safe/timestamp")) {
            currentTimestamp = fbdo.doubleData();
        }
        
        if (currentTimestamp > lastProcessedTimestamp && !isSolenoidTerbuka) {
            lastProcessedTimestamp = currentTimestamp;
            t_commandReceived = millis();

            Serial.println("");
            Serial.println("╔═══════════════════════════════════════════════════════════╗");
            Serial.printf( "║  UNLOCK COMMAND DITERIMA — T+%lu ms                       \n", t_commandReceived);
            Serial.println("╠═══════════════════════════════════════════════════════════╣");

            // ── Relay & Solenoid Activation ──
            unsigned long t_beforeRelay = millis();
            digitalWrite(RELAY_PIN, RELAY_AKTIF); 
            t_solenoidOpened = millis();
            latency_cmdToSolenoid = t_solenoidOpened - t_commandReceived;
            unsigned long relayActivationLatency = t_solenoidOpened - t_beforeRelay;

            Serial.printf( "║  Relay/Solenoid AKTIF         | %lu ms                    \n", relayActivationLatency);

            isSolenoidTerbuka = true;
            waktuPintuDibuka = millis();     
            notifPintuTerbukaTerkirim = false; 
            waktuPeringatanTerakhir = millis(); 
            
            // ── Kirim ACK ke Firebase ──
            unsigned long t_beforeAck = millis();
            Firebase.RTDB.setBool(&fbdo, "/safe/ack", true);
            t_ackSent = millis();
            latency_solenoidToAck = t_ackSent - t_solenoidOpened;
            latency_cmdToAck      = t_ackSent - t_commandReceived;
            unsigned long ackWriteLatency = t_ackSent - t_beforeAck;

            Serial.printf( "║  ACK Write to Firebase        | %lu ms                    \n", ackWriteLatency);

            Firebase.RTDB.setString(&fbdo, "/safe/command", "IDLE");

            unsigned long t_beforeStatus = millis();
            Firebase.RTDB.setString(&fbdo, "/safe/status", "UNLOCKED");
            t_statusUpdated = millis();
            unsigned long statusWriteLatency = t_statusUpdated - t_beforeStatus;

            Serial.printf( "║  Status UNLOCKED Write        | %lu ms                    \n", statusWriteLatency);

            unsigned long t_beforeLog = millis();
            pushLog("Sistem", "INFO", "Brankas berhasil dibuka via Aplikasi (Face Recognition Valid).");
            t_logPushed = millis();
            latency_totalUnlock = t_logPushed - t_commandReceived;
            unsigned long logWriteLatency = t_logPushed - t_beforeLog;
            totalUnlockCount++;

            Serial.printf( "║  Log Push to Firebase         | %lu ms                    \n", logWriteLatency);
            Serial.println("╠═══════════════════════════════════════════════════════════╣");
            Serial.println("║                                                           ║");
            Serial.println("║   TABEL LATENCY ESP32 — PENGUJIAN SKRIPSI                ║");
            Serial.println("║                                                           ║");
            Serial.println("╠═══════════════════════════════╤═══════════════════════════╣");
            Serial.println("║ Tahapan                       │ Waktu (ms)                ║");
            Serial.println("╠═══════════════════════════════╪═══════════════════════════╣");
            Serial.printf( "║ CMD → Relay Activation        │ %-25lu ║\n", latency_cmdToSolenoid);
            Serial.printf( "║ Relay Activation              │ %-25lu ║\n", relayActivationLatency);
            Serial.printf( "║ Solenoid → ACK Write          │ %-25lu ║\n", latency_solenoidToAck);
            Serial.printf( "║ ACK Firebase Write            │ %-25lu ║\n", ackWriteLatency);
            Serial.printf( "║ Status Firebase Write         │ %-25lu ║\n", statusWriteLatency);
            Serial.printf( "║ Log Firebase Push             │ %-25lu ║\n", logWriteLatency);
            Serial.println("╠═══════════════════════════════╪═══════════════════════════╣");
            Serial.printf( "║ CMD → ACK Total               │ %-25lu ║\n", latency_cmdToAck);
            Serial.printf( "║ CMD → Log Pushed (E2E ESP32)  │ %-25lu ║\n", latency_totalUnlock);
            Serial.println("╠═══════════════════════════════╧═══════════════════════════╣");
            Serial.printf( "║ Unlock Count : #%-3d | RSSI : %d dBm                      \n", totalUnlockCount, WiFi.RSSI());
            Serial.println("╚═══════════════════════════════════════════════════════════╝");
            Serial.println("");
        }
      }
    }
  }

  // ==========================================================
  // 1.5. BACA & DEBOUNCE SENSOR PINTU (MC-38)
  // ==========================================================
  bool currentMc38 = digitalRead(MC38_PIN); 
  if (currentMc38 != lastMc38Read) {
      lastMc38DebounceTime = millis();
  }
  if ((millis() - lastMc38DebounceTime) > 150) { 
      if (pintuRapatStabil != (currentMc38 == LOW)) {
          pintuRapatStabil = (currentMc38 == LOW);
          Serial.printf("[MC-38] Status pintu berubah: %s\n", pintuRapatStabil ? "RAPAT" : "TERBUKA");
          Firebase.RTDB.setBool(&fbdo, "/sensors/doorClosed", pintuRapatStabil);
      }
  }
  lastMc38Read = currentMc38;

  // ==========================================================
  // 2. LOGIKA SENSOR PINTU -> Auto Lock & Peringatan Terbuka
  // ==========================================================
  if (isSolenoidTerbuka) {
    bool isGracePeriod = (millis() - waktuPintuDibuka < 10000);

    if (pintuRapatStabil) {
      if (!sedangProsesRapat) {
        waktuPintuMulaiRapat = millis();
        sedangProsesRapat = true;
      }
      
      if (!isGracePeriod && (millis() - waktuPintuMulaiRapat >= 1500)) {
        unsigned long t_lockStart = millis();
        Serial.println("");
        Serial.printf("  [T+%lu ms] Pintu rapat terdeteksi. Mengunci solenoid...\n", t_lockStart);
        digitalWrite(RELAY_PIN, RELAY_MATI); 
        isSolenoidTerbuka = false;
        sedangProsesRapat = false;
        notifIntrusiTerkirim = false; 
        
        Firebase.RTDB.setString(&fbdo, "/safe/status", "LOCKED");
        Firebase.RTDB.setBool(&fbdo, "/safe/ack", false); 
        
        unsigned long unlockDuration = t_lockStart - waktuPintuDibuka;
        Serial.printf("  >> Durasi solenoid terbuka: %lu ms (%.1f detik)\n",
                      unlockDuration, unlockDuration / 1000.0);
        Serial.println("  [OK] Brankas LOCKED kembali.");
        
        pushLog("Sistem", "INFO", "Brankas ditutup dan dikunci secara otomatis.");
      }
    } else {
      sedangProsesRapat = false; 
      
      if (millis() - waktuPintuDibuka >= 10000) {  
        if (!notifPintuTerbukaTerkirim) {
          notifPintuTerbukaTerkirim = true;
          Serial.println("[ALARM] Peringatan: Pintu Brankas Lupa Ditutup!");
          pushLog("Peringatan", "WARNING", "Pintu brankas masih terbuka, harap segera ditutup!");
        }
      }
    }
  }

  // ==========================================================
  // 3. LOGIKA PENGAWASAN ANOMALI (SENSOR PIR & INTRUSI)
  // ==========================================================
  if (!isSolenoidTerbuka) {
    // --- Deteksi Pembobolan Fisik (PRIORITAS TERTINGGI) ---
    if (!pintuRapatStabil) {
      if (!notifIntrusiTerkirim) {
        notifIntrusiTerkirim = true;
        Serial.println("[ALARM] INTRUSI! Pintu dicongkel!");
        Firebase.RTDB.setString(&fbdo, "/safe/status", "ALERT");
        digitalWrite(RELAY_PIN, RELAY_MATI); 

        // Jepret 1 Foto Bukti Secepat Kilat!
        analogWrite(FLASH_PIN, FLASH_INTENSITY); 
        delay(800);
        for(int i=0; i<3; i++){ camera_fb_t *d = esp_camera_fb_get(); if(d) esp_camera_fb_return(d); }
        
        String urlBukti = "";
        camera_fb_t *fb = esp_camera_fb_get();
        if (fb) {
           uint8_t* buf = (uint8_t*) ps_malloc(fb->len);
           if (buf) {
              memcpy(buf, fb->buf, fb->len);
              urlBukti = uploadFotoBurst(buf, fb->len);
              free(buf);
           }
           esp_camera_fb_return(fb);
        }
        analogWrite(FLASH_PIN, 0);

        pushLog("Pembobolan", "CRITICAL", "Pintu brankas terdeteksi terbuka secara paksa!", urlBukti);
      }
    } else {
      // Pintu sudah dirapatkan kembali. RESET INSTAN.
      if (notifIntrusiTerkirim) {
        if (Firebase.RTDB.setString(&fbdo, "/safe/status", "LOCKED")) {
            notifIntrusiTerkirim = false;
            pushLog("Sistem", "INFO", "Pintu brankas telah ditutup kembali setelah intrusi.");
        }
      }
    }

    // --- LOGIKA PIR (HANYA AKTIF JIKA PINTU RAPAT / TIDAK DIBOBOL) ---
    bool adaGerakan = (digitalRead(PIR_PIN) == HIGH);

    if (adaGerakan != statusPirTerakhir) {
      statusPirTerakhir = adaGerakan;
      Firebase.RTDB.setBool(&fbdo, "/sensors/pir", adaGerakan);
    }

    // Syarat: PIR hanya boleh memfoto kalau pintu sedang rapat.
    // Ini mencegah PIR nge-blokir program saat pintu sedang terbuka paksa.
    
    // Cek cooldown dulu — setelah foto/reset, tunggu 5 detik sebelum boleh deteksi lagi
    if (millis() < pirCooldownSampai) {
      // Dalam cooldown, abaikan PIR sepenuhnya
      sedangDeteksiPIR = false;
      pirHighCount = 0;
    }
    else if (adaGerakan && pintuRapatStabil) {
      // === MEKANISME SAMPLING KONTINU ===
      // PIR harus HIGH secara konsisten selama 3 detik penuh.
      // Kita sample setiap 200ms. Jika HIGH → counter++. Jika LOW → counter = 0.
      // Butuh 15 kali HIGH berturut-turut (15 x 200ms = 3 detik).
      
      if (!sedangDeteksiPIR) {
        sedangDeteksiPIR = true;
        pirHighCount = 0;
        pirLastSampleTime = millis();
        Serial.println("[PIR] Gerakan terdeteksi, mulai konfirmasi sampling...");
      }

      // Sample setiap PIR_SAMPLE_INTERVAL (200ms)
      if (millis() - pirLastSampleTime >= PIR_SAMPLE_INTERVAL) {
        pirLastSampleTime = millis();
        
        // Re-read PIR pada saat sampling untuk konfirmasi real
        bool konfirmasi = (digitalRead(PIR_PIN) == HIGH);
        
        if (konfirmasi) {
          pirHighCount++;
          Serial.printf("[PIR] Sampling #%d/%d — HIGH ✓\n", pirHighCount, PIR_REQUIRED_COUNT);
        } else {
          // PIR LOW saat sampling → gerakan hanya sekilas, RESET!
          Serial.printf("[PIR] Sampling #%d — LOW ✗ → Reset! (gerakan hanya sekilas)\n", pirHighCount);
          pirHighCount = 0;
          sedangDeteksiPIR = false;
          pirCooldownSampai = millis() + 2000; // Cooldown pendek 2 detik setelah false alarm
        }
      }

      // TARGET: PIR_REQUIRED_COUNT (15) kali HIGH berturut = 3 detik kontinu
      if (pirHighCount >= PIR_REQUIRED_COUNT) {
        Serial.println("[PIR] ✅ Anomali 3 Detik TERKONFIRMASI! Eksekusi BURST CAPTURE...");
        sedangDeteksiPIR = false;
        pirHighCount = 0;
        pirCooldownSampai = millis() + PIR_COOLDOWN_MS; // Cooldown 5 detik setelah foto
        
        uint8_t* burstFrames[3] = {NULL, NULL, NULL};
        size_t burstLengths[3] = {0, 0, 0};

        analogWrite(FLASH_PIN, FLASH_INTENSITY); 
        delay(800); // Biarkan sensor menyesuaikan eksposur

        for (int i = 0; i < 3; i++) {
          // KUNCI: Flush SEMUA buffer sebelum SETIAP foto
          // Dengan fb_count=2, kita harus buang 2 frame lama agar
          // esp_camera_fb_get() berikutnya mengembalikan frame BARU.
          for(int d = 0; d < 2; d++){
            camera_fb_t *dummy = esp_camera_fb_get();
            if (dummy) esp_camera_fb_return(dummy);
          }
          // Delay kecil agar sensor sempat meng-capture frame baru
          delay(150);

          // Ambil frame baru (sekarang pasti fresh dari sensor)
          camera_fb_t *fb = esp_camera_fb_get();
          if (fb) {
            burstFrames[i] = (uint8_t*) ps_malloc(fb->len);
            if (burstFrames[i] != NULL) {
              memcpy(burstFrames[i], fb->buf, fb->len);
              burstLengths[i] = fb->len;
              Serial.printf("[BURST] Foto #%d: %d bytes\n", i+1, fb->len);
            }
            esp_camera_fb_return(fb); 
          }
          // Jeda antar foto agar ada perbedaan waktu/posisi
          if (i < 2) delay(500);
        }
        
        analogWrite(FLASH_PIN, 0); 
        Serial.println("Selesai Burst. Mengunggah...");

        String urls[3] = {"", "", ""};
        int successCount = 0;
        for (int i = 0; i < 3; i++) {
          if (burstFrames[i] != NULL) {
            String secureUrl = uploadFotoBurst(burstFrames[i], burstLengths[i]);
            if (secureUrl != "gagal" && secureUrl != "gagal_konek") {
              urls[i] = secureUrl;
              successCount++;
            }
            free(burstFrames[i]); 
          }
        }
        
        if (successCount > 0) {
            FirebaseJson logJson;
            logJson.set("type", "Anomali");
            logJson.set("level", "CRITICAL");
            logJson.set("personName", "Sistem ESP32");
            logJson.set("description", "Terdapat gerakan di dekat brankas (" + String(successCount) + " foto diambil)");
            double timestamp_ms = (double)time(nullptr) * 1000.0;
            logJson.set("timestamp", timestamp_ms);
            
            if (urls[0] != "") logJson.set("imageUrl", urls[0]);
            if (urls[1] != "") logJson.set("imageUrl2", urls[1]);
            if (urls[2] != "") logJson.set("imageUrl3", urls[2]);
            
            Firebase.RTDB.pushJSON(&fbdo, "/logs", &logJson);
        }
        Serial.println("[SISTEM] Pengawasan Aktif Kembali...");
      }
    } else {
      // PIR LOW — jika sedang deteksi, langsung reset counter
      if (sedangDeteksiPIR) {
        Serial.printf("[PIR] Gerakan hilang (counter: %d/%d). Reset.\n", pirHighCount, PIR_REQUIRED_COUNT);
        sedangDeteksiPIR = false;
        pirHighCount = 0;
        // Tidak ada cooldown untuk "gerakan hilang natural"
      }
    }
  }

  // --- HEARTBEAT REAL-TIME UNIX TIMESTAMP + LATENCY MONITOR ---
  static unsigned long lastHeartbeat = 0;
  if(millis() - lastHeartbeat >= 10000) {
      unsigned long t_hbStart = millis();
      lastHeartbeat = t_hbStart;
      double realTime = (double)time(nullptr) * 1000.0;
      Firebase.RTDB.setDouble(&fbdo, "/sensors/heartbeat", realTime);
      unsigned long hbLatency = millis() - t_hbStart;
      Serial.printf("[HB] T+%lu ms | Firebase write: %lu ms | RSSI: %d dBm | Unlock count: %d\n",
                    t_hbStart, hbLatency, WiFi.RSSI(), totalUnlockCount);
  }
}