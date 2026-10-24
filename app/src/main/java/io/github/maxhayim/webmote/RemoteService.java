package io.github.maxhayim.webmote;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.InputMethod;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.security.SecureRandom;

/**
 * The remote's hands, and its server's home: while this accessibility service is on, the page is served on the home
 * network. D-pad, OK, Back, Home and Turn off are the system's own global actions (Android 13 added the D-pad ones);
 * volume goes through the audio service. It listens only for which app comes to the front (to name it on the page),
 * and reads the screen only when the page types: to find the text field that has focus. Apps that draw their own
 * on-screen keyboard (Netflix) have no text field, so there it types as a keyboard would, through the input method
 * Android 13 gives accessibility services.
 */
public class RemoteService extends AccessibilityService {
    static final int PORT = 8080;

    static volatile RemoteService running;
    private static Auth auth;

    private Server server;
    private volatile String frontPackage;
    private String keyed = ""; // what key presses have typed into the app in front, to work out what to change

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
        if (!pkg.equals("android") && !pkg.equals("com.android.systemui") && !pkg.equals(getPackageName())) {
            if (!pkg.equals(frontPackage)) forgetKeyed();
            frontPackage = pkg;
        }
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

    /**
     * Puts {@code text} in the focused text field (all of it, replacing what was there), or, in an app with no text
     * field but taking keys, presses the keys that turn what was typed before into it; false when neither is there.
     */
    boolean type(String text) {
        AccessibilityNodeInfo field = focusedField();
        if (field == null) return keyIn(text);
        forgetKeyed();
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    /** The keyboard's Enter (search, go) in the focused text field, or an Enter key press. */
    boolean enter() {
        AccessibilityNodeInfo field = focusedField();
        if (field != null) return field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
        InputMethod.AccessibilityInputConnection ic = keys();
        if (ic == null) return false;
        tap(ic, KeyEvent.KEYCODE_ENTER);
        forgetKeyed();
        return true;
    }

    private synchronized boolean keyIn(String text) {
        InputMethod.AccessibilityInputConnection ic = keys();
        if (ic == null) return false;
        int same = 0;
        while (same < keyed.length() && same < text.length() && keyed.charAt(same) == text.charAt(same)) same++;
        for (int i = keyed.length(); i > same; i--) tap(ic, KeyEvent.KEYCODE_DEL);
        KeyCharacterMap map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
        for (int i = same; i < text.length(); i++) {
            KeyEvent[] events = map.getEvents(new char[] {text.charAt(i)});
            if (events == null) ic.commitText(text.substring(i, i + 1), 1, null); // no key for it (é, emoji)
            else for (KeyEvent e : events) ic.sendKeyEvent(e);
        }
        keyed = text;
        return true;
    }

    private synchronized void forgetKeyed() {
        keyed = "";
    }

    /** The connection to whatever takes keys in the app in front, or null when nothing does. */
    private InputMethod.AccessibilityInputConnection keys() {
        InputMethod im = getInputMethod();
        return im == null || !im.getCurrentInputStarted() ? null : im.getCurrentInputConnection();
    }

    private static void tap(InputMethod.AccessibilityInputConnection ic, int code) {
        ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
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

    String status() {
        AudioManager audio = getSystemService(AudioManager.class);
        String app = "";
        // The window in front, when the TV says; window events alone miss some returns to the home screen.
        AccessibilityNodeInfo root = getRootInActiveWindow();
        String pkg = root != null && root.getPackageName() != null ? root.getPackageName().toString() : frontPackage;
        if (pkg != null && (pkg.equals("com.android.systemui") || pkg.equals(getPackageName()))) pkg = frontPackage;
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
