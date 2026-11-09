package io.github.maxhayim.webmote;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

/**
 * Standby: while the screen is off, keeps the TV awake enough (the processor and Wi-Fi, not the screen) for the page
 * and Home Assistant to reach Webmote and turn the screen back on. Without it, most TVs fall asleep and drop off the
 * network, and nothing on the network can wake them. On by default; costs a few watts while the TV is off.
 *
 * <p>Android's Doze ignores an app's wake locks unless the app is let off battery optimisation, which
 * {@link #exempt} tells; install.sh does that over ADB, and Webmote's screen asks where the TV has the dialog.
 */
final class Standby {
    private static final String TAG = "webmote:standby";

    private final Context context;
    private final PowerManager power;
    private final WifiManager wifi;
    private final Handler main = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock cpu;
    private WifiManager.WifiLock radio;
    private boolean registered;

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) hold();
            else release();
        }
    };

    Standby(Context context) {
        this.context = context;
        power = context.getSystemService(PowerManager.class);
        wifi = context.getApplicationContext().getSystemService(WifiManager.class);
    }

    static boolean enabled(Context c) {
        return prefs(c).getBoolean("standby", true);
    }

    static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("standby", on).apply();
        RemoteService svc = RemoteService.running;
        if (svc != null) svc.standbyChanged();
    }

    /** Whether Doze leaves Webmote's wake locks alone (true where the TV has no Doze to speak of). */
    static boolean exempt(Context c) {
        PowerManager p = c.getSystemService(PowerManager.class);
        return p == null || p.isIgnoringBatteryOptimizations(c.getPackageName());
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("webmote", Context.MODE_PRIVATE);
    }

    void start() {
        if (!registered) {
            IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
            f.addAction(Intent.ACTION_SCREEN_ON);
            context.registerReceiver(screen, f);
            registered = true;
        }
        if (power != null && !power.isInteractive()) hold();
    }

    void stop() {
        if (registered) {
            context.unregisterReceiver(screen);
            registered = false;
        }
        release();
    }

    /** Screen off: stay on the network, if standby is on. */
    synchronized void hold() {
        if (!enabled(context) || power == null || power.isInteractive()) return;
        if (cpu == null) {
            cpu = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
            cpu.setReferenceCounted(false);
        }
        cpu.acquire();
        if (wifi != null) {
            if (radio == null) {
                @SuppressWarnings("deprecation") // the low-latency mode works only with the screen on
                int mode = WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                radio = wifi.createWifiLock(mode, TAG);
                radio.setReferenceCounted(false);
            }
            radio.acquire();
        }
    }

    synchronized void release() {
        if (cpu != null && cpu.isHeld()) cpu.release();
        if (radio != null && radio.isHeld()) radio.release();
    }

    /**
     * Turns the screen on (and, through HDMI-CEC, a TV the device is plugged into, where it does that on waking). A
     * wake lock that wakes the screen first; where the TV ignores it, a moment of an activity that turns it on.
     */
    @SuppressWarnings("deprecation") // FULL_WAKE_LOCK with ACQUIRE_CAUSES_WAKEUP is still how an app wakes the screen
    boolean wake() {
        if (power == null) return false;
        if (power.isInteractive()) return true;
        PowerManager.WakeLock w = power.newWakeLock(
                PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, TAG + ":on");
        w.acquire(3000);
        main.postDelayed(() -> {
            if (!power.isInteractive()) {
                try {
                    context.startActivity(new Intent(context, WakeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (RuntimeException ignored) {
                    // Nothing else to try.
                }
            }
        }, 800);
        return true;
    }

    /** Shown for an instant to turn the screen on, then gone, back to what was in front. */
    public static class WakeActivity extends Activity {
        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }

        @Override
        protected void onResume() {
            super.onResume();
            // Long enough for the screen to come on with this window, which is see-through.
            getWindow().getDecorView().postDelayed(this::finish, 600);
        }
    }
}
