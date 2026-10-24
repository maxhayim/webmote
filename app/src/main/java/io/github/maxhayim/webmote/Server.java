package io.github.maxhayim.webmote;

import android.content.res.AssetManager;
import android.os.SystemClock;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The page and its API, for phones and computers on the home network. Everything under /api/ except pairing needs a
 * paired key in the X-Webmote-Key header, which a page on another site can't send without the browser asking first
 * (and this server never says yes). Requests from outside the home network, or with a name instead of an address in
 * Host, are refused.
 */
final class Server {
    private static final String TAG = "Webmote";

    private final RemoteService service;
    private final Auth auth;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private volatile ServerSocket socket;
    private volatile int port = -1;

    Server(RemoteService service, Auth auth) {
        this.service = service;
        this.auth = auth;
    }

    int port() {
        return port;
    }

    void start(int preferred) {
        Thread t = new Thread(() -> {
            for (int p = preferred; p < preferred + 10 && socket == null; p++) {
                try {
                    ServerSocket s = new ServerSocket();
                    s.setReuseAddress(true);
                    s.bind(new InetSocketAddress(p));
                    socket = s;
                    port = p;
                } catch (IOException e) {
                    Log.w(TAG, "port " + p + " is taken");
                }
            }
            ServerSocket s = socket;
            while (s != null && !s.isClosed()) {
                try {
                    Socket c = s.accept();
                    pool.execute(() -> handle(c));
                } catch (IOException e) {
                    if (!s.isClosed()) Log.w(TAG, "accept", e);
                }
            }
        }, "webmote-server");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
            // Closing anyway.
        }
        pool.shutdownNow();
    }

    private void handle(Socket c) {
        try (Socket s = c) {
            s.setSoTimeout(10_000);
            OutputStream out = s.getOutputStream();
            if (!Http.isLocal(s.getInetAddress())) {
                Http.write(out, 403, "text/plain", bytes("Home network only."), false);
                return;
            }
            Http.Request r = Http.read(s.getInputStream());
            if (r == null) {
                Http.write(out, 400, "text/plain", bytes("Bad request."), false);
                return;
            }
            if (!Http.hostAllowed(r.header("host"))) {
                Http.write(out, 403, "text/plain", bytes("Open Webmote by the TV's address."), false);
                return;
            }
            route(r, out);
        } catch (SocketException ignored) {
            // The phone went away.
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "request", e);
        }
    }

    private void route(Http.Request r, OutputStream out) throws IOException {
        String p = r.path;
        boolean get = r.method.equals("GET"), post = r.method.equals("POST");
        if (get && (p.equals("/") || p.equals("/index.html"))) {
            Http.write(out, 200, "text/html; charset=utf-8", named(asset("index.html"), html(service.tvName())), false);
        } else if (get && p.equals("/manifest.webmanifest")) {
            String tv = Json.quote(service.tvName());
            Http.write(out, 200, "application/manifest+json", named(asset("manifest.webmanifest"), tv.substring(1, tv.length() - 1)), false);
        } else if (get && p.equals("/icon-192.png")) {
            Http.write(out, 200, "image/png", Icon.png(192), true);
        } else if (get && p.equals("/icon-512.png")) {
            Http.write(out, 200, "image/png", Icon.png(512), true);
        } else if (get && p.equals("/apple-touch-icon.png")) {
            Http.write(out, 200, "image/png", Icon.png(180), true);
        } else if (post && p.equals("/api/pair")) {
            Auth.Result res = auth.pair(Json.string(r.body, "pin"), SystemClock.elapsedRealtime());
            if (res.key != null) {
                service.hidePin();
                json(out, 200, "{\"key\":" + Json.quote(res.key) + "}");
            }
            else if (res.waitMs > 0) json(out, 429, "{\"error\":\"Too many tries.\",\"wait\":" + ((res.waitMs + 999) / 1000) + "}");
            else json(out, 403, "{\"error\":\"That PIN isn't right.\"}");
        } else if (post && p.equals("/api/pair/show")) {
            // JSON only, so another site's page can't post it without the browser asking first (and it gets no yes).
            boolean json = String.valueOf(r.header("content-type")).startsWith("application/json");
            if (json) service.showPin();
            ok(out, json);
        } else if (p.startsWith("/api/")) {
            if (!auth.allowed(r.header("x-webmote-key"))) {
                json(out, 401, "{\"error\":\"Pair this device again.\"}");
            } else {
                api(r, get, post, out);
            }
        } else {
            Http.write(out, 404, "text/plain", bytes("Not here."), false);
        }
    }

    private void api(Http.Request r, boolean get, boolean post, OutputStream out) throws IOException {
        String p = r.path;
        if (get && p.equals("/api/status")) {
            json(out, 200, service.status());
        } else if (post && p.equals("/api/key")) {
            String key = Json.string(r.body, "key");
            ok(out, key != null && service.press(key));
        } else if (post && p.equals("/api/text")) {
            String text = Json.string(r.body, "text");
            boolean done = Json.isTrue(r.body, "enter") ? service.enter(text) : text != null && service.type(text);
            if (done) ok(out, true);
            else if (service.searchesOnEnter()) json(out, 200, "{\"ok\":false,\"error\":\"This app has its own keyboard. Press Enter to search it.\"}");
            else json(out, 200, "{\"ok\":false,\"error\":\"No text box has focus on the TV.\"}");
        } else if (get && p.equals("/api/apps")) {
            json(out, 200, Apps.json(service));
        } else if (get && p.startsWith("/api/icon/")) {
            String pkg = p.substring("/api/icon/".length());
            if (pkg.endsWith(".png")) pkg = pkg.substring(0, pkg.length() - 4);
            byte[] png = Apps.icon(service, pkg);
            if (png == null) Http.write(out, 404, "text/plain", bytes("No icon."), false);
            else Http.write(out, 200, "image/png", png, false);
        } else if (post && p.equals("/api/launch")) {
            String pkg = Json.string(r.body, "pkg");
            ok(out, pkg != null && Apps.isTvApp(service, pkg) && service.launch(pkg));
        } else {
            json(out, 404, "{\"error\":\"Unknown call.\"}");
        }
    }

    private static void ok(OutputStream out, boolean ok) throws IOException {
        json(out, 200, "{\"ok\":" + ok + "}");
    }

    private static void json(OutputStream out, int status, String body) throws IOException {
        Http.write(out, status, "application/json; charset=utf-8", bytes(body), false);
    }

    /** The asset with each {@code %TV%} swapped for the TV's name, already escaped for where it goes. */
    static byte[] named(byte[] asset, String tv) {
        return bytes(new String(asset, StandardCharsets.UTF_8).replace("%TV%", tv));
    }

    static String html(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private byte[] asset(String name) throws IOException {
        AssetManager a = service.getAssets();
        try (InputStream in = a.open(name)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            return b.toByteArray();
        }
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
