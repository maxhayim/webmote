package io.github.maxhayim.webmote;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.net.Inet4Address;

/**
 * The tile and module card open this: the address to open on a phone or computer, the PIN that pairs it, and whether
 * the remote service is on. New PIN signs every paired device out.
 */
public class MainActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout rows;
    private TextView address, pin, status;
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
        left.addView(text("On a phone or computer on this Wi-Fi, open", 16, 0xFF8B949E), lp(px(28)));
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
        status = text("", 15, 0xFF8B949E);
        right.addView(status);
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        right.addView(rows, lp(px(12)));
        page.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        setContentView(page);
    }

    @Override
    protected void onResume() {
        super.onResume();
        build();
    }

    @Override
    protected void onPause() {
        main.removeCallbacksAndMessages(null);
        super.onPause();
    }

    private void build() {
        int focused = rows.indexOfChild(rows.getFocusedChild());
        rows.removeAllViews();
        Auth auth = RemoteService.auth(this);
        RemoteService svc = RemoteService.running;
        String ip = ip();
        int port = svc == null ? -1 : svc.port();
        if (svc == null) {
            address.setText("Remote service is off");
            status.setText("Switch on the remote service below (Accessibility, Webmote) to start the page.");
        } else if (port < 0) {
            address.setText("Starting…");
            main.postDelayed(this::build, 700);
        } else {
            address.setText(ip == null ? "No network" : "http://" + ip + (port == 80 ? "" : ":" + port));
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

        int keep = focused >= 0 && focused < rows.getChildCount() ? focused : 0;
        rows.getChildAt(keep).requestFocus();
    }

    private String ip() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        Network n = cm.getActiveNetwork();
        LinkProperties p = n == null ? null : cm.getLinkProperties(n);
        if (p == null) return null;
        for (LinkAddress a : p.getLinkAddresses()) {
            if (a.getAddress() instanceof Inet4Address) return a.getAddress().getHostAddress();
        }
        return null;
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
