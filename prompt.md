Bạn là lập trình viên Android TV có kinh nghiệm. Hãy xây dựng một ứng dụng Android TV hoàn chỉnh tên **Family TV**, dùng trong gia đình để trẻ em và người lớn xem các video bố mẹ chuẩn bị sẵn trên USB.

Tôi cần bạn trực tiếp tạo mã nguồn, cấu hình build và tài liệu sử dụng; không chỉ tư vấn kiến trúc hoặc đưa các đoạn code rời rạc.

## 1. Mục tiêu

Ứng dụng có trải nghiệm quen thuộc như YouTube trên TV: menu bên trái, các hàng video theo chủ đề, ảnh đại diện, tìm kiếm và trình phát toàn màn hình.

Toàn bộ nội dung lấy từ USB, hoạt động offline. Bố mẹ kiểm soát những thư mục được phép hiển thị bằng một file JSON.

Thiết bị ưu tiên kiểm thử là Sony KD-49X7500H. Không tự giả định phiên bản Android đang cài; hãy hướng dẫn kiểm tra khi cần.

Ưu tiên: đọc USB ổn định, thao tác remote thuận tiện, lọc nội dung trẻ em chính xác, rồi đến hiệu ứng giao diện.

## 2. Công nghệ

- Kotlin.
- Jetpack Compose for TV.
- Media3/ExoPlayer.
- DataStore cho cài đặt; dùng Room nếu cần lưu chỉ mục video và tiến độ xem.
- Coroutines cho tác vụ nền.
- Dùng các phiên bản thư viện ổn định, tương thích với nhau; kiểm tra tài liệu chính thức trước khi chọn phiên bản.
- Không dùng WebView để bọc một website.
- Không cần backend, tài khoản đăng nhập hoặc dịch vụ trả phí.
- Không khai báo quyền Internet, không quảng cáo, không analytics.
- Không tích hợp YouTube API hoặc phát video trực tuyến.
- Có cấu hình launcher cho Android TV và hỗ trợ remote; không bắt buộc màn hình cảm ứng.

## 3. USB và cấu hình thư viện

Cấu trúc mẫu:

FamilyTV/
  config.json
  videos/
    kids/
      songs/
      english/
    adults/
    shared/

Lần đầu mở app, bố mẹ chọn thư mục FamilyTV trên USB và cấp quyền truy cập. Dùng Storage Access Framework và lưu quyền URI nếu thiết bị hỗ trợ.

Các đường dẫn trong JSON là đường dẫn tương đối tính từ thư mục đã chọn. Không hardcode đường dẫn /storage/XXXX-XXXX.

Phải kiểm tra khả năng chọn thư mục trên Android TV thực tế. Nếu TV không có trình chọn thư mục phù hợp, báo rõ hạn chế và đề xuất phương án khả thi; không giả vờ rằng việc đọc USB đã hoạt động.

App chỉ hiển thị video thuộc những thư mục được khai báo trong JSON. Không tự quét toàn bộ USB để đưa nội dung khác vào thư viện.

Ví dụ config.json:

{
  "schemaVersion": 1,
  "appName": "Family TV",
  "defaultMode": "kids",
  "settings": {
    "autoplay": false,
    "resumePlayback": true,
    "sessionLimitMinutes": 30
  },
  "folders": [
    {
      "id": "kids-songs",
      "name": "Các bài hát thiếu nhi",
      "path": "videos/kids/songs",
      "audience": "kids",
      "recursive": true,
      "sortBy": "filename"
    },
    {
      "id": "kids-english",
      "name": "Tiếng Anh cho bé",
      "path": "videos/kids/english",
      "audience": "kids",
      "recursive": true,
      "sortBy": "filename"
    },
    {
      "id": "adults",
      "name": "Video dành cho bố mẹ",
      "path": "videos/adults",
      "audience": "adults",
      "recursive": true,
      "sortBy": "filename"
    },
    {
      "id": "family",
      "name": "Cả nhà cùng xem",
      "path": "videos/shared",
      "audience": "all",
      "recursive": true,
      "sortBy": "filename"
    }
  ]
}

Quy tắc:

- kids: nội dung trẻ em.
- adults: nội dung người lớn.
- all: nội dung phù hợp cho cả hai nhóm.
- Chế độ Trẻ em hiển thị kids + all.
- Chế độ Người lớn hiển thị adults + all.
- Chế độ Tất cả hiển thị mọi nhóm.

Kiểm tra cấu hình trước khi áp dụng:

- schemaVersion không hỗ trợ.
- ID bị trùng.
- audience không hợp lệ.
- Đường dẫn tuyệt đối, chứa thành phần “..” hoặc thoát khỏi thư mục được cấp quyền.
- Thư mục không tồn tại.
- JSON sai định dạng.

Nếu cập nhật JSON bị lỗi, giữ bản cấu hình hợp lệ gần nhất và hiển thị lỗi trong khu vực quản lý của bố mẹ. Khi chưa có cấu hình hợp lệ, không hiển thị nội dung tùy tiện.

