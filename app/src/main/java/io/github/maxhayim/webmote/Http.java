package io.github.maxhayim.webmote;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Just enough HTTP/1.1 for one page and a small API on the home network: one request per connection. */
final class Http {
    static final int MAX_HEADER_BYTES = 8 * 1024;
    static final int MAX_BODY_BYTES = 16 * 1024;

    private Http() {
    }

    static final class Request {
        final String method;
        final String path;
        final Map<String, String> headers;
        final String body;

        Request(String method, String path, Map<String, String> headers, String body) {
            this.method = method;
            this.path = path;
            this.headers = headers;
            this.body = body;
        }

        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    /** The request on {@code in}, or null when it is malformed or too big. */
    static Request read(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int b = in.read();
            if (b < 0 || head.size() >= MAX_HEADER_BYTES) return null;
            head.write(b);
            matched = (b == (matched % 2 == 0 ? '\r' : '\n')) ? matched + 1 : (b == '\r' ? 1 : 0);
        }
        String[] lines = head.toString(StandardCharsets.ISO_8859_1.name()).split("\r\n");
        String[] first = lines[0].split(" ");
        if (first.length != 3 || !first[2].startsWith("HTTP/1.")) return null;
        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) continue;
            headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim());
        }
        int length = 0;
        String cl = headers.get("content-length");
        if (cl != null) {
            try {
                length = Integer.parseInt(cl);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (length < 0 || length > MAX_BODY_BYTES) return null;
        byte[] body = new byte[length];
        for (int off = 0; off < length; ) {
            int n = in.read(body, off, length - off);
            if (n < 0) return null;
            off += n;
        }
        String path = first[1];
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        return new Request(first[0], path, headers, new String(body, StandardCharsets.UTF_8));
    }

    static void write(OutputStream out, int status, String type, byte[] body, boolean cache) throws IOException {
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
        h.append("Content-Type: ").append(type).append("\r\n");
        h.append("Content-Length: ").append(body.length).append("\r\n");
        h.append("Cache-Control: ").append(cache ? "max-age=86400" : "no-store").append("\r\n");
        h.append("X-Content-Type-Options: nosniff\r\n");
        h.append("Referrer-Policy: no-referrer\r\n");
        if (type.startsWith("text/html")) {
            h.append("Content-Security-Policy: default-src 'self'; script-src 'self' 'unsafe-inline'; "
                    + "style-src 'self' 'unsafe-inline'; img-src 'self' blob: data:; connect-src 'self'; frame-ancestors 'none'\r\n");
        }
        h.append("Connection: close\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 429: return "Too Many Requests";
            case 503: return "Service Unavailable";
            default: return "Error";
        }
    }

    /** Only the home network: private, link-local and loopback addresses. */
    static boolean isLocal(InetAddress a) {
        if (a == null) return false;
        if (a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()) return true;
        if (a instanceof Inet6Address) return (a.getAddress()[0] & 0xFE) == 0xFC; // fc00::/7, unique local
        if (a instanceof Inet4Address) {
            byte[] b = a.getAddress();
            return (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64; // 100.64/10: Tailscale and carrier NAT
        }
        return false;
    }

    /**
     * The Host header has to be an address, not a name: a web page elsewhere can point a name of its own at this TV
     * (DNS rebinding), but it can't make the browser send this TV's address as the host.
     */
    static boolean hostAllowed(String host) {
        if (host == null || host.isEmpty()) return false;
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[")) return h.matches("\\[[0-9a-f:.]+\\](:\\d{1,5})?");
        int colon = h.lastIndexOf(':');
        if (colon >= 0) {
            if (!h.substring(colon + 1).matches("\\d{1,5}")) return false;
            h = h.substring(0, colon);
        }
        return h.equals("localhost") || h.matches("(\\d{1,3}\\.){3}\\d{1,3}");
    }
}
