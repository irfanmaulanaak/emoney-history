# Emoney Reimburse

Aplikasi Android untuk memudahkan proses reimburse parkir dengan membaca kartu e-money (Mandiri e-money, BCA Flazz, dll) melalui NFC dan menghasilkan dokumen PDF otomatis.

## Fitur

- **Scan Kartu NFC**: Membaca kartu e-money/Flazz langsung dari ponsel Android dengan NFC
- **Pilih Transaksi**: Memilih transaksi parkir yang valid untuk direimburse
- **Generate PDF**: Membuat dokumen PDF berisi screenshot transaksi, perhitungan total, dan nama
- **Mode Demo**: Testing tanpa kartu fisik menggunakan data demo
- **Support Multi Kartu**: Mandiri e-money, BCA Flazz, BNI Tapcash, BRI Brizzi (work in progress)

## Flow Aplikasi

1. Buka aplikasi dan tempelkan kartu e-money/Flazz ke bagian belakang ponsel
2. Pilih transaksi parkir yang valid dari daftar yang terbaca
3. Isi nama lengkap Anda
4. Generate PDF
5. Bagikan PDF via WhatsApp, Email, dll

## Tech Stack

- **Language**: Kotlin
- **UI**: Jetpack Compose (Material 3)
- **Architecture**: MVVM dengan ViewModel
- **NFC**: android.nfc.tech.IsoDep & MifareClassic
- **PDF**: iTextG 5.5.10
- **Build**: Gradle dengan Kotlin DSL

## Cara Build

### Prerequisites

- Android Studio Hedgehog (2023.1.1) atau lebih baru
- Android SDK 34
- JDK 17
- Perangkat Android dengan NFC (untuk testing kartu sungguhan)

### Steps

1. Clone repository:
```bash
git clone git@github.com:irfanmaulanaak/emoney-history.git
cd emoney-history
```

2. Buka project di Android Studio

3. Sync Gradle dan build project

4. Run pada perangkat Android dengan NFC

## Struktur Project

```
app/src/main/java/com/emoneyreimburse/
├── MainActivity.kt           # Entry point & NFC handling
├── model/
│   └── Transaction.kt        # Data models (Transaction, CardInfo, etc)
├── nfc/
│   └── NfcCardReader.kt      # NFC card reading logic
├── pdf/
│   └── PdfGenerator.kt       # PDF generation with iText
└── ui/
    ├── MainViewModel.kt      # App state management
    ├── ScanScreen.kt         # NFC scanning UI
    ├── SelectScreen.kt       # Transaction selection UI
    ├── InputScreen.kt        # Name input UI
    └── PreviewScreen.kt      # PDF preview & share UI
```

## NFC Card Compatibility

### Didukung (dengan catatan)

| Kartu | Type | Status |
|-------|------|--------|
| Mandiri e-money | Mifare Classic / IsoDep | ⚠️ Testing required |
| BCA Flazz | Mifare Classic / IsoDep | ⚠️ Testing required |
| BNI Tapcash | Mifare Classic | ⚠️ Testing required |
| BRI Brizzi | Mifare Classic | ⚠️ Testing required |

### Catatan Penting

- **Aplikasi ini memerlukan kartu fisik** untuk testing fungsi NFC
- Tidak semua kartu e-money menyimpan log transaksi yang dapat dibaca
- Kartu yang lebih baru mungkin menggunakan enkripsi yang lebih kuat
- Jika kartu tidak terbaca, gunakan **Mode Demo** untuk mencoba fitur aplikasi

## Demo Mode

Untuk mencoba aplikasi tanpa kartu fisik:
1. Buka aplikasi
2. Klik tombol **"Gunakan Data Demo"**
3. Aplikasi akan menampilkan 10 transaksi contoh
4. Pilih transaksi, isi nama, dan generate PDF

## Known Issues & Limitations

1. **NFC Reading**: Pembacaan kartu sungguhan memerlukan testing dengan kartu fisik karena setiap jenis kartu memiliki format data yang berbeda
2. **Mifare Classic Keys**: Beberapa kartu menggunakan proprietary keys yang tidak dapat diautentikasi
3. **iTextG**: Menggunakan iTextG (LGPL) untuk kompatibilitas Android

## Contributing

Pull requests welcome! Fokus utama yang dibutuhkan:
- Testing dengan kartu e-money sungguhan
- Reverse engineering format data kartu spesifik
- Penambahan parser untuk kartu baru

## License

MIT License - see LICENSE file for details

## Acknowledgments

- [EMV-NFC-Paycard-Enrollment](https://github.com/devnied/EMV-NFC-Paycard-Enrollment) - Referensi library EMV
- [prepaidcard_reader](https://github.com/agusibrahim/prepaidcard_reader) - Referensi Flutter plugin untuk kartu Indonesia
- [eChis](https://play.google.com/store/apps/details?id=com.medicom.flutter_app_nfc) - Referensi aplikasi pembaca kartu e-toll
