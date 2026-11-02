# Family TV

Ứng dụng Android TV cho gia đình: xem các video bố mẹ đã chép sẵn trên USB, giao diện kiểu "TV app" (menu trái, các hàng video, tìm kiếm, trình phát toàn màn hình), điều khiển hoàn toàn bằng remote, **chạy offline**.

- Không cần Internet (APK **không có quyền INTERNET**), không tài khoản, không quảng cáo, không analytics, không YouTube API.
- Chỉ hiển thị video trong các thư mục được khai báo trong `config.json`.
- Chế độ **Trẻ em / Người lớn / Tất cả**. Mỗi lần mở app đều vào chế độ Trẻ em; đổi sang chế độ khác cần PIN.
- Giới hạn thời gian xem theo ngày cho chế độ Trẻ em.
- App **chỉ đọc** USB, không ghi hay sửa gì trên USB.
- Trải nghiệm giống app video quen thuộc: hàng "Đề xuất cho bạn", bấm Xuống khi đang xem để mở kệ "Video tiếp theo", màn kết thúc có gợi ý, đếm ngược "Tiếp theo". Logo là nút play đỏ; ở chế độ Trẻ em không có dấu hiệu nào cho thấy app đang lọc nội dung (xem mục 4.1).

APK debug đã build sẵn: **`dist/FamilyTV-1.0.0-debug.apk`** (bản gốc nằm ở `app/build/outputs/apk/debug/app-debug.apk`).

---

## 1. Chuẩn bị USB

### 1.1 Cấu trúc thư mục

```
FamilyTV/                 ← thư mục bạn sẽ chọn trong app
  config.json
  background.jpg          ← ảnh nền của app (không bắt buộc)
  videos/
    kids/
      songs/
        Bai hat ABC.mp4
        Bai hat ABC.jpg   ← ảnh đại diện (không bắt buộc)
      english/
    adults/
    shared/
```

File mẫu: [`sample/FamilyTV/config.json`](sample/FamilyTV/config.json). Video tải từ Internet nên chạy qua bước chuyển đổi ở mục 1.6 trước khi chép lên USB. Muốn có thư viện thử nghiệm (video mẫu dạng sọc màu), chạy `tools/make_demo_library.sh <thư-mục-đích>`; script này cần ffmpeg. Thư viện mẫu chỉ để thử, app không chứa dữ liệu giả nào.

USB định dạng **FAT32 hoặc exFAT** là an toàn nhất trên TV. Tên file tiếng Việt có dấu được hỗ trợ, kể cả tên file chép từ máy Mac.

### 1.2 `config.json`

```json
{
  "schemaVersion": 1,
  "appName": "Family TV",
  "defaultMode": "kids",
  "settings": { "autoplay": false, "resumePlayback": true, "sessionLimitMinutes": 30 },
  "folders": [
    { "id": "kids-songs", "name": "Các bài hát thiếu nhi", "path": "videos/kids/songs",
      "audience": "kids", "recursive": true, "sortBy": "filename" }
  ]
}
```

| Khóa | Bắt buộc | Ý nghĩa |
|---|---|---|
| `schemaVersion` | có | Phải là `1`. Giá trị khác bị từ chối. |
| `appName` | không | Tên hiện ở đầu Trang chủ (tối đa 40 ký tự). |
| `defaultMode` | không | Chỉ chấp nhận `kids` / `adults` / `all`, nhưng **app luôn khởi động ở Trẻ em**. Giá trị khác `kids` sẽ bị bỏ qua kèm cảnh báo. |
| `settings.autoplay` | không | Tự phát video tiếp theo (mặc định `false`). |
| `settings.resumePlayback` | không | Hỏi "xem tiếp từ chỗ cũ" (mặc định `true`). |
| `settings.sessionLimitMinutes` | không | Số phút xem mỗi ngày ở chế độ Trẻ em, từ `0` đến `1440`; `0` = không giới hạn; mặc định `30`. |
| `folders[].id` | có | Chữ không dấu, số, `-`, `_`; không được trùng (không phân biệt hoa/thường). |
| `folders[].name` | có | Tên hàng/danh mục hiển thị. |
| `folders[].path` | có | Đường dẫn **tương đối** tính từ thư mục FamilyTV; `.` là chính thư mục FamilyTV. |
| `folders[].audience` | có | `kids`, `adults` hoặc `all`. |
| `folders[].recursive` | không | Lấy cả thư mục con (mặc định `true`). |
| `folders[].sortBy` | không | `filename` (mặc định, sắp xếp tự nhiên: "Bài 2" đứng trước "Bài 10"), `filename_desc`, `modified_desc` (mới nhất trước), `modified_asc`. |

Thiết lập bố mẹ chọn trong app (Khu vực bố mẹ) được ưu tiên hơn `settings` trong JSON. Mỗi mục đều có lựa chọn "Theo config.json".

