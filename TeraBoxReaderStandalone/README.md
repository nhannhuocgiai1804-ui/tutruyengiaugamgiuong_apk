# TeraBox Reader - Standalone Android App (Không Cần Server Trung Gian)

Ứng dụng Android Kotlin độc lập kết nối trực tiếp đến TeraBox bằng OkHttp native, đọc sách và manga theo cơ chế HTTP Range streaming (không tải toàn bộ file).

## Điểm Nổi Bật
- **100% Độc lập**: Không cần server Python hay Render chạy ngầm.
- **Đọc không cần tải**: RemoteZipFile đọc Central Directory ở cuối file (tail 65KB), chỉ tải đúng chương hoặc trang ảnh đang đọc.
- **Đầy đủ định dạng**: EPUB, CBZ, PDF, MOBI/AZW3, DOCX, TXT.
- **Bảo mật**: Mã hóa cookie ndus bằng EncryptedSharedPreferences (AndroidX Security Crypto).
- **Hỗ trợ Intent-Filter**: Nhận và mở trực tiếp link chia sẻ terabox.com/s/..., 1024terabox.com/s/...

## Cách Mở Trong Android Studio
1. Giải nén thư mục `TeraBoxReaderStandalone`.
2. Mở Android Studio -> chọn **File -> Open** và chọn thư mục vừa giải nén.
3. Đợi Gradle đồng bộ thư viện (khoảng 1–2 phút).
4. Nhấn **Run (Shift + F10)** để cài đặt lên điện thoại.

## Cách Build APK Bằng GitHub Actions (Không Cần Cài Android Studio)
1. Tạo một repository mới trên GitHub.
2. Push toàn bộ mã nguồn này lên nhánh `main`.
3. Vào tab **Actions** -> Chọn **Build TeraBox Reader Standalone APK** -> Bấm **Run workflow**.
4. Tải file APK tại mục **Artifacts** về cài lên điện thoại.
