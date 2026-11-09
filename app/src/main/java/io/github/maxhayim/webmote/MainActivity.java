package io.github.maxhayim.webmote;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The tile and module card open this: the address to open on a phone or computer (and a QR code of it, for a phone's
 * camera), the PIN that pairs it, and whether
 * the remote service is on. New PIN signs every paired device out.
 */
public class MainActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout rows;
    private TextView address, pin, status;
    private ImageView qr;
    private String qrFor;
    /** OK pressed once on New PIN: a second press makes it. */
    private boolean newPinArmed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.HORIZONTAL);
        page.setBackgroundColor(0xFF0D1117);
        page.setPadding(px(56), px(40), px(56), px(32));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(text(getString(R.string.app_name), 28, 0xFFFFFFFF));
        left.addView(text("On a phone or computer on this Wi-Fi or hotspot, open", 16, 0xFF8B949E), lp(px(28)));
        address = text("", 34, 0xFF8FA8FF);
        left.addView(address, lp(px(6)));
        left.addView(text("and pair it with this PIN", 16, 0xFF8B949E), lp(px(26)));
        pin = text("", 48, 0xFFFFFFFF);
        pin.setLetterSpacing(0.25f);
        left.addView(pin, lp(px(4)));
        TextView tip = text("On a phone, add the page to the home screen: it opens full screen, like an app.", 14, 0xFF8B949E);
        left.addView(tip, lp(px(26)));
        page.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.1f));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(px(32), px(44), 0, 0);
        qr = new ImageView(this);
        qr.setVisibility(ImageView.GONE);
        right.addView(qr, new LinearLayout.LayoutParams(px(150), px(150)));
        status = text("", 15, 0xFF8B949E);
        right.addView(status, lp(px(12)));
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        right.addView(rows, lp(px(12)));
        // Scrolls to the focused row where the rows don't all fit.
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(right);
        page.addView(scroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        setContentView(page);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Updater.visible = true;
        build();
        main.postDelayed(tick, 2000);
    }

    @Override
    protected void onPause() {
        Updater.visible = false;
        main.removeCallbacksAndMessages(null);
        super.onPause();
    }

    /** Keeps the update line current while an update runs. */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (updateRow != null && !Updater.status(MainActivity.this).equals(updateShown)) build();
            main.postDelayed(this, 2000);
        }
    };
    private Row updateRow;
    private String updateShown;

    private void build() {
        int focused = rows.indexOfChild(rows.getFocusedChild());
        rows.removeAllViews();
        Auth auth = RemoteService.auth(this);
        RemoteService svc = RemoteService.running;
        String ip = Net.address(this);
        int port = svc == null ? -1 : svc.port();
        if (svc == null || port < 0) showQr(null);
        if (svc == null) {
            address.setText("Remote service is off");
            status.setText("Switch on the remote service below (Accessibility, Webmote) to start the page.");
        } else if (port < 0) {
            address.setText("Starting…");
            main.postDelayed(this::build, 700);
        } else {
            String url = ip == null ? null : "http://" + ip + (port == 80 ? "" : ":" + port);
            address.setText(url == null ? "No network" : url);
            showQr(url);
            int n = auth.phones();
            status.setText(n == 0 ? "No devices paired yet." : n + (n == 1 ? " device paired." : " devices paired."));
        }
        pin.setText(auth.pin());

        Row service = add(new Row(this, "Remote service", "Presses the keys for the page. OK opens Accessibility settings.", () -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (ActivityNotFoundException | SecurityException e) {
                status.setText("This TV has no screen for it. From a computer: see the README.");
            }
        }));
        service.setValue(svc != null ? "On" : "Off");
        Row fresh = add(new Row(this, "New PIN", "Signs out every paired phone and computer.", () -> {
            if (!newPinArmed) {
                newPinArmed = true;
                build();
                main.postDelayed(() -> {
                    newPinArmed = false;
                    build();
                }, 5000);
                return;
            }
            newPinArmed = false;
            auth.newPin();
            build();
        }));
        fresh.setValue(newPinArmed ? "OK again" : "");

        boolean standby = Standby.enabled(this);
        Row power = add(new Row(this, "Standby",
                "Stays on the network while the TV is off, so Home Assistant and the page can turn it back on.", () -> {
            boolean on = !Standby.enabled(this);
            Standby.setEnabled(this, on);
            if (on && !Standby.exempt(this)) askDozeExemption();
            build();
        }));
        power.setValue(standby ? "On" : "Off");
        if (standby && !Standby.exempt(this)) {
            power.setDetail("On, but Android may still put Webmote to sleep while the TV is off: run install.sh again from a computer (see the README).");
        }

        String mode = Updater.mode(this);
        Row updates = add(new Row(this, "Updates", modeDetail(mode), () -> {
            String m = Updater.mode(this);
            Updater.setMode(this, Updater.AUTO.equals(m) ? Updater.NOTIFY : Updater.NOTIFY.equals(m) ? Updater.OFF : Updater.AUTO);
            build();
        }));
        updates.setValue(Updater.AUTO.equals(mode) ? "Automatic" : Updater.NOTIFY.equals(mode) ? "Notify only" : "Off");
        updateShown = Updater.status(this);
        updateRow = add(new Row(this, "Update now", updateShown.isEmpty() ? "Checks GitHub for a newer Webmote." : updateShown, () -> {
            Updater.checkNow(this);
            build();
        }));
        updateRow.setValue(BuildConfig.VERSION_NAME);

        int keep = focused >= 0 && focused < rows.getChildCount() ? focused : 0;
        rows.getChildAt(keep).requestFocus();
    }

    private static String modeDetail(String mode) {
        if (Updater.AUTO.equals(mode)) return "Installs new versions of Webmote from GitHub by itself.";
        if (Updater.NOTIFY.equals(mode)) return "Checks GitHub daily and tells Home Assistant; installs only with Update now.";
        return "Never checks by itself; Update now still works.";
    }

    /** Android's own "let this app run in the background?" prompt, where the TV has it. */
    private void askDozeExemption() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException | SecurityException e) {
            status.setText("This TV has no screen for it. From a computer: run install.sh again (see the README).");
        }
    }

    /** The QR code of {@code url}, white on black with a quiet zone, or none. */
    private void showQr(String url) {
        if (url != null && url.equals(qrFor)) return;
        qrFor = url;
        boolean[][] m = url == null ? null : Qr.encode(url);
        if (m == null) {
            qr.setVisibility(ImageView.GONE);
            return;
        }
        int n = m.length + 8;
        Bitmap b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int mx = x - 4, my = y - 4;
                boolean dark = mx >= 0 && my >= 0 && mx < m.length && my < m.length && m[my][mx];
                b.setPixel(x, y, dark ? 0xFF000000 : 0xFFFFFFFF);
            }
        }
        android.graphics.drawable.BitmapDrawable d = new android.graphics.drawable.BitmapDrawable(getResources(), b);
        d.setFilterBitmap(false); // sharp modules when scaled up
        qr.setImageDrawable(d);
        qr.setScaleType(ImageView.ScaleType.FIT_START);
        qr.setVisibility(ImageView.VISIBLE);
    }

    private Row add(Row r) {
        rows.addView(r, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return r;
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.START);
        return t;
    }

    private LinearLayout.LayoutParams lp(int top) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l.topMargin = top;
        return l;
    }

    private int px(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