**Kiểm tra trước khi áp dụng.** Các lỗi sau làm **cả file bị từ chối**: JSON sai cú pháp (báo dòng và cột), `schemaVersion` không hỗ trợ, `id` trùng, `audience` không hợp lệ, đường dẫn tuyệt đối (`/storage/...`, `C:\...`, `content://...`), đường dẫn chứa `..`, kiểu dữ liệu sai.
Các trường hợp sau chỉ là **cảnh báo**: thư mục không tồn tại (hàng đó sẽ trống), khóa lạ, `sortBy` lạ.

Khi bản cập nhật `config.json` bị lỗi, app **giữ bản hợp lệ gần nhất**, hiện chip cảnh báo ở Trang chủ và liệt kê từng lỗi trong Khu vực bố mẹ. Nếu chưa từng có bản hợp lệ nào, app không hiển thị nội dung.

### 1.3 Quy tắc kids / adults / all và khi các mục trùng nhau

- Chế độ **Trẻ em** hiện các hàng `kids` + `all`.
- Chế độ **Người lớn** hiện các hàng `adults` + `all`.
- Chế độ **Tất cả** hiện mọi hàng.

**Quy tắc an toàn:** một file có thể nằm trong nhiều mục cấu hình, ví dụ mục `kids` khai báo `videos` (đệ quy) và mục `adults` khai báo `videos/adults`. Nếu **bất kỳ** mục nào chứa file đó có `audience: "adults"`, file sẽ **không bao giờ** xuất hiện ở chế độ Trẻ em, dù ở hàng kids, tìm kiếm, yêu thích, xem tiếp, lịch sử, hàng đợi phát hay khi mở theo ID. App cũng nhận ra hai đường dẫn chỉ khác hoa/thường là cùng một thư mục, và hiện cảnh báo giải thích khi phát hiện phạm vi chồng lấn.

### 1.4 Ảnh đại diện (thumbnail)

- Đặt ảnh **cùng tên** với video, cạnh video: `Bai hat ABC.mp4` → `Bai hat ABC.jpg`. Các đuôi được nhận: `.jpg`, `.jpeg`, `.png`, `.webp`.
- Nếu không có ảnh, app tự trích một khung hình (khoảng 10% thời lượng) **trong nền**, tối đa 2 video cùng lúc.
- Ảnh được thu nhỏ còn 480×270 và lưu trong bộ nhớ đệm riêng của app. Bộ nhớ đệm tối đa khoảng 120 MB, tự dọn ảnh cũ; nếu TV còn dưới 50 MB trống thì app chỉ giữ ảnh trong RAM. Không có gì được ghi lên USB.
- Nếu không tạo được ảnh, app hiển thị ô màu kèm biểu tượng ▶.

**Ảnh nền của app:** đặt file `background.jpg` (hoặc `background.jpeg`/`.png`/`.webp`) ngay trong thư mục `FamilyTV`. App hiển thị ảnh này phía sau Trang chủ và các trang khác, làm tối dần xuống dưới để chữ vẫn dễ đọc. Ảnh được thu nhỏ về tối đa 1920 px và lưu một bản trong bộ nhớ đệm của app. Muốn đổi ảnh thì thay file rồi rút/cắm lại USB (hoặc "Quét lại thư viện"); muốn bỏ thì xóa file. Đây là nền **bên trong app**: màn hình chính của Android TV (launcher Sony/Google) không cho đặt ảnh nền riêng.

### 1.5 Định dạng video

Khả năng phát **phụ thuộc codec và phần cứng của TV**, app không đảm bảo phát được mọi file. Khuyên dùng **MP4 (H.264 + AAC)**. MKV/WebM/MOV/TS thường phát được nếu codec bên trong được TV hỗ trợ. WMV không được hỗ trợ. File lỗi hoặc không hỗ trợ sẽ hiện thông báo dễ hiểu, app không bị crash.

TV Sony đang dùng (BRAVIA VU1, Android 11) giải mã được hình H.264, H.265, VP8, VP9, MPEG-2/4, nhưng **không giải mã được AV1**. Video tải từ Internet rất hay dùng AV1, kể cả khi đuôi file là `.mp4`. Gặp file như vậy, app báo "TV này không có bộ giải mã hình cho định dạng AV1". Trước khi có bản sửa này, TV chỉ phát tiếng mà không có hình. Cách xử lý: xem mục 1.6.

Các file sau bị bỏ qua: file/thư mục bắt đầu bằng `.` (kể cả file `._xxx` do macOS tạo), `$RECYCLE.BIN`, `System Volume Information`. Mỗi lần quét tối đa 20.000 video và thư mục sâu tối đa 12 cấp.

### 1.6 Chuyển đổi video và âm thanh cho TV

Cần cài **ffmpeg** (gồm cả `ffprobe`), ví dụ: `winget install Gyan.FFmpeg`.

**Kiểm tra codec của một file:**

```
ffprobe -v error -show_entries stream=codec_type,codec_name,width,height -of compact "ten video.mp4"
```

