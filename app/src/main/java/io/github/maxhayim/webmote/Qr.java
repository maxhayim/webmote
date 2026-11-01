package io.github.maxhayim.webmote;

import java.nio.charset.StandardCharsets;

/**
 * A QR code for the page's address, so a phone can open it by pointing its camera at the screen. Just enough of QR
 * for a short link: byte mode, error correction level M, versions 1 to 3 (up to 42 bytes), one fixed mask.
 */
final class Qr {
    /** Data and error-correction codewords for versions 1 to 3 at level M (one block each). */
    private static final int[] DATA = {0, 16, 28, 44};
    private static final int[] EC = {0, 10, 16, 26};

    private final int size;
    private final boolean[][] dark;     // [y][x]
    private final boolean[][] reserved; // function patterns and format bits, which the data and mask skip

    private Qr(int version) {
        size = 17 + 4 * version;
        dark = new boolean[size][size];
        reserved = new boolean[size][size];
    }

    /** The modules, true for dark, [row][column], without the quiet zone; null when {@code text} is too long. */
    static boolean[][] encode(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        int version = 1;
        while (version <= 3 && bytes.length + 2 > DATA[version]) version++;
        if (version > 3) return null;
        Qr q = new Qr(version);
        q.functionPatterns(version);
        byte[] data = q.dataCodewords(bytes, DATA[version]);
        byte[] ec = remainder(data, divisor(EC[version]));
        byte[] all = new byte[data.length + ec.length];
        System.arraycopy(data, 0, all, 0, data.length);
        System.arraycopy(ec, 0, all, data.length, ec.length);
        q.place(all);
        q.mask();
        q.formatBits();
        return q.dark;
    }

    private void functionPatterns(int version) {
        for (int i = 0; i < size; i++) {
            set(6, i, i % 2 == 0);
            set(i, 6, i % 2 == 0);
        }
        finder(3, 3);
        finder(size - 4, 3);
        finder(3, size - 4);
        if (version > 1) {
            int c = size - 7;
            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -2; dx <= 2; dx++) set(c + dx, c + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
            }
        }
        formatBits(); // reserves the format areas now; written for real after masking
    }

    /** A finder pattern centred on (cx, cy), with its light separator. */
    private void finder(int cx, int cy) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int x = cx + dx, y = cy + dy, d = Math.max(Math.abs(dx), Math.abs(dy));
                if (x >= 0 && x < size && y >= 0 && y < size) set(x, y, d != 2 && d != 4);
            }
        }
    }

    /** Format information for level M and mask 0, in both copies, plus the dark module. */
    private void formatBits() {
        int data = 0; // level M is 00, mask 000
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) set(8, i, bit(bits, i));
        set(8, 7, bit(bits, 6));
        set(8, 8, bit(bits, 7));
        set(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) set(14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) set(size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) set(8, size - 15 + i, bit(bits, i));
        set(8, size - 8, true);
    }

    private byte[] dataCodewords(byte[] bytes, int capacity) {
        int[] bits = new int[capacity * 8];
        int n = 0;
        n = append(bits, n, 0b0100, 4);
        n = append(bits, n, bytes.length, 8);
        for (byte b : bytes) n = append(bits, n, b & 0xFF, 8);
        n = Math.min(n + 4, capacity * 8); // terminator
        n = (n + 7) / 8 * 8;
        for (int pad = 0xEC; n < capacity * 8; pad ^= 0xEC ^ 0x11) n = append(bits, n, pad, 8);
        byte[] out = new byte[capacity];
        for (int i = 0; i < bits.length; i++) out[i >>> 3] |= (byte) (bits[i] << (7 - (i & 7)));
        return out;
    }

    private static int append(int[] bits, int n, int value, int length) {
        for (int i = length - 1; i >= 0; i--) bits[n++] = (value >>> i) & 1;
        return n;
    }

    /** Codeword bits in the zigzag order, two columns at a time from the bottom right, skipping the timing column. */
    private void place(byte[] codewords) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!reserved[y][x] && i < codewords.length * 8) {
                        dark[y][x] = bit(codewords[i >>> 3], 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    /** Mask 0: flips every module where row + column is even, outside the function patterns. */
    private void mask() {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) if (!reserved[y][x] && (x + y) % 2 == 0) dark[y][x] = !dark[y][x];
        }
    }

    private void set(int x, int y, boolean on) {
        dark[y][x] = on;
        reserved[y][x] = true;
    }

    private static boolean bit(int v, int i) {
        return ((v >>> i) & 1) != 0;
    }

    /** Reed-Solomon generator polynomial of the given degree, over GF(256) with the QR polynomial 0x11D. */
    private static byte[] divisor(int degree) {
        byte[] r = new byte[degree];
        r[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < r.length; j++) {
                r[j] = (byte) multiply(r[j] & 0xFF, root);
                if (j + 1 < r.length) r[j] ^= r[j + 1];
            }
            root = multiply(root, 0x02);
        }
        return r;
    }

    private static byte[] remainder(byte[] data, byte[] divisor) {
        byte[] r = new byte[divisor.length];
        for (byte b : data) {
            int factor = (b ^ r[0]) & 0xFF;
            System.arraycopy(r, 1, r, 0, r.length - 1);
            r[r.length - 1] = 0;
            for (int i = 0; i < r.length; i++) r[i] ^= (byte) multiply(divisor[i] & 0xFF, factor);
        }
        return r;
    }

    private static int multiply(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }
}
