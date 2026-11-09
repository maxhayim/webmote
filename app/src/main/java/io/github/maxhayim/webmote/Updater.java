package io.github.maxhayim.webmote;

import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Updates Webmote from its GitHub releases: checks once a day and, unless switched off on Webmote's screen, downloads
 * and installs a newer release. The install is silent on Android 12 and newer once Webmote installed itself (install.sh
 * makes it its own installer); elsewhere Android asks once on screen, which Webmote shows only when someone asked for
 * the update or is on Webmote's screen, never over a show. GitHub is the only place it contacts, and only for this.
 */
final class Updater {
    static final String REPO = "maxhayim/webmote";
    private static final String LATEST = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final long DAY_MS = 24 * 60 * 60 * 1000L;
    private static final long ASKED_FOR_MS = 10 * 60 * 1000L;
    private static final int JOB_ID = 1;

    /** Webmote's screen is open: Android's install prompt can be shown. */
    static volatile boolean visible;
    /** When someone last asked for an update (the TV's screen, the page or Home Assistant). */
    private static volatile long askedAt = -ASKED_FOR_MS;
    private static final Object busy = new Object();

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("updater", Context.MODE_PRIVATE);
    }

    static boolean auto(Context c) {
        return prefs(c).getBoolean("auto", true);
    }

    static void setAuto(Context c, boolean on) {
        prefs(c).edit().putBoolean("auto", on).apply();
    }

    /** The latest release's version, as last seen ("" before the first check). */
    static String latest(Context c) {
        return prefs(c).getString("version", "");
    }

    /** What happened last, for Webmote's screen and the status ("Up to date", "Update failed: …"). */
    static String status(Context c) {
        return prefs(c).getString("status", "");
    }

    static void setStatus(Context c, String s) {
        prefs(c).edit().putString("status", s).apply();
    }

    /** The daily check, kept across restarts. */
    static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null || js.getPendingJob(JOB_ID) != null) return;
        js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, Job.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(DAY_MS)
                .setPersisted(true)
                .build());
    }

    /** Checks now, on a thread of its own, and installs a newer release; for a person who asked. */
    static void checkNow(Context c) {
        askedAt = SystemClock.elapsedRealtime();
        setStatus(c, "Checking…");
        Context app = c.getApplicationContext();
        new Thread(() -> run(app, true), "webmote-update").start();
    }

    /** Blocking. {@code asked}: someone asked, so it runs even with automatic updates off. */
    static void run(Context c, boolean asked) {
        if (!asked && !auto(c)) return;
        synchronized (busy) {
            String version, apk;
            try {
                JSONObject rel = new JSONObject(get(LATEST));
                version = rel.optString("tag_name").replaceFirst("^v", "");
                apk = null;
                JSONArray assets = rel.optJSONArray("assets");
                for (int i = 0; assets != null && i < assets.length(); i++) {
                    JSONObject a = assets.optJSONObject(i);
                    if (a != null && a.optString("name").endsWith(".apk")) apk = a.optString("browser_download_url");
                }
            } catch (IOException | JSONException e) {
                setStatus(c, "Couldn't reach GitHub");
                return;
            }
            prefs(c).edit().putString("version", version).putLong("checked", System.currentTimeMillis()).apply();
            if (compare(version, BuildConfig.VERSION_NAME) <= 0) {
                setStatus(c, "Up to date");
                return;
            }
            if (apk == null) {
                setStatus(c, "Version " + version + " has no app file yet");
                return;
            }
            install(c, version, apk);
        }
    }

    /** Compares "1.10.0" with "1.9.2" by number, part by part. */
    static int compare(String a, String b) {
        String[] x = a.split("[.\\-]"), y = b.split("[.\\-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void install(Context c, String version, String url) {
        File apk = new File(c.getCacheDir(), "update.apk");
        try {
            setStatus(c, "Downloading " + version + "…");
            download(url, apk);
            String problem = verify(c, apk);
            if (problem != null) {
                apk.delete();
                setStatus(c, "Update refused: " + problem);
                return;
            }
            setStatus(c, "Installing " + version + "…");
            commit(c, apk);
        } catch (IOException e) {
            setStatus(c, "Update failed: " + e.getMessage());
        }
    }

    private static String get(String url) throws IOException {
        HttpURLConnection h = open(url);
        try {
            if (h.getResponseCode() != 200) throw new IOException("HTTP " + h.getResponseCode());
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (InputStream in = h.getInputStream()) {
                copy(in, b);
            }
            return b.toString(StandardCharsets.UTF_8.name());
        } finally {
            h.disconnect();
        }
    }

    private static void download(String url, File to) throws IOException {
        HttpURLConnection h = open(url);
        try {
            if (h.getResponseCode() != 200) throw new IOException("HTTP " + h.getResponseCode());
            try (InputStream in = h.getInputStream(); OutputStream out = new FileOutputStream(to)) {
                copy(in, out);
            }
        } finally {
            h.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(15_000);
        h.setReadTimeout(60_000);
        h.setRequestProperty("User-Agent", "Webmote/" + BuildConfig.VERSION_NAME);
        return h;
    }

    /** It must be Webmote, newer, and signed with this install's key (Android checks that too, less clearly). */
    @SuppressWarnings("deprecation") // GET_SIGNATURES is what reads an APK file's signer on every Android version
    private static String verify(Context c, File apk) {
        PackageManager pm = c.getPackageManager();
        PackageInfo update = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNATURES);
        if (update == null) return "not an app file";
        if (!c.getPackageName().equals(update.packageName)) return "a different app";
        if (compare(update.versionName, BuildConfig.VERSION_NAME) <= 0) return "not newer";
        try {
            Signature[] mine = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNATURES).signatures;
            // Some Android versions read no signer from a file; Android still refuses another key when installing.
            if (update.signatures != null && update.signatures.length > 0 && !Arrays.equals(mine, update.signatures)) {
                return "signed with a different key";
            }
        } catch (PackageManager.NameNotFoundException e) {
            return "can't read this app's key";
        }
        return null;
    }

    private static void commit(Context c, File apk) throws IOException {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName());
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int id = pi.createSession(params);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (OutputStream out = s.openWrite("base.apk", 0, apk.length()); InputStream in = new FileInputStream(apk)) {
                copy(in, out);
                s.fsync(out);
            }
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            s.commit(PendingIntent.getBroadcast(c, id, new Intent(c, Done.class), flags).getIntentSender());
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
    }

    /** Whether Android's "Install?" prompt may go on screen now: someone asked lately, or is on Webmote's screen. */
    private static boolean mayAsk(Context c) {
        PowerManager p = c.getSystemService(PowerManager.class);
        boolean on = p == null || p.isInteractive();
        return visible || (on && SystemClock.elapsedRealtime() - askedAt < ASKED_FOR_MS);
    }

    /** The daily check. */
    public static class Job extends JobService {
        @Override
        public boolean onStartJob(JobParameters params) {
            new Thread(() -> {
                run(getApplicationContext(), false);
                jobFinished(params, false);
            }, "webmote-update").start();
            return true;
        }

        @Override
        public boolean onStopJob(JobParameters params) {
            return true;
        }
    }

    /** Android's installer reporting back. */
    public static class Done extends BroadcastReceiver {
        @Override
        @SuppressWarnings("deprecation") // the typed getParcelableExtra needs Android 13
        public void onReceive(Context c, Intent intent) {
            if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
                // The new version is running; the installer's own report went to the old one.
                setStatus(c, "Updated to " + BuildConfig.VERSION_NAME);
                return;
            }
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            switch (status) {
                case PackageInstaller.STATUS_PENDING_USER_ACTION:
                    Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm != null && mayAsk(c)) {
                        try {
                            c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                            setStatus(c, "Confirm the install on the TV");
                            break;
                        } catch (RuntimeException ignored) {
                            // Falls through to waiting.
                        }
                    }
                    setStatus(c, "Version " + latest(c) + " is ready: open Webmote on the TV and press Update now");
                    break;
                case PackageInstaller.STATUS_SUCCESS:
                    setStatus(c, "Updated");
                    break;
                case PackageInstaller.STATUS_FAILURE_ABORTED:
                    setStatus(c, "Install cancelled. If you just allowed installs from Webmote, press Update now again.");
                    break;
                default:
                    String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                    setStatus(c, "Update failed" + (msg == null ? "" : ": " + msg));
            }
        }
    }
}