TV phát tốt khi hình là `h264` (tối đa 1920×1080) và tiếng là `aac` hoặc `mp3`. Nếu thấy hình là `av1`, cần chuyển đổi.

**Chuyển cả thư mục FamilyTV (khuyên dùng):**

```
powershell -ExecutionPolicy Bypass -File tools\convert_for_tv.ps1 -Source E:\FamilyTV -Output D:\FamilyTV_tv
```

Script không sửa thư mục gốc. Nó tạo thư mục `-Output` có cùng cấu trúc rồi xử lý từng file:
- **Video đã tương thích** (hình H.264 yuv420p tối đa 1080p, tiếng AAC/MP3, đuôi `.mp4`): chép nguyên.
- **Hình** cần chuyển: mã hóa lại sang H.264 (High profile, level 4.1, CRF 21, yuv420p), thu về tối đa 1920 px chiều ngang.
- **Tiếng:** nếu đã là AAC hoặc MP3 thì giữ nguyên (chép thẳng, không giảm chất lượng); codec khác (Opus, Vorbis, AC-3, DTS…) được chuyển sang **AAC 160 kbps**.
- File kết quả luôn là `.mp4` (bật `faststart` để mở nhanh).
- `config.json`, ảnh đại diện `.jpg/.png` và các file khác được chép sang; file `._xxx` của macOS bị bỏ qua.
- **Dừng giữa chừng rồi chạy lại được:** lần chạy sau bỏ qua file đã xong và chỉ xử lý file mới hoặc file đã thay đổi. File đang mã hóa được ghi vào `xxx.mp4.part` rồi mới đổi tên, nên không bao giờ có file `.mp4` dở dang.
- **File đang tải về** (file tạm của yt-dlp như `.part`, `.ytdl`, `.temp.webm`, `.f123.webm`, hoặc file vừa thay đổi trong 2 phút gần nhất) được để lại. Tải xong thì chạy lại lệnh trên.
- Script chạy ở mức ưu tiên thấp nên máy vẫn dùng bình thường được. Script dùng CPU (x264): trên máy Core i7-8700, video 4K AV1 chuyển được khoảng gấp 2 lần thời gian thực (1 giờ video mất khoảng 30 phút). Card NVIDIA không được dùng vì ffmpeg 8.x cần driver 570 trở lên.

Chạy xong, chép thư mục `-Output` lên USB thay cho thư mục FamilyTV cũ.

**Chuyển một file bằng ffmpeg:**

```
ffmpeg -i "vao.mp4" -map 0:v:0 -map 0:a:0? -c:v libx264 -preset medium -crf 21 -profile:v high -level:v 4.1 -pix_fmt yuv420p -vf "scale='min(1920,iw)':-2" -c:a aac -b:a 160k -movflags +faststart "ra.mp4"
```

Nếu tiếng gốc đã là AAC, có thể thay `-c:a aac -b:a 160k` bằng `-c:a copy` để giữ nguyên âm thanh và chạy nhanh hơn.

**Đã làm với USB hiện tại (28/10/2026):** 2 video trong `videos/kids/songs` và `videos/kids/english` là AV1 1080p, tiếng AAC. Tôi đã chuyển hình sang H.264 1080p, **giữ nguyên tiếng AAC**, đặt lại đúng chỗ với cùng tên file (nên tiến độ xem và yêu thích vẫn giữ). Hai file AV1 gốc được chuyển sang `FamilyTV/_goc_AV1/`; app không hiển thị thư mục này, có thể xóa khi không cần nữa.

---

## 2. Cài lên Android TV (Sony KD-49X7500H)

**Kiểm tra phiên bản Android trước:** Cài đặt → Tùy chọn thiết bị → Giới thiệu → Phiên bản. App hỗ trợ Android 6.0 trở lên. TV đang dùng thực tế báo `BRAVIA VU1`, **Android 11**.

**Cách A – qua adb (khuyên dùng):**
1. Trên TV: Cài đặt → Tùy chọn thiết bị → Giới thiệu → bấm 7 lần vào "Bản dựng" để bật Tùy chọn nhà phát triển. Sau đó vào Tùy chọn nhà phát triển, bật "Gỡ lỗi USB" / "Gỡ lỗi qua mạng" nếu có.
2. Trên máy tính cùng mạng: `adb connect <IP-của-TV>:5555`, chấp nhận hộp thoại trên TV.
3. `adb install -r dist/FamilyTV-1.0.0-debug.apk`

**Cách B – qua USB và ứng dụng quản lý tệp:** chép APK vào USB, mở bằng một ứng dụng quản lý tệp trên TV, và cho phép "Nguồn không xác định" cho ứng dụng đó (Cài đặt → Tùy chọn thiết bị → Bảo mật & giới hạn).

Biểu tượng Family TV sẽ xuất hiện trong hàng ứng dụng của launcher Android TV.

## 3. Lần đầu mở app

