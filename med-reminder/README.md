# همراه داروی من
یک اپ خصوصی فارسی و آفلاین برای ثبت مصرف واقعی دارو، شرح حال، زنگ و گزارش هفتگی.
هیچ دوزی پیشنهاد نمی‌دهد؛ زمان‌ها را فقط براساس دستور پزشک وارد کنید.
Build: cd med-reminder && gradle :app:assembleDebug
APK: app/build/outputs/apk/debug/app-debug.apk
Needs notification and exact alarm permissions on Android 12+.
Alarm behavior must be tested on a physical Android device, including battery optimization and silent/DND modes.
No personal medical data are stored in GitHub; data remain locally on the device.
