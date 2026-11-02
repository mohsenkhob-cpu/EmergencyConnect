# راه‌یاب VPN — بسته توسعه نسخه ۲ (پیش‌نمونه موتور داخلی)

این بسته بر پایه پروژه فعلی RahYabVPN ساخته شده است. برنامه فعلی فهرست VPN Gate را دریافت و پروفایل را به OpenVPN for Android تحویل می‌دهد.

## وضعیت صادقانه نسخه

**این بسته هنوز VPN مستقل نیست و موتور OpenVPN 3 داخل APK قرار نگرفته است.** دو فایل Java به‌عنوان قرارداد و مرز JNI برای ادغام موتور اضافه شده‌اند. تا زمانی که OpenVPN 3 Core، لایه JNI و پیاده‌سازی Android TunBuilder ساخته و آزمایش نشوند، اتصال داخلی، اتصال خودکار و تعویض خودکار سرور فعال نیستند.

- `app/src/main/java/ir/emergencyconnect/engine/VpnEngine.java`: قرارداد موتور اتصال.
- `app/src/main/java/ir/emergencyconnect/engine/OpenVpn3Bridge.java`: مرز JNI؛ در نبود کتابخانه native اتصال را موجود اعلام نمی‌کند.
- `native/openvpn3/README_FA.md`: مراحل فنی لازم برای تکمیل موتور.

## ساخت APK آزمایشی فعلی

1. محتویات این پروژه را در ریشه مخزن GitHub قرار دهید.
2. از تب Actions گردش کار Build Android APK را اجرا کنید.
3. فایل Artifact را دریافت کنید.

APK ساخته‌شده همچنان رفتار نسخه فعلی را دارد و برای اتصال به OpenVPN for Android نیازمند است. این بسته را به‌عنوان نسخه مستقل یا آماده انتشار معرفی نکنید.

## منابع فنی

- OpenVPN 3 Core: https://github.com/OpenVPN/openvpn3
- راهنمای توسعه OpenVPN 3: https://openvpn.github.io/openvpn3/
- راهنمای رسمی Android VpnService: https://developer.android.com/develop/connectivity/vpn

## ایمنی و حریم خصوصی

سرورهای عمومی داوطلبانه ممکن است ناپایدار باشند و اپراتور آن‌ها می‌تواند فراداده یا ترافیک را مشاهده کند. برای بانکداری یا اطلاعات حساس از سرورهای ناشناس استفاده نکنید.