1. **Tạo PIN bố mẹ** (4–8 chữ số; app từ chối PIN quá dễ như `0000` hay `1234`). Không có PIN mặc định.
2. **Chọn thư mục FamilyTV** trong Khu vực bố mẹ. Trên menu trái, mục này hiện tên **"Cài đặt"** (biểu tượng bánh răng) và luôn cần PIN:
   - **"Chọn thư mục FamilyTV"** dùng trình chọn thư mục của hệ thống (Storage Access Framework). Mở USB, chọn thư mục `FamilyTV`, bấm "Cho phép". App lưu quyền truy cập lâu dài nếu TV hỗ trợ.
   - Nếu TV **không có trình chọn thư mục**, app sẽ báo rõ. Tình huống này đã xảy ra thật trên emulator Android TV 9 (xem mục 8). Khi đó dùng **"Đọc USB trực tiếp (dự phòng)"**:
     - **Android 6–10:** bấm "Cấp quyền đọc bộ nhớ" → Cho phép. App liệt kê các ổ, rồi chọn `<USB> / FamilyTV`.
     - **Android 11 trở lên:** cần quyền "Truy cập mọi tệp". Nhiều TV không có màn hình cấp quyền này; khi đó chạy lệnh sau từ máy tính:
       `adb shell appops set --uid com.familytv.app MANAGE_EXTERNAL_STORAGE allow`
3. App quét thư viện (chạy nền) và hiện Trang chủ ở chế độ Trẻ em.

## 4. Điều khiển bằng remote

| Phím | Màn hình duyệt | Trình phát |
|---|---|---|
| ◀ ▶ ▲ ▼ | Di chuyển; ◀ ở cột đầu sẽ mở menu trái | Khi thanh điều khiển ẩn: ◀ ▶ tua 10 giây (giữ lâu để tua 30 giây); ▲ hiện thanh điều khiển; **▼ mở kệ "Video tiếp theo"** |
| OK | Mở / chọn. **Giữ OK** trên một video để thêm/bỏ Yêu thích | Phát/tạm dừng; khi thanh điều khiển hiện thì OK chọn nút đang focus |
| Back | Về trang trước → Trang chủ → thoát app | Nếu video đang phát và thanh điều khiển đang hiện: ẩn thanh; các trường hợp khác: thoát trình phát |
| ⏯ ⏩ ⏪ ⏭ ⏮ | – | Phát/tạm dừng, tua ±30 giây, video tiếp/trước |
| Phím số | Nhập PIN | – |

Quay lại từ trình phát vẫn giữ vị trí danh sách và thẻ video đang chọn. Thanh điều khiển của trình phát có: Trước, −10 giây, Phát/Tạm dừng, +10 giây, Tiếp, Xem từ đầu, Yêu thích, Tuần tự/Ngẫu nhiên, Lặp (tắt / cả danh sách / một video). Danh sách phát tiếp luôn được lọc theo chế độ hiện tại và được dựng lại khi đổi chế độ.

Cách các lựa chọn phát tiếp hoạt động khi hết video:
- **Lặp 1 video:** phát lại chính video đó.
- **Lặp danh sách:** chuyển sang video kế tiếp và quay vòng.
- **Tự phát tiếp** (mặc định tắt): chuyển sang video kế tiếp, dừng khi hết danh sách.
- Với "Lặp danh sách" và "Tự phát tiếp", trước khi chuyển video sẽ có thẻ **"Tiếp theo sau 5 giây"** kèm nút Phát ngay / Hủy. Bấm Hủy hoặc Back thì ở lại màn kết thúc.
- Không bật lựa chọn nào: hiện "Đã xem xong" với các nút Xem lại / Video tiếp theo / Quay lại, bên dưới là kệ gợi ý.

### 4.1 Giống app video quen thuộc

- **Trang chủ:** đầu trang có logo nút play đỏ và tên app (lấy từ `appName` trong `config.json`). Hàng đầu tiên là **"Đề xuất cho bạn"**, gồm video lấy xen kẽ từ các danh mục, video chưa xem xong xếp trước. Hàng này chỉ hiện khi có ít nhất 2 danh mục có video.
- **Đang xem, bấm ▼:** kệ **"Video tiếp theo"** trượt lên. Thứ tự gợi ý: các video kế tiếp trong danh sách đang xem, rồi video cùng thư mục, rồi các video đề xuất khác. Chọn một thẻ để phát ngay; ▲ quay về các nút điều khiển. Khi thanh điều khiển đang hiện, dòng chữ "Video tiếp theo" ló ra ở mép dưới; bấm ▼ từ hàng nút cũng mở kệ.
- **Hết video:** màn kết thúc có kệ gợi ý; nếu bật tự phát hoặc lặp danh sách thì có đếm ngược "Tiếp theo".
- **Ở chế độ Trẻ em, app không hiện** nút "Chế độ", số phút còn lại, cảnh báo config hay chữ "chế độ Trẻ em" ở Tìm kiếm/Danh mục. Mục Khu vực bố mẹ trên menu mang tên **"Cài đặt"** với biểu tượng bánh răng, và hỏi "Nhập mã PIN". Các thông tin này vẫn hiện ở chế độ Người lớn/Tất cả và trong Cài đặt.
- **Logo:** icon launcher, banner Android TV và logo trong app là **nút play đỏ bo góc** (biểu tượng play chung, tự vẽ, không dùng logo hay tên thương hiệu của hãng khác). Tên hiện dưới icon trên launcher vẫn là "Family TV"; muốn đổi thì sửa `app_name` trong `app/src/main/res/values/strings.xml` rồi build lại. Tên trong app đổi bằng `appName` trong `config.json`.
- **An toàn:** mọi gợi ý, đề xuất và đếm ngược đều lấy từ dữ liệu đã lọc theo chế độ. Ở chế độ Trẻ em không có đường nào để hiện video `adults` (có unit test cho `recommended` và `related`). Giới hạn giờ xem vẫn áp dụng khi phát từ gợi ý.

