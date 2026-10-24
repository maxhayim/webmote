package io.github.maxhayim.webmote;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.security.SecureRandom;
import java.util.Map;

/**
 * The remote's hands, and its server's home: while this accessibility service is on, the page is served on the home
 * network. D-pad, OK, Back, Home and Turn off are the system's own global actions (Android 13 added the D-pad ones);
 * volume goes through the audio service. It listens only for which app comes to the front (to name it on the page),
 * and reads the screen only when the page types: to find the text field that has focus. Apps that draw their own
 * keyboard (Netflix) have no text field to fill, but take a search in a link, so Enter searches there.
 */
public class RemoteService extends AccessibilityService {
    static final int PORT = 8080;

    /** Apps with their own keyboard and a search link, by package: the link, before the search words. */
    private static final Map<String, String> SEARCH_LINKS = Map.of("com.netflix.ninja", "nflx://www.netflix.com/search?q=");

    static volatile RemoteService running;
    private static Auth auth;

    private Server server;
    private volatile String frontPackage;

    static synchronized Auth auth(Context c) {
        if (auth == null) {
            SharedPreferences p = c.getApplicationContext().getSharedPreferences("webmote", Context.MODE_PRIVATE);
            auth = new Auth(new Auth.Store() {
                @Override
                public String get(String key) {
                    return p.getString(key, null);
                }

                @Override
                public void put(String key, String value) {
                    p.edit().putString(key, value).apply();
                }
            }, new SecureRandom());
        }
        return auth;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        running = this;
        server = new Server(this, auth(this));
        server.start(PORT);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.getPackageName() == null) return;
        String pkg = e.getPackageName().toString();
        // Dialogs and the volume bar come and go over the app; keep the app.
        if (!pkg.equals("android") && !pkg.equals("com.android.systemui") && !pkg.equals(getPackageName())) frontPackage = pkg;
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        if (server != null) server.stop();
        if (running == this) running = null;
        super.onDestroy();
    }

    int port() {
        return server == null ? -1 : server.port();
    }

    /** One remote button; false for an unknown name or one the TV refused. */
    boolean press(String key) {
        AudioManager audio = getSystemService(AudioManager.class);
        switch (key) {
            case "up": return performGlobalAction(GLOBAL_ACTION_DPAD_UP);
            case "down": return performGlobalAction(GLOBAL_ACTION_DPAD_DOWN);
            case "left": return performGlobalAction(GLOBAL_ACTION_DPAD_LEFT);
            case "right": return performGlobalAction(GLOBAL_ACTION_DPAD_RIGHT);
            case "ok": return performGlobalAction(GLOBAL_ACTION_DPAD_CENTER);
            case "back": return performGlobalAction(GLOBAL_ACTION_BACK);
            case "home": return performGlobalAction(GLOBAL_ACTION_HOME);
            case "recents": return performGlobalAction(GLOBAL_ACTION_RECENTS);
            case "off": return performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
            case "vol_up":
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
                return true;
            case "vol_down":
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
                return true;
            case "mute":
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI);
                return true;
            case "play_pause":
                long t = SystemClock.uptimeMillis();
                audio.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0));
                audio.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0));
                return true;
            case "settings":
                return start(new Intent(Settings.ACTION_SETTINGS));
            default:
                return false;
        }
    }

    /** Puts {@code text} in the focused text field (all of it, replacing what was there); false when none has focus. */
    boolean type(String text) {
        AccessibilityNodeInfo field = focusedField();
        if (field == null) return false;
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    /**
     * The keyboard's Enter (search, go) in the focused text field, after putting {@code text} in it when given. With no
     * text field, in an app that searches by link, searches there for {@code text}.
     */
    boolean enter(String text) {
        AccessibilityNodeInfo field = focusedField();
        if (field == null) return text != null && search(text);
        if (text != null && !type(text)) return false;
        return field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
    }

    /** Whether the app in front has no text field to type into but searches on Enter. */
    boolean searchesOnEnter() {
        return focusedField() == null && SEARCH_LINKS.containsKey(String.valueOf(front()));
    }

    private boolean search(String text) {
        String pkg = front(), link = pkg == null ? null : SEARCH_LINKS.get(pkg);
        if (link == null || text.trim().isEmpty()) return false;
        return start(new Intent(Intent.ACTION_VIEW, Uri.parse(link + Uri.encode(text.trim()))).setPackage(pkg));
    }

    private AccessibilityNodeInfo focusedField() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo f = root == null ? null : root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        return f != null && f.isEditable() ? f : null;
    }

    boolean launch(String pkg) {
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = getPackageManager().getLaunchIntentForPackage(pkg);
        return i != null && start(i);
    }

    private boolean start(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    /** The name the TV goes by in its settings, for the page title and the API. */
    String tvName() {
        String name = Settings.Global.getString(getContentResolver(), Settings.Global.DEVICE_NAME);
        return name == null || name.trim().isEmpty() ? "TV" : name.trim();
    }

    /** The app in front: the window's, when the TV says; window events alone miss some returns to the home screen. */
    private String front() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        String pkg = root != null && root.getPackageName() != null ? root.getPackageName().toString() : frontPackage;
        return pkg != null && (pkg.equals("com.android.systemui") || pkg.equals(getPackageName())) ? frontPackage : pkg;
    }

    String status() {
        AudioManager audio = getSystemService(AudioManager.class);
        String app = "";
        String pkg = front();
        if (pkg != null) {
            try {
                ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
                app = getPackageManager().getApplicationLabel(ai).toString();
            } catch (PackageManager.NameNotFoundException ignored) {
                // Uninstalled since.
            }
        }
        return "{\"tv\":" + Json.quote(tvName())
                + ",\"app\":" + Json.quote(app)
                + ",\"volume\":" + audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                + ",\"max\":" + audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                + ",\"muted\":" + audio.isStreamMute(AudioManager.STREAM_MUSIC) + "}";
    }
}
