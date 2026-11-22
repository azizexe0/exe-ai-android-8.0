## 8.0 ile gelenler (1. aşama)
- Gün planlayıcı: "1 ay sonra saat 10 Ankara'ya gidicem" gibi planları kaydeder; "bugünün planı" ile sorarsın. Bildirim yalnızca "hatırlat" ya da "bildirim" denirse kurulur.
- Notlar: "not al: ..." ve "notlarımı göster".
- Sohbet listesinde uzun basınca yeniden adlandırma ve silme.
- WhatsApp: "whatsapp'tan Timu'ya merhaba yaz" rehberden kişiyi bulur, onay sorar, WhatsApp'ta mesajı açar; göndermeyi sen yaparsın.
- Sonraki aşamalar: hava durumu, çeviri, özetler, yedekleme, kısayollar, bilgi yarışması, güncel bilgi.

# EXE AI 8.0

EXE AI 6.0 Android Java projesi temel alınarak hazırlanmış 8.0 sürümü. Yapay zekâ cevapları Google Gemini API üzerinden gelir. Java kullanılır, Kotlin yoktur.

## Derleme (APK)

1. Bu klasörü GitHub deposuna yükle.
2. Actions sekmesinde **Build EXE AI 8.0 APK** iş akışını çalıştır (`workflow_dispatch`) ya da `main` dalına push et.
3. Çıkan `EXE-AI-8.0-APK` artefaktından `app-debug.apk` dosyasını indir.

Yerel derleme: Android Studio veya AndroidIDE ile proje kökünü aç, Gradle 8.9 ve JDK 17 kullan, `gradle assembleDebug` çalıştır.

## 8.0 ile gelenler

- **Siyah-kırmızı arayüz:** sohbet ekranı, mesaj alanı, GÖNDER düğmesi, Sesli, Canlı, Kamera, Görsel, Dosya, Bilgi, Alarmlar, Hafıza, Ses ve Sistem düğmeleri.
- **Gizli API ayarları:** "EXE AI" başlığına basılı tut. Gemini anahtarı buradan girilir, bağlantı testi yapılır, model elle seçilebilir (boş bırakılırsa otomatik seçim yapılır).
- **API anahtarı güvenliği:** anahtar `EncryptedSharedPreferences` ile şifreli saklanır, kaynak koduna yazılmaz. Güvenli depo açılamazsa anahtar kaydedilmez.
- **Gemini bağlantısı:** `generateContent` REST çağrısı, anahtar `x-goog-api-key` başlığında gider. Görsel (fotoğraf) gönderimi desteklenir.
- **Otomatik model seçimi:** kısa ve basit sorular `gemini-2.5-flash`, uzun veya analitik sorular (neden, karşılaştır, kod, analiz, adım adım) `gemini-2.5-pro` ile çalışır.
- **API hata mesajları:** 400, 401/403, 404, 429, 5xx ve ağ hataları Türkçe olarak gösterilir.
- **Doğal Türkçe sohbet:** kısaltmalar (mrb, slm, nbr, nsl, tşk…) ve yakın yazım hataları kullanıcıya gösterilmeden düzeltilir. Anlaşılmayan mesajlarda tahmin uydurmadan "Anlamadım efendim." denir.
- **Kimlik:** "efendim" hitabı; kendini insan olarak tanıtmaz; kim kodladı sorusuna "Beni Aziz EXE kodladı." der.
- **Sesli sohbet:** Sesli düğmesi tek seferlik konuşma tanıma yapar. Canlı düğmesi açıkken her cevaptan sonra otomatik dinlemeye geçer. "EXE" veya "Hey EXE" ile başlayan ifadeler algılanır; yalnız seslenildiğinde "Evet efendim?" der.
- **Ses ayarları (🔊):** sesli cevap açma/kapatma, cihazdaki Türkçe sesler arasından seçim, ses tonu, konuşma hızı, örnek konuşmayı dinleme. Seçimler kalıcı kaydedilir.
- **Alarm ve hatırlatıcı:** "saat 18.30'a alarm kur", "10 dakika sonra hatırlat", "her gün … tekrar" kalıpları. Listeleme ve silme ("alarm sil <no>"). AlarmManager ile çalışır, yeniden başlatmada BootReceiver ile yeniden kurulur.
- **Cihaz kontrolü:** kurulu uygulamayı adıyla açma (yaklaşık yazımla), Wi-Fi ve Bluetooth ayarları, ses artırma/azaltma, kamera ve galeri açma, sistem ayarları. Uygulamanın açıldığı Android tarafından doğrulanmaz; "açmayı denedim" anlamındadır.
- **Hafıza:** "hatırla: …" ile kayıt, "hafızamı listele", "hafızayı sil". Kayıtlar SQLite'a yazılır.
- **Veri:** 6.0 veritabanı dosyası (`exe_ai_memory.db`) aynen kullanılır; sürüm 2'den 3'e yükseltme eski satırları korur. Yeni tablolar: `reminders`, `history`.
- **Bilgi paketi:** TSV içe aktarma (📚 Bilgi). Örnek paket 48 satırdır.
- **Web araştırması:** DuckDuckGo HTML ve Bing RSS; bulunan sayfalardan metin çıkarılır; kaynak URL'leri cevapta gösterilir. `*** konu` komutu da çalışır.
- **Dosya ekleme (📄 Dosya):** metin tabanlı dosyanın ilk 12.000 karakteri bir sonraki soruya eklenir.

## Bilinen sınırlar

- Gemini API anahtarı gerekir; anahtar olmadan yalnızca yerel bilgi, web araştırması ve komutlar çalışır.
- Model adları (`gemini-2.5-flash`, `gemini-2.5-pro`) `GeminiClient.java` içinde sabittir. Google model adlarını değiştirirse gizli ayarlardan model elle girilebilir.
- Sürekli arka planda mikrofon dinleme yoktur. Android, uygulamanın kapalıyken sürekli mikrofon kullanmasına izin vermez; "EXE" algılama yalnızca uygulama açıkken veya Canlı modda çalışır.
- Güç tuşuna üç kez basma, kilit ekranı asistanı ve sistem genelinde yüzen panel yoktur.
- WhatsApp mesajı gönderme yoktur. Spesifikasyonda istenen kullanıcı onaylı akış bu sürümde uygulanmamıştır.
- Erişilebilirlik (AccessibilityService) ile ekran otomasyonu yoktur.
- 1.000.000 kayıtlık bilgi tabanı yoktur. Bu sürüm içe aktarma hattını ve şablonu içerir; içinde yalnızca örnek paket vardır. Uydurulmuş kayıt eklenmemiştir.
- Zamanlanmış görev ve dosya arşivi (listeleme, toplu silme) ayrı bir ekran olarak yazılmamıştır.
- Çevrim dışı mod: yerel bilgi ve hafıza çalışır; tam bir çevrim dışı LLM ya da tüm kategorilerde dolu bilgi havuzu yoktur.
- Bu ortamda derleme ve cihaz testi yapılmamıştır. Java kaynakları sözdizimi açısından kontrol edilmiştir; Android'e özgü API kullanımı ve derleme çıktısı GitHub Actions'ta doğrulanmalıdır.