## 5. Chế độ xem và PIN

- Mỗi lần mở app, và mỗi khi app bị đưa xuống nền (bấm Home, TV ngủ), app tự quay về **Trẻ em** và khóa Khu vực bố mẹ.
- Cần PIN để chuyển sang Người lớn hoặc Tất cả, vào Khu vực bố mẹ (đổi nguồn USB, quét lại, đổi giới hạn, xóa dữ liệu) và thêm giờ xem.
- Khi chưa có PIN, không thể vào nội dung `adults`.
- PIN **không lưu dạng rõ**: app chỉ lưu PBKDF2-HMAC-SHA256 (40.000 vòng) với salt ngẫu nhiên 16 byte, trong dữ liệu riêng của app. PIN không bao giờ được ghi log, và dữ liệu này bị loại khỏi sao lưu/chuyển thiết bị.
- **Giới hạn số lần thử:** cho phép sai 4 lần; lần sai thứ 5 khóa 30 giây, mỗi lần sai tiếp theo thời gian khóa nhân đôi, tối đa 15 phút. Thời gian khóa đo bằng đồng hồ đơn điệu (`elapsedRealtime`), nên chỉnh giờ TV không rút ngắn được. Khởi động lại app hay TV cũng không xóa được trạng thái khóa.
- **Đổi PIN:** Khu vực bố mẹ → Đổi PIN (nhập PIN hiện tại, PIN mới, nhập lại).
- **Quên PIN:** không có mã vượt khóa. Cách duy nhất là xóa dữ liệu của app: Cài đặt TV → Ứng dụng → Family TV → Xóa dữ liệu (hoặc `adb shell pm clear com.familytv.app`, hoặc gỡ rồi cài lại). Thao tác này xóa PIN, lịch sử, yêu thích, giới hạn và nguồn USB đã chọn; video trên USB không bị ảnh hưởng.

**Giới hạn của app:** Family TV chỉ kiểm soát nội dung **bên trong app**. App **không khóa được toàn bộ TV** và không chặn nút Home; trẻ vẫn có thể mở ứng dụng khác. Nếu cần, hãy dùng thêm hồ sơ hạn chế hoặc tính năng kiểm soát của phụ huynh trên TV.

## 6. Giới hạn thời gian (chế độ Trẻ em)

- Mặc định 30 phút mỗi ngày. Có thể đặt trong `config.json` hoặc trong Khu vực bố mẹ (Theo config / Tắt / 15–120 phút). Tại màn hình nghỉ hoặc trong Khu vực bố mẹ có thể thêm 15/30/60 phút, hoặc đặt lại thời gian hôm nay (cần PIN).
- Chỉ tính thời gian **video thực sự đang phát** ở chế độ Trẻ em. Lúc tạm dừng, lúc chọn video, lúc đang hỏi "xem tiếp" và chế độ Người lớn/Tất cả đều không bị tính.
- Thời gian được đo theo từng nhịp bằng đồng hồ đơn điệu, mỗi nhịp tối đa 5 giây, nên sau sleep/resume không bị cộng dồn sai và không bao giờ âm. Thời gian đã dùng được lưu xuống bộ nhớ khoảng mỗi 10 giây, khi tạm dừng/thoát, và ngay lúc hết giờ.
- Khi hết giờ: video dừng và màn hình nghỉ hiện ra. Thoát rồi mở lại app (kể cả force-stop) vẫn ở trạng thái hết giờ.
- **Quy tắc đặt lại hằng ngày:** hạn mức tự đặt lại lúc **0:00 theo giờ TV**. Nếu đồng hồ TV bị chỉnh **lùi**, thời gian đã dùng được giữ nguyên. Hạn chế: app không có nguồn giờ tin cậy (vì không dùng Internet), nên nếu ai đó chỉnh đồng hồ TV **tiến** sang ngày mới thì hạn mức sẽ bị đặt lại. Nên bật "Ngày giờ tự động" trên TV.

## 7. Dữ liệu và độ ổn định