Nếu nhiều mục cấu hình trỏ đến cùng video và có quy tắc audience xung đột, không được làm video adults xuất hiện trong chế độ Trẻ em. Chọn quy tắc an toàn và giải thích trong tài liệu.

## 4. Giao diện

Ngôn ngữ mặc định: tiếng Việt.

Thiết kế nền tối, chữ rõ khi xem từ xa, thumbnail 16:9. Lấy cảm hứng từ bố cục YouTube TV nhưng dùng tên, logo và tài nguyên riêng.

Menu trái:

- Trang chủ.
- Tìm kiếm.
- Danh mục.
- Yêu thích.
- Xem tiếp.
- Khu vực bố mẹ.

Trang chủ gồm:

- Chế độ hiện tại.
- Hàng Xem tiếp.
- Các hàng video theo tên thư mục trong JSON.
- Trạng thái USB khi cần.

Thẻ video có ảnh đại diện, tiêu đề, thời lượng nếu đọc được và thanh tiến độ đã xem.

Điều khiển hoàn toàn bằng remote:

- Bốn phím hướng để di chuyển.
- OK để chọn.
- Back để quay lại.
- Ô đang focus có viền hoặc hiệu ứng đủ rõ, không bị cắt khi phóng to.
- Quay lại từ trình phát phải giữ vị trí danh sách và video đang chọn.
- Xử lý tốt danh sách rỗng, dài hoặc có nhiều hàng.
- Không có nút chỉ thao tác được bằng chuột.

## 5. Thông tin video và thumbnail

- Mặc định lấy tiêu đề từ tên file, bỏ phần mở rộng.
- Hỗ trợ tên file tiếng Việt.
- Cho phép ảnh cùng tên cạnh video, ví dụ:
  “Bai hat ABC.mp4”
  “Bai hat ABC.jpg”
- Ưu tiên ảnh có sẵn; nếu thiếu, thử trích khung hình và lưu cache trong bộ nhớ riêng của app.
- Nếu không tạo được ảnh, dùng placeholder.
- Không trích thumbnail hàng loạt trên luồng giao diện.
- Giới hạn dung lượng cache và số tác vụ đồng thời.
- Không ghi hoặc sửa file trên USB.

Tạo chỉ mục để không phải quét lại toàn bộ thư viện mỗi lần vào màn hình. Có nút “Quét lại thư viện”; cập nhật chỉ mục khi nguồn thay đổi và xóa dữ liệu tham chiếu đã lỗi thời.

## 6. Tìm kiếm

- Chỉ tìm trong nội dung được phép của chế độ hiện tại.
- Tìm theo tiêu đề video và tên danh mục.
- Không phân biệt hoa/thường.
- Hỗ trợ tiếng Việt có dấu và không dấu, bao gồm đ/d.
- Nhập bằng bàn phím hệ thống phù hợp với TV và remote.
- Có trạng thái không tìm thấy.
- Không tìm trên Internet.
- Tìm kiếm giọng nói chưa thuộc phiên bản đầu.

## 7. Trình phát

- Toàn màn hình.
- Phát/tạm dừng.
- Tua tới/lùi.
- Thanh tiến độ.
- Hiển thị tiêu đề và thời lượng.
- Video trước/tiếp theo trong danh sách hiện tại.
- Hỗ trợ nút media trên remote nếu có.
- Lưu tiến độ và tiếp tục xem theo cài đặt.
- Có lựa chọn xem từ đầu.
- Tùy chọn phát tuần tự, ngẫu nhiên và lặp lại; mặc định tắt tự phát tiếp.

Danh sách phát tiếp phải tuân thủ chế độ hiện tại. Khi đổi chế độ, hủy hoặc tạo lại hàng đợi để không phát nhầm nội dung.

Nếu rút USB khi đang phát, dừng có kiểm soát, báo “USB đã ngắt kết nối”, không crash hoặc tự chuyển sang nguồn khác.

Không hứa phát mọi file MP4/MKV: khả năng phát phụ thuộc codec và phần cứng TV. Hiển thị lỗi dễ hiểu khi định dạng không được hỗ trợ.

## 8. Chế độ trẻ em và PIN

- Mỗi lần khởi động mới mặc định vào Trẻ em.
- Chuyển sang Người lớn hoặc Tất cả cần PIN.
- Khu vực bố mẹ, đổi nguồn USB, quét lại cấu hình và thay đổi giới hạn cần PIN.
- PIN được thiết lập trong app, không nằm trong JSON.
- Không lưu hoặc ghi log PIN dạng rõ; lưu giá trị kiểm chứng có salt bằng cơ chế phù hợp.
- Có giới hạn thử PIN và thời gian chờ khi nhập sai nhiều lần.
- Không có PIN mặc định hoặc mã bí mật vượt khóa.
- Khi chưa thiết lập PIN, chưa cho phép vào nội dung adults.
- Có quy trình đổi PIN bằng PIN hiện tại; mô tả rõ cách đặt lại dữ liệu nếu quên PIN.

Áp dụng lọc nội dung thống nhất tại tầng dữ liệu cho:

- Trang chủ.
- Danh mục.
- Tìm kiếm.
- Yêu thích.
- Xem tiếp.
- Lịch sử.
- Hàng đợi phát.
- Mở video trực tiếp bằng ID hoặc intent.

Không chỉ ẩn nội dung adults trên giao diện rồi vẫn cho phép phát bằng đường khác.

App không được tự nhận là có thể khóa toàn bộ TV. Không chặn nút Home bằng thủ thuật; tài liệu cần nói rõ giới hạn kiểm soát trong phạm vi ứng dụng.

## 9. Giới hạn thời gian

- Giới hạn thời gian xem cho chế độ Trẻ em, mặc định 30 phút.
- Bố mẹ có thể thay đổi hoặc tắt.
- Đếm thời gian video thực sự phát, không tính thời gian pause hoặc duyệt danh sách.
- Khi hết giờ, dừng phát và hiển thị màn hình nghỉ.
- Cần PIN để cấp thêm thời gian.
- Thoát và mở lại app không được tự động xóa thời gian đã dùng hoặc bỏ trạng thái hết giờ.
- Đề xuất và ghi rõ quy tắc đặt lại hạn mức hằng ngày; dùng đồng hồ đơn điệu khi đo thời lượng, tránh bộ đếm âm hoặc cộng sai sau sleep/resume.

## 10. Dữ liệu cục bộ và độ ổn định

Lưu trong dữ liệu riêng của app:

- Nguồn USB và quyền truy cập.
- Cấu hình hợp lệ gần nhất.
- Chỉ mục video.
- Yêu thích.
- Tiến độ xem.
- Cài đặt và dữ liệu kiểm chứng PIN.
- Thời gian xem đã sử dụng.

Video cần có định danh ổn định trong cùng nguồn thư viện; tránh dùng vị trí trong danh sách làm ID. Không ghép nhầm tiến độ giữa hai USB có đường dẫn giống nhau.

Xử lý rõ các trường hợp:

- Chưa cắm USB.
- Mất quyền đọc.
- USB được cắm lại nhưng URI không còn hợp lệ.
- JSON lỗi.
- Thư mục trống hoặc bị xóa.
- Video bị xóa hoặc hỏng.
- Codec không hỗ trợ.
- Thiếu dung lượng lưu cache.

Không làm treo app khi quét thư viện lớn. Giải phóng tài nguyên trình phát đúng vòng đời, xử lý pause/resume và TV sleep.

## 11. Cách triển khai

Trước tiên đọc dự án hiện có. Nếu thư mục trống, khởi tạo dự án Android TV mới.

Đưa ra kế hoạch ngắn rồi bắt đầu viết code ngay. Tự quyết định các chi tiết thông thường; chỉ hỏi khi có thông tin thật sự chặn triển khai.

Triển khai theo các mốc:

1. Build được app tối thiểu, chọn USB/thư mục, đọc JSON, liệt kê và phát một video bằng remote.
2. Hoàn thiện giao diện, tìm kiếm, thumbnail và chỉ mục.
3. Hoàn thiện chế độ xem, PIN, lọc dữ liệu và giới hạn thời gian.
4. Kiểm thử, sửa lỗi, tạo APK và tài liệu.

Xác minh luồng đọc USB sớm trước khi đầu tư nhiều vào hiệu ứng giao diện.

Không dừng sau khi đưa kế hoạch. Không dùng dữ liệu giả để thay cho chức năng đọc USB. Nếu cần dữ liệu demo, phải tách rõ khỏi luồng sử dụng thật.

## 12. Kiểm thử và bàn giao

Viết kiểm thử có ý nghĩa cho:

- Parse và validation JSON.
- Quy tắc kids/adults/all.
- Không lộ nội dung adults qua tìm kiếm, yêu thích, xem tiếp hoặc hàng đợi.
- Chặn đường dẫn vượt phạm vi thư viện.
- Tìm tiếng Việt không dấu.
- Lưu và phục hồi giới hạn thời gian.
- Cấu hình cập nhật lỗi không làm mất bản hợp lệ trước đó.

Build và chạy các kiểm thử trong môi trường hiện có. Không tuyên bố đã kiểm thử trên Sony TV nếu chưa thực sự làm.

Bàn giao:

- Mã nguồn dự án hoàn chỉnh.
- config.json mẫu.
- Hướng dẫn chuẩn bị USB và đặt thumbnail.
- Hướng dẫn mở và build bằng Android Studio.
- APK debug nếu môi trường build được; chỉ rõ đường dẫn file.
- Hướng dẫn cài APK lên Android TV, mở app và cấp quyền lần đầu.
- Checklist kiểm thử trên TV thật: remote, cấp quyền USB, rút/cắm lại USB, sleep/resume, video lỗi, đổi chế độ và hết giờ.
- Liệt kê chính xác phần đã chạy kiểm thử, phần chưa kiểm thử và hạn chế còn lại.

Nếu môi trường thiếu Android SDK hoặc không thể build, hoàn thành tối đa mã nguồn và cung cấp lệnh cùng lỗi cụ thể; không khẳng định APK đã tạo thành công.

Bắt đầu bằng việc kiểm tra môi trường và thực hiện mốc 1.