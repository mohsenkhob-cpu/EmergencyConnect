package com.rahyab.next;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setGravity(Gravity.CENTER);
        v.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        v.setPadding(dp(8), dp(5), dp(8), dp(5));
        return v;
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(9, 18, 35));
        getWindow().setNavigationBarColor(Color.rgb(9, 18, 35));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(9, 18, 35));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(22), dp(26), dp(22), dp(24));
        page.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        scroll.addView(page);

        TextView brand = label("راه‌یاب", 29, Color.WHITE, true);
        brand.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        page.addView(brand);
        TextView subtitle = label("اتصال ساده، انتخاب هوشمند", 14, Color.rgb(164, 181, 205), false);
        subtitle.setGravity(Gravity.RIGHT);
        page.addView(subtitle);

        TextView status = label("هنوز به سروری متصل نیستید", 16, Color.rgb(220, 230, 245), true);
        status.setBackground(shape(Color.rgb(22, 36, 59), 18));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, dp(58));
        statusParams.topMargin = dp(30);
        page.addView(status, statusParams);

        TextView orb = label("⏻", 54, Color.WHITE, true);
        orb.setBackground(shape(Color.rgb(27, 112, 108), 100));
        LinearLayout.LayoutParams orbParams = new LinearLayout.LayoutParams(dp(154), dp(154));
        orbParams.gravity = Gravity.CENTER_HORIZONTAL;
        orbParams.topMargin = dp(32);
        orbParams.bottomMargin = dp(12);
        page.addView(orb, orbParams);

        TextView helper = label("برای شروع، سرور قابل استفاده لازم است", 14, Color.rgb(164, 181, 205), false);
        page.addView(helper);

        Button connect = new Button(this);
        connect.setText("اتصال هوشمند");
        connect.setTextSize(17);
        connect.setTextColor(Color.WHITE);
        connect.setAllCaps(false);
        connect.setBackground(shape(Color.rgb(31, 151, 137), 18));
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-1, dp(58));
        buttonParams.topMargin = dp(24);
        page.addView(connect, buttonParams);
        connect.setOnClickListener(v -> status.setText("موتور اتصال هنوز راه‌اندازی نشده است"));

        TextView serverTitle = label("سرور انتخاب‌شده", 15, Color.WHITE, true);
        serverTitle.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.topMargin = dp(30);
        page.addView(serverTitle, titleParams);

        TextView server = label("انتخاب خودکار سرور\nفعلاً سروری به فهرست اضافه نشده", 14, Color.rgb(190, 204, 224), false);
        server.setBackground(shape(Color.rgb(22, 36, 59), 18));
        LinearLayout.LayoutParams serverParams = new LinearLayout.LayoutParams(-1, dp(82));
        serverParams.topMargin = dp(10);
        page.addView(server, serverParams);

        TextView note = label("نسخه آزمایشی رابط کاربری — اتصال VPN هنوز فعال نیست", 12, Color.rgb(235, 190, 115), false);
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(-1, -2);
        noteParams.topMargin = dp(24);
        page.addView(note, noteParams);

        setContentView(scroll);
    }
}