- Dữ liệu riêng của app: nguồn USB và quyền truy cập, cấu hình hợp lệ gần nhất, PIN (đã băm), giới hạn, thời gian đã dùng (DataStore); chỉ mục video, tiến độ xem, yêu thích (Room); thumbnail (cache).
- **Định danh video** = SHA-256 của (nguồn + đường dẫn tương đối). Nguồn chứa mã ổ USB (UUID), nên hai USB có đường dẫn giống hệt nhau vẫn không bị ghép nhầm tiến độ.
- **Chỉ mục:** app quét lúc khởi động, khi cắm lại USB, hoặc khi bố mẹ bấm "Quét lại thư viện". Các màn hình chỉ đọc chỉ mục, không quét lại. Sau một lần quét **trọn vẹn**, video đã bị xóa cùng tiến độ và yêu thích của chúng sẽ được gỡ. Nếu quét dở dang (ví dụ USB bị rút giữa chừng), app chỉ thêm/cập nhật, **không xóa** gì.
- **Rút USB khi đang phát:** video dừng, hiện "USB đã ngắt kết nối". Trang chủ hiện "Chưa thấy USB" và không hiển thị nội dung. Cắm lại thì thư viện tự phục hồi.
- **Mất quyền hoặc URI không còn hợp lệ:** hiện "Mất quyền đọc USB" và hướng dẫn chọn lại thư mục.

---

## 8. Kiểm thử: đã làm, chưa làm, hạn chế

### Đã chạy thực sự

