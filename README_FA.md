# ادغام موتور OpenVPN 3 در اندروید — مرحله توسعه

این پوشه محل کد بومی OpenVPN 3 و لایه JNI پروژه RahYabVPN است. در ZIP فعلی، خود موتور OpenVPN 3 و فایل‌های باینری آن عمداً ادعا نشده‌اند و هنوز وجود ندارند.

## اجزای لازم برای تکمیل اتصال واقعی

1. دریافت سورس رسمی OpenVPN 3 Core از https://github.com/OpenVPN/openvpn3 و ثبت دقیق commit/tag استفاده‌شده.
2. ساخت برای ABIهای Android مورد پشتیبانی (حداقل `arm64-v8a`) با Android NDK و CMake.
3. پیاده‌سازی JNI برای `OpenVPNClient` در `client/ovpncli.hpp`.
4. پیاده‌سازی کلاس مشتق از `openvpn::TunBuilderBase` که آدرس‌ها، routeها، DNS و MTU را با `android.net.VpnService.Builder` برقرار کند.
5. محافظت از سوکت انتقال با `VpnService.protect()` و انتقال descriptor رابط TUN به موتور.
6. مدیریت چرخه عمر، اعلان foreground، مجوز VPN، توقف، خطا و پاک‌سازی منابع.
7. آزمایش اتصال واقعی روی گوشی و چند شبکه؛ سپس افزودن reconnect و failover فقط پس از تأیید قطع واقعی تونل.

## نکته مجوز

مخزن OpenVPN 3 مجوزهای MPL-2.0 و AGPL-3.0-only با استثنای مشخص OpenSSL را اعلام می‌کند. مجوز فایل‌های upstream و تغییرات باید حفظ شود. پیش از انتشار، متن مجوز و تعهدات توزیع نسخه انتخابی را بازبینی کنید.

## وضعیت این بسته

این بسته «اسکلت ادغام» است، نه VPN مستقل آماده استفاده. `OpenVpn3Bridge.isAvailable()` تا زمان ساخت و قرار دادن کتابخانه `libopenvpn3_jni.so` مقدار false می‌دهد. هیچ‌گاه نباید صرف ایجاد VpnService یا بازشدن برنامه به‌عنوان اتصال VPN گزارش شود.
