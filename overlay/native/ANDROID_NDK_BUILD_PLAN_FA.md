# نقشه ساخت موتور OpenVPN 3 برای Android NDK

این سند نتیجه بررسی سورس رسمی OpenVPN 3 در commit ثابت‌شده زیر است:

- Repository: https://github.com/OpenVPN/openvpn3
- Commit: 0abc8313b8fe5f63dbebc10d53094952957845d9
- C++ standard: C++20
- API عمومی کلاینت: client/ovpncli.hpp
- رابط TUN: openvpn/tun/builder/base.hpp

## نتیجه بررسی ساخت

فایل ریشه CMake پروژه رسمی، علاوه بر client، تست‌ها و agentهای دسکتاپ را نیز اضافه می‌کند؛ بنابراین افزودن مستقیم کل ریشه به Gradle Android مناسب نیست.

هدف client/ovpnclilib در client/CMakeLists.txt یک wrapper مبتنی بر SWIG با زبان Python است؛ این هدف کتابخانه JNI اندروید نیست و نباید به‌عنوان موتور Android استفاده شود.

تابع add_corelibrary_dependencies در cmake/findcoredeps.cmake سورس‌های هسته را به خود target مصرف‌کننده اضافه می‌کند، زیرا برخی تنظیمات کامپایل باید برای همه واحدهای ترجمه یکسان باشند. وابستگی‌های اصلی که باید برای ABI اندروید ساخته و به target متصل شوند:
- Asio نسخه/پچ مورد انتظار upstream
- LZ4
- fmt
- TLS backend (OpenSSL یا mbedTLS)
- Android system libraries و pthread در صورت نیاز

فایل findcoredeps.cmake فعلی مسیرهای پلتفرم Linux/macOS/Windows را تشخیص می‌دهد و از pkg-config برای TLS استفاده می‌کند؛ این مسیر را نمی‌توان بدون adapter مخصوص Android مستقیماً در NDK به‌کار برد.

## طرح پیاده‌سازی RahYabVPN

### مرحله A — آماده‌سازی build
1. دریافت سورس upstream با همان commit ثابت، نه شاخه متحرک.
2. تهیه dependency toolchain مخصوص Android ABI (ابتدا arm64-v8a).
3. ساخت Asio، LZ4، fmt و TLS backend برای Android.
4. ایجاد CMake target مستقل `openvpn3_jni` در پروژه RahYabVPN که فقط client API و فایل‌های لازم هسته را کامپایل کند؛ نه SWIG/Python و نه تست‌ها/agentهای دسکتاپ.
5. نگه‌داشتن این target پشت گزینه صریح تا زمانی که تمام dependencyها موجود باشند؛ نبود dependency نباید با یک کتابخانه جعلی یا stub پنهان شود.

### مرحله B — adapter و JNI واقعی
1. پیاده‌سازی کلاس C++ مشتق از `openvpn::TunBuilderBase`.
2. نگاشت همه آدرس‌ها، routeها، DNS، MTU، allow-family و session name به Android VpnService.Builder.
3. انتقال FD تون با مالکیت روشن: پس از تحویل FD به OpenVPN core، فقط مالک نهایی آن را می‌بندد.
4. حفاظت تمام سوکت‌های انتقال با `VpnService.protect(fd)` پیش از اتصال.
5. ایجاد JNI session handle پایدار، callbackهای state/log/error/credentials، لغو و join امن thread و آزادسازی منابع.
6. اجرای چرخه foreground service و مدیریت revoke، توقف کاربر، قطع شبکه و reconnect.

### مرحله C — معیار قبولی
- ساخت واقعی `libopenvpn3_jni.so` برای arm64-v8a و بررسی symbolهای JNI.
- APK شامل کتابخانه native واقعی باشد و در زمان اجرا `System.loadLibrary` موفق شود.
- اتصال TLS موفق به سرور آزمایشی مجاز، ایجاد TUN و عبور ترافیک IPv4/IPv6/DNS.
- اثبات عدم نشت ترافیک خارج از تون، محافظت سوکت‌ها، توقف تمیز و reconnect.
- آزمایش روی دستگاه واقعی؛ صرف موفقیت Gradle یا تولید APK معیار اتصال VPN نیست.

## وضعیت فعلی

این commit فقط نقشه فنی و یافته‌های بررسی upstream را ثبت می‌کند. هنوز target بومی OpenVPN 3 ساخته یا به APK متصل نشده و JNI موجود همچنان قرارداد/اعلان اولیه است. بنابراین نسخه v2 فعلاً VPN مستقل محسوب نمی‌شود.

## مجوز

پیش از انتشار باید انتخاب و رعایت یکی از مسیرهای مجوز upstream (MPL-2.0 یا AGPL-3.0-only با استثنای OpenSSL طبق فایل مجوز upstream) توسط مسئول انتشار بررسی شود.