**56 unit test (JVM), tất cả đều qua.** Lệnh: `gradlew :app:testDebugUnitTest`. Các nhóm:
- `ConfigParserTest` (14): JSON hợp lệ/sai, BOM, schemaVersion, id trùng, audience sai, đường dẫn tuyệt đối/`..`/`content://`/ổ đĩa, chuẩn hóa `\`, kiểu sai, cảnh báo chồng lấn.
- `ConfigManagerTest` (6): bản cập nhật lỗi giữ bản hợp lệ cũ, khôi phục sau khởi động lại, không dùng config của USB khác, thư mục thiếu chỉ là cảnh báo.
- `ModeViewTest` (12): quy tắc kids/adults/all; không lộ adults qua tìm kiếm (kể cả tìm theo tên danh mục adults), yêu thích, xem tiếp, lịch sử, mọi loại hàng đợi phát, mở theo ID, hàng "Đề xuất" và kệ "Video tiếp theo"; xung đột kids+adults; thứ tự đề xuất xen kẽ danh mục.
- `LibraryScannerTest` (6): chỉ quét thư mục khai báo, bỏ file ẩn/`._`, ảnh cạnh video, không đệ quy, hợp nhất audience (khác hoa/thường), ID ổn định theo USB, quét dở dang, đường dẫn chỉ phân giải trong thư mục gốc.
- `VietnameseTextTest` (5): tìm không dấu (kể cả đ/d), tên file NFD (macOS), sắp xếp tự nhiên.
- `WatchTimeTest` (6): lưu và phục hồi thời gian, hết giờ sau khởi động lại, thêm giờ, qua ngày mới, đồng hồ chỉnh lùi, nhịp âm/quá lớn.
- `PinTest` (7): PBKDF2 khớp với cài đặt chuẩn của JVM, PIN yếu, khóa tăng dần, khóa vẫn còn sau khởi động lại, không có PIN mặc định, đổi PIN, cần PIN còn hiệu lực để rời chế độ Trẻ em.

**Build:** `assembleDebug` thành công; Android Lint 0 lỗi (còn 22 cảnh báo, chủ yếu là "có phiên bản thư viện mới hơn" vì phiên bản được ghim có chủ đích, xem mục 9).

**Chạy thử trên emulator Android TV 9 (API 28, x86, 1080p)**, điều khiển bằng phím D-pad giả lập. "USB" là một volume rời giả lập bằng `sm set-virtual-disk` (FAT, gắn tại `/storage/XXXX-XXXX`, có broadcast mount/unmount như USB thật). Các kịch bản đã chạy:
- Tạo PIN (PIN yếu bị từ chối), mở khóa Khu vực bố mẹ.
- **Trình chọn thư mục hệ thống không có** trên image này: DocumentsUI có cài nhưng không có activity nào xử lý `OPEN_DOCUMENT_TREE`. App báo đúng hạn chế này. Phương án **"Đọc USB trực tiếp"** hoạt động: xin quyền đọc, tìm thấy ổ và thư mục FamilyTV, đọc `config.json`, quét được 8 video (bỏ đúng thư mục không khai báo và file `._`).
- Trang chủ chế độ Trẻ em: chỉ có hàng kids và all; thumbnail từ ảnh cạnh video và từ khung hình trích ra; thời lượng; sắp xếp tự nhiên; thanh tiến độ đã xem.
- Phát video, tua, "Đã xem xong", video tiếp theo, hỏi "Xem tiếp từ 0:18 / Xem từ đầu", quay lại giữ đúng thẻ đang chọn và vị trí cuộn.
- Video hỏng: hiện thông báo lỗi, app không crash.
- Tìm "da lat" ra "Du lịch Đà Lạt"; tìm "phim" ở chế độ Trẻ em ra "Không tìm thấy"; bàn phím hệ thống của TV dùng được.
- Đổi sang Người lớn bằng PIN: chỉ còn hàng adults và all, hàng "Xem tiếp" cũng được lọc. Bấm Home rồi mở lại: tự về Trẻ em.
- **Rút USB khi đang phát:** dừng và báo "USB đã ngắt kết nối". Trang chủ hiện "Chưa thấy USB". **Cắm lại** thì tự phục hồi.
- Đổi `config.json` (giới hạn 1 phút) rồi cắm lại USB: config mới được áp dụng. Hết giờ giữa video: dừng và hiện màn nghỉ. **Force-stop rồi mở lại: vẫn hết giờ.** Nhập sai PIN 5 lần thì khóa 30 giây có đếm ngược; sau đó nhập đúng PIN và thêm 15 phút thì xem tiếp được.
- `config.json` lỗi (audience sai, `../../`, id trùng): giữ bản cũ, hiện chip cảnh báo, Khu vực bố mẹ liệt kê từng lỗi.
- TV sleep/wake khi đang phát: trình phát được giải phóng, khi thức dậy hiện lại ở trạng thái tạm dừng kèm thanh điều khiển.
- Yêu thích (giữ OK), trang Danh mục.
- Logcat: không có crash hay ANR nào của `com.familytv.app`.

Các lỗi phát hiện qua emulator và **đã sửa**: focus rơi vào menu khi nội dung đổi; vào menu không focus mục đang chọn; thanh điều khiển không hiện sau khi TV thức dậy; nhập PIN bằng phím số mà focus không tự chuyển sang OK; thanh "đã xem" khó thấy trên ảnh cùng màu.

**Trên TV Sony thật** (cài qua adb Wi-Fi, ngày 27/10/2026):
- TV báo `Sony BRAVIA VU1`, **Android 11 (API 30)**, chip ARM 32-bit, 1920×1080. TV **không có trình chọn thư mục** (không activity nào xử lý `OPEN_DOCUMENT_TREE`), nên dùng luồng "Đọc USB trực tiếp" sau khi cấp quyền bằng `adb shell appops set --uid com.familytv.app MANAGE_EXTERNAL_STORAGE allow`.
- App cài và mở được, không crash. Lần đầu mở mất khoảng 10 giây (bản debug).
- Người dùng báo **chỉ nghe tiếng, không có hình**. Nguyên nhân: 2 video trên USB mã hóa **AV1 1080p**, còn TV chỉ có bộ giải mã H.264/H.265/VP8/VP9/MPEG-2/4. App đã được sửa để **báo lỗi rõ ràng** thay vì phát mỗi tiếng (đã kiểm chứng với chính file AV1 đó trên emulator). Bản chuyển sang H.264 sẽ phát được bằng bộ giải mã phần cứng của TV.

### Chưa kiểm thử

- Trên TV Sony thật mới chỉ thử cài, mở app và phát 2 video AV1 (do người dùng thao tác). Các mục khác trong checklist (mục 9), như remote Sony, rút/cắm USB thật, sleep/resume, chưa được chạy trên TV.
- Trình chọn thư mục SAF (luồng chính) **chưa được chạy thử**, vì cả emulator lẫn TV Sony đều không có trình chọn. Mã đã được viết theo tài liệu và build được.
- Android 10 (dùng `requestLegacyExternalStorage` cho luồng đọc trực tiếp) chưa chạy trên thiết bị nào.
- Thư viện lớn (hàng nghìn video), USB NTFS, phím media trên remote thật, bàn phím tiếng Việt có dấu trên TV.
- Không có test giao diện tự động (instrumented/UI test). Việc kiểm tra UI ở trên là chạy tay trên emulator.

### Hạn chế còn lại

- Trên TV không có trình chọn thư mục mà chạy Android 11+, luồng dự phòng cần cấp quyền qua adb.
- Hạn mức ngày dựa trên đồng hồ TV (xem mục 6).
- App không khóa được toàn bộ TV (xem mục 5).
- Khả năng phát video phụ thuộc codec của TV.
- Chưa có tìm kiếm bằng giọng nói (đúng phạm vi phiên bản đầu).
- Giao diện chỉ có tiếng Việt; chuỗi giao diện nằm trực tiếp trong mã Kotlin, chưa tách ra `strings.xml`.

## 9. Checklist kiểm thử trên TV thật

Đánh dấu khi đã thử trên Sony KD-49X7500H:

- [ ] Ghi lại phiên bản Android của TV (Cài đặt → Giới thiệu).
- [ ] Cài APK, biểu tượng/banner Family TV hiện trong launcher, mở được bằng remote.
- [ ] Tạo PIN; PIN yếu bị từ chối.
- [ ] **Quyền USB:** thử "Chọn thư mục FamilyTV". Ghi lại TV có trình chọn hay không, trình chọn có hiện USB hay không. Nếu không có, thử "Đọc USB trực tiếp".
- [ ] Tắt/mở TV hoặc khởi động lại: app vẫn đọc được USB mà không phải chọn lại (quyền đã được lưu).
- [ ] **Remote:** di chuyển bốn hướng, OK, Back, giữ OK (yêu thích), phím số khi nhập PIN, phím media khi phát; ô focus nhìn rõ và không bị cắt.
- [ ] Quay lại từ trình phát vẫn giữ đúng thẻ đang chọn.
- [ ] Tìm kiếm bằng bàn phím của TV, gõ có dấu và không dấu.
- [ ] **Rút USB khi đang phát:** dừng, báo "USB đã ngắt kết nối", không crash. **Cắm lại:** thư viện tự phục hồi.
- [ ] Cắm một USB khác có cùng cấu trúc thư mục: tiến độ xem không bị ghép nhầm.
- [ ] **Sleep/resume:** tắt TV bằng remote khi đang phát, bật lại: video tạm dừng, đang ở chế độ Trẻ em.
- [ ] **Video lỗi/codec:** thử MP4 H.264, MKV, HEVC (H.265), AVI, một file hỏng; ghi lại file nào phát được.
- [ ] **Đổi chế độ:** Người lớn/Tất cả cần PIN; bấm Home rồi mở lại thì về Trẻ em; nội dung adults không xuất hiện ở Trẻ em (Trang chủ, tìm kiếm, yêu thích, xem tiếp).
- [ ] **Hết giờ:** đặt giới hạn ngắn, xem đến hết giờ: màn nghỉ hiện; tắt app rồi mở lại vẫn hết giờ; thêm giờ bằng PIN.
- [ ] Nhập sai PIN 5 lần thì bị khóa tạm thời.
- [ ] Sửa `config.json` cho sai cú pháp: app giữ bản cũ và báo lỗi trong Khu vực bố mẹ.
- [ ] Bấm "Quét lại thư viện" sau khi chép thêm hoặc xóa video.

---

## 10. Mở và build mã nguồn

**Android Studio** (bản ổn định mới, có hỗ trợ AGP 8.13): File → Open → chọn thư mục này, chờ Gradle sync, rồi Run hoặc Build → Build APK(s).

**Dòng lệnh** (cần JDK 17–21 và Android SDK platform 36):

```
gradlew.bat :app:assembleDebug          # Windows
./gradlew :app:assembleDebug            # macOS/Linux
gradlew.bat :app:testDebugUnitTest      # unit test
gradlew.bat :app:lintDebug              # lint
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. File `local.properties` trỏ tới Android SDK của máy hiện tại; Android Studio sẽ tự tạo lại file này trên máy khác.

