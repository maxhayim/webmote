package io.github.maxhayim.webmote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

public class WebmoteTest {
    private static Http.Request req(String raw) throws IOException {
        return Http.read(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void readsARequestWithItsBody() throws IOException {
        Http.Request r = req("POST /api/key?x=1 HTTP/1.1\r\nHost: 192.168.1.5:8080\r\nX-Webmote-Key: abc\r\n"
                + "Content-Length: 13\r\n\r\n{\"key\":\"up\"}\nEXTRA");
        assertNotNull(r);
        assertEquals("POST", r.method);
        assertEquals("/api/key", r.path);
        assertEquals("abc", r.header("X-WEBMOTE-KEY"));
        assertEquals("{\"key\":\"up\"}\n", r.body);
    }

    @Test
    public void refusesMalformedAndOversizedRequests() throws IOException {
        assertNull(req("GARBAGE\r\n\r\n"));
        assertNull(req("GET / HTTP/1.1\r\nHost: x\r\n")); // never ends
        assertNull(req("POST / HTTP/1.1\r\nContent-Length: 999999\r\n\r\n"));
        assertNull(req("POST / HTTP/1.1\r\nContent-Length: -1\r\n\r\n"));
        assertNull(req("POST / HTTP/1.1\r\nContent-Length: 10\r\n\r\nshort"));
        StringBuilder big = new StringBuilder("GET / HTTP/1.1\r\nX: ");
        for (int i = 0; i < Http.MAX_HEADER_BYTES; i++) big.append('a');
        assertNull(req(big.append("\r\n\r\n").toString()));
    }

    @Test
    public void onlyTheHomeNetwork() throws IOException {
        for (String ok : new String[]{"192.168.1.20", "10.0.0.2", "172.16.4.4", "127.0.0.1", "169.254.1.1", "100.101.102.103", "fd12::1", "fe80::1", "::1"}) {
            assertTrue(ok, Http.isLocal(InetAddress.getByName(ok)));
        }
        for (String no : new String[]{"8.8.8.8", "1.1.1.1", "100.128.0.1", "172.32.0.1", "2001:4860::8888"}) {
            assertFalse(no, Http.isLocal(InetAddress.getByName(no)));
        }
        assertFalse(Http.isLocal(null));
    }

    @Test
    public void hostMustBeAnAddress() {
        assertTrue(Http.hostAllowed("192.168.1.5:8080"));
        assertTrue(Http.hostAllowed("192.168.1.5"));
        assertTrue(Http.hostAllowed("localhost:8080"));
        assertTrue(Http.hostAllowed("[fe80::1]:8080"));
        assertFalse(Http.hostAllowed("evil.example:8080"));
        assertFalse(Http.hostAllowed("192.168.1.5.nip.io"));
        assertFalse(Http.hostAllowed("192.168.1.5:80x"));
        assertFalse(Http.hostAllowed(""));
        assertFalse(Http.hostAllowed(null));
    }

    @Test
    public void jsonRoundTrips() {
        String nasty = "a \"quote\" \\ back\nline\u0001 é 🎬";
        String json = "{\"x\":1, \"text\" : " + Json.quote(nasty) + ",\"enter\": true}";
        assertEquals(nasty, Json.string(json, "text"));
        assertTrue(Json.isTrue(json, "enter"));
        assertFalse(Json.isTrue(json, "x"));
        assertNull(Json.string(json, "x"));
        assertNull(Json.string(json, "missing"));
        assertNull(Json.string("{\"text\":\"unterminated", "text"));
        assertEquals("up", Json.string("{\"key\":\"\\u0075p\"}", "key"));
        // A key named inside a value isn't the key.
        assertEquals("real", Json.string("{\"note\":\"\\\"pin\\\"\",\"pin\":\"real\"}", "pin"));
        assertNull(Json.string(null, "pin"));
    }

    private static final class MapStore implements Auth.Store {
        final Map<String, String> m = new HashMap<>();

        @Override
        public String get(String key) {
            return m.get(key);
        }

        @Override
        public void put(String key, String value) {
            m.put(key, value);
        }
    }

    @Test
    public void pairingGivesAKeyAndKeepsOnlyItsHash() {
        MapStore store = new MapStore();
        Auth auth = new Auth(store, new SecureRandom());
        assertTrue(auth.pin().matches("\\d{6}"));
        Auth.Result r = auth.pair(auth.pin(), 0);
        assertNotNull(r.key);
        assertEquals(48, r.key.length());
        assertTrue(auth.allowed(r.key));
        assertFalse(store.m.get("keys").contains(r.key));
        assertFalse(auth.allowed(r.key.substring(1) + "0"));
        assertFalse(auth.allowed(null));
        assertEquals(1, auth.phones());
        // The PIN and keys survive a restart.
        Auth again = new Auth(store, new SecureRandom());
        assertTrue(again.allowed(r.key));
    }

    @Test
    public void wrongPinsStartAGrowingWait() {
        Auth auth = new Auth(new MapStore(), new SecureRandom());
        String wrong = auth.pin().equals("000000") ? "111111" : "000000";
        for (int i = 1; i < Auth.FREE_TRIES; i++) assertEquals(0, auth.pair(wrong, 0).waitMs);
        assertEquals(Auth.FIRST_WAIT_MS, auth.pair(wrong, 0).waitMs);
        // Even the right PIN waits.
        Auth.Result locked = auth.pair(auth.pin(), 1000);
        assertNull(locked.key);
        assertEquals(Auth.FIRST_WAIT_MS - 1000, locked.waitMs);
        assertEquals(2 * Auth.FIRST_WAIT_MS, auth.pair(wrong, Auth.FIRST_WAIT_MS).waitMs);
        long t = 10 * Auth.MAX_WAIT_MS;
        for (int i = 0; i < 12; i++, t += 2 * Auth.MAX_WAIT_MS) assertTrue(auth.pair(wrong, t).waitMs <= Auth.MAX_WAIT_MS);
        assertNotNull(auth.pair(auth.pin(), t + Auth.MAX_WAIT_MS).key);
    }

    @Test
    public void newPinSignsEveryoneOut() {
        Auth auth = new Auth(new MapStore(), new SecureRandom());
        String key = auth.pair(auth.pin(), 0).key;
        auth.newPin();
        assertFalse(auth.allowed(key));
        assertEquals(0, auth.phones());
    }

    @Test
    public void keepsTheNewestPhones() {
        Auth auth = new Auth(new MapStore(), new SecureRandom());
        String first = auth.pair(auth.pin(), 0).key;
        for (int i = 0; i < Auth.MAX_PHONES; i++) auth.pair(auth.pin(), 0);
        assertFalse(auth.allowed(first));
        assertEquals(Auth.MAX_PHONES, auth.phones());
    }

    @Test
    public void namesThePageAfterTheTv() {
        byte[] page = "<title>%TV% - Webmote</title>".getBytes(StandardCharsets.UTF_8);
        assertEquals("<title>Max's &lt;TV&gt; &amp; &quot;more&quot; - Webmote</title>",
                new String(Server.named(page, Server.html("Max's <TV> & \"more\"")), StandardCharsets.UTF_8));
    }

    @Test
    public void fillsInTheKindOfDevice() {
        byte[] page = "<html data-kind=\"%KIND%\"><b>%TV%</b>".getBytes(StandardCharsets.UTF_8);
        assertEquals("<html data-kind=\"touch\"><b>%KIND%</b>",
                new String(Server.named(page, "%KIND%", "touch"), StandardCharsets.UTF_8));
    }

    @Test
    public void makesAQrCodeOfTheAddress() {
        boolean[][] v2 = Qr.encode("http://192.168.43.1:8080");
        assertEquals(25, v2.length);
        // Finder pattern corners and centres, the separator, and the dark module by the bottom-left finder.
        for (int[] c : new int[][]{{0, 0}, {6, 6}, {3, 3}, {0, 18}, {3, 21}, {18, 0}, {21, 3}}) assertTrue(v2[c[1]][c[0]]);
        assertFalse(v2[7][7]);
        assertFalse(v2[1][1]);
        assertTrue(v2[25 - 8][8]);
        // Timing pattern.
        for (int i = 8; i < 17; i++) assertEquals(i % 2 == 0, v2[6][i]);
        assertEquals(21, Qr.encode("hi").length);
        assertEquals(29, Qr.encode("http://255.255.255.255:65535").length);
        assertNull(Qr.encode("http://192.168.100.200:8080/a-much-longer-path-than-fits"));
    }

    @Test
    public void knowsWifiAndHotspotInterfaces() {
        for (String n : new String[]{"wlan0", "wlan1", "ap0", "swlan0", "softap0"}) assertTrue(n, Net.isWifi(n));
        for (String n : new String[]{"rmnet_data0", "eth0", "lo", "tun0", null}) assertFalse(String.valueOf(n), Net.isWifi(n));
    }
}
