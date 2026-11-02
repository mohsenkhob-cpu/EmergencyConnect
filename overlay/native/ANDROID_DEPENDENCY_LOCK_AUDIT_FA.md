# فهرست قفل وابستگی‌های OpenVPN 3 برای Android

این سند از فایل‌های همان commit ثابت‌شده upstream استخراج شده است:

- OpenVPN 3: `0abc8313b8fe5f63dbebc10d53094952957845d9`
- vcpkg baseline: `69a1a33b4ba2b21e392c17e215ff637290778a97`
- فایل مرجع وابستگی‌ها: `vcpkg.json`
- فایل مرجع تنظیمات core: `cmake/findcoredeps.cmake`

## وابستگی‌های اعلام‌شده upstream

| بسته | دلیل نیاز / وضعیت |
|---|---|
| Asio | کتابخانه I/O؛ نسخه پین‌شده در port upstream برابر 1.38.2 است و شش patch اختصاصی OpenVPN روی آن اعمال می‌شود. |
| fmt | کتابخانه قالب‌بندی؛ از طریق `find_package(fmt)` و هدف `fmt::fmt` لینک می‌شود. |
| LZ4 | فشرده‌سازی؛ از طریق `find_package(lz4)` و هدف `lz4::lz4` لینک می‌شود. |
| OpenSSL | backend پیش‌فرض TLS/crypto در تنظیمات عادی upstream؛ باید برای ABI اندروید cross-compile شود. |
| jsoncpp | در manifest وجود دارد؛ در مسیرهای خاص از طریق `add_json_library` مصرف می‌شود. |
| xxHash | در manifest وجود دارد؛ وابستگی‌های بعضی قابلیت‌ها/مسیرهای core. |
| tap-windows6 | بسته Windows؛ برای هدف Android نباید ساخته یا لینک شود. |
| gtest | وابستگی host برای تست‌ها؛ در کتابخانه اپ نهایی نباید وارد شود. |

## نکته مهم درباره Asio

نباید Asio عمومی سیستم یا نسخه بدون patch را جایگزین کرد. پورت upstream در همان commit:
- Asio را روی نسخه 1.38.2 قفل می‌کند.
- شش patch مشخص در مسیر `deps/vcpkg-ports/asio` اعمال می‌کند.
- حالت standalone را فعال نگه می‌دارد تا وابستگی Boost لازم نباشد.

## محدودیت فعلی ساخت Android

فایل `cmake/findcoredeps.cmake` پلتفرم‌های Apple، Windows و مسیر عمومی Linux را تشخیص می‌دهد؛ Android را به‌عنوان پلتفرم مستقل تعریف نمی‌کند. همچنین در مسیر غیر Windows از مسیرهای dependency مخصوص Linux و `pkg-config` برای TLS استفاده می‌کند. این موارد نباید به شکل ضمنی برای NDK فرض شوند.

بنابراین مرحله بعد باید یک dependency superbuild مخصوص Android داشته باشد که:
1. vcpkg baseline و نسخه/patch هر port را ثابت کند.
2. فقط ABI هدف (در گام اول `arm64-v8a`) را با NDK بسازد.
3. Asio patchشده را به‌صورت header-only نصب کند.
4. LZ4، fmt و OpenSSL را به‌صورت static برای همان ABI تولید کند.
5. مسیر include/library و targetهای CMake را بدون اتکا به pkg-config میزبان صادر کند.
6. بسته‌های Windows و تست‌های host را از dependency graph هدف Android حذف کند.

## دروازه کیفیت

تا وقتی dependency superbuild واقعاً در CI اجرا نشده و فایل‌های static برای Android تولید نشده‌اند، نباید target نهایی `openvpn3_jni` را به‌عنوان کتابخانه قابل اتصال تعریف کرد. ساخت یک JNI stub یا کتابخانه خالی، معیار قبولی نیست.

**وضعیت:** این سند ممیزی وابستگی‌هاست؛ هنوز هیچ‌یک از کتابخانه‌های native اندروید در این مخزن ساخته نشده‌اند.