**Phiên bản** (khai báo trong `gradle/libs.versions.toml`, đã kiểm tra `aar-metadata` trên Google Maven/Maven Central ngày 27/10/2026): AGP 8.13.2, Gradle 8.14.3, Kotlin 2.2.21, KSP 2.2.21-2.0.5, Compose BOM 2026.06.01 (Compose 1.11.4), tv-material 1.1.0, Media3 1.11.1, Room 2.8.5, DataStore 1.2.1, activity-compose 1.13.0, core-ktx 1.18.0, lifecycle 2.10.0, kotlinx-serialization-json 1.9.0, coroutines 1.10.2; `compileSdk`/`targetSdk` 36, `minSdk` 23.
Các bản mới hơn (Compose BOM 2026.09, lifecycle 2.11, core 1.19) đòi compileSdk 37 và AGP 9.1, nên chưa nâng.

**Cấu trúc mã** (`app/src/main/java/com/familytv/app/`):

| Gói | Nội dung |
|---|---|
| `core/` | Logic thuần Kotlin, có unit test: `config` (parse/validate, quy tắc đường dẫn, giữ bản hợp lệ), `catalog` (lọc theo chế độ: **mọi màn hình, tìm kiếm, hàng đợi và mở theo ID đều đi qua `ModeView`**), `model` (`ContentPolicy`), `text` (tiếng Việt, sắp xếp tự nhiên), `pin`, `quota`, `session` |
| `data/source` | Đọc USB: `SafFileSystem` (Storage Access Framework), `LocalFileSystem` (đọc trực tiếp), tìm ổ |
| `data/library` | `LibraryScanner`, `LibraryRepository` (USB, config, Room, broadcast mount/unmount), `ThumbnailRepository` |
| `data/db`, `data/settings` | Room (chỉ mục, tiến độ, yêu thích), DataStore |
| `ui/` | Compose for TV: menu, Trang chủ, Tìm kiếm, Danh mục, Yêu thích, Xem tiếp, Khu vực bố mẹ, hộp thoại PIN, màn nghỉ |
| `player/` | `PlayerActivity` + `PlayerViewModel` (Media3 ExoPlayer, hàng đợi, đếm giờ, xử lý mất USB) |
