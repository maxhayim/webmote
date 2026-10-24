package io.github.maxhayim.webmote;

import android.accessibilityservice.AccessibilityService;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The PIN in a corner of the TV while a phone or computer that isn't paired has the page open, so nobody has to open
 * Webmote on the TV first. It takes no focus and no keys, so whatever is playing carries on underneath; it goes when
 * that device pairs, or after a minute.
 */
final class PinCard {
    private static final long SHOW_MS = 60_000;

    private final AccessibilityService service;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable hide = this::remove;
    private LinearLayout card; // main thread only
    private TextView pin;

    PinCard(AccessibilityService service) {
        this.service = service;
    }

    void show(String code) {
        main.post(() -> {
            if (card == null) add();
            pin.setText(code);
            main.removeCallbacks(hide);
            main.postDelayed(hide, SHOW_MS);
        });
    }

    void hide() {
        main.post(hide);
    }

    private void add() {
        card = new LinearLayout(service);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(px(24), px(18), px(24), px(20));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF20D1117);
        bg.setCornerRadius(px(18));
        bg.setStroke(px(1), 0xFF2A3040);
        card.setBackground(bg);
        card.addView(text(service.getString(R.string.app_name), 15, 0xFF8FA8FF));
        card.addView(text("A phone or computer wants to pair. Type this PIN there:", 14, 0xFF8B949E));
        pin = text("", 40, 0xFFFFFFFF);
        pin.setLetterSpacing(0.25f);
        card.addView(pin);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(px(380), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = px(40);
        lp.y = px(32);
        service.getSystemService(WindowManager.class).addView(card, lp);
    }

    private void remove() {
        main.removeCallbacks(hide);
        if (card == null) return;
        service.getSystemService(WindowManager.class).removeView(card);
        card = null;
        pin = null;
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(service);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private int px(int dp) {
        return Math.round(dp * service.getResources().getDisplayMetrics().density);
    }
}
