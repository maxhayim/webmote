package io.github.maxhayim.webmote;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Pairing: the TV shows a six-digit PIN; a phone that sends it gets a long random key, and every API call after that
 * carries the key. Only a hash of each key is kept. Five wrong PINs start a wait, 30 seconds and doubling up to five
 * minutes. A new PIN signs every phone out.
 */
final class Auth {
    static final int MAX_PHONES = 20;
    static final int FREE_TRIES = 5;
    static final long FIRST_WAIT_MS = 30_000;
    static final long MAX_WAIT_MS = 5 * 60_000;

    /** Where the PIN and key hashes are kept: SharedPreferences on the TV, a map in tests. */
    interface Store {
        String get(String key);

        void put(String key, String value);
    }

    static final class Result {
        final String key;
        final long waitMs;

        Result(String key, long waitMs) {
            this.key = key;
            this.waitMs = waitMs;
        }
    }

    private final Store store;
    private final SecureRandom random;
    private int wrong;
    private long lockedUntil;

    Auth(Store store, SecureRandom random) {
        this.store = store;
        this.random = random;
        if (pin() == null) newPin();
    }

    String pin() {
        return store.get("pin");
    }

    /** A fresh PIN, and every paired phone forgotten. */
    synchronized void newPin() {
        store.put("pin", String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000)));
        store.put("keys", "");
        wrong = 0;
        lockedUntil = 0;
    }

    synchronized int phones() {
        return keys().size();
    }

    /** A new key when {@code pin} is right; else no key, and how long to wait before the next try (0: none). */
    synchronized Result pair(String pin, long now) {
        if (now < lockedUntil) return new Result(null, lockedUntil - now);
        String want = pin();
        if (pin != null && want != null && MessageDigest.isEqual(pin.getBytes(StandardCharsets.UTF_8), want.getBytes(StandardCharsets.UTF_8))) {
            wrong = 0;
            byte[] raw = new byte[24];
            random.nextBytes(raw);
            String key = hex(raw);
            List<String> keys = keys();
            keys.add(hash(key));
            while (keys.size() > MAX_PHONES) keys.remove(0);
            store.put("keys", String.join(",", keys));
            return new Result(key, 0);
        }
        wrong++;
        if (wrong >= FREE_TRIES) {
            long wait = FIRST_WAIT_MS << Math.min(wrong - FREE_TRIES, 10);
            lockedUntil = now + Math.min(wait, MAX_WAIT_MS);
            return new Result(null, lockedUntil - now);
        }
        return new Result(null, 0);
    }

    synchronized boolean allowed(String key) {
        if (key == null || key.length() != 48) return false;
        String h = hash(key);
        for (String k : keys()) {
            if (MessageDigest.isEqual(k.getBytes(StandardCharsets.US_ASCII), h.getBytes(StandardCharsets.US_ASCII))) return true;
        }
        return false;
    }

    private List<String> keys() {
        String s = store.get("keys");
        List<String> out = new ArrayList<>();
        if (s != null && !s.isEmpty()) out.addAll(Arrays.asList(s.split(",")));
        return out;
    }

    static String hash(String s) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
        return s.toString();
    }
}
