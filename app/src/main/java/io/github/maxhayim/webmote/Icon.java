package io.github.maxhayim.webmote;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * The home-screen icon, drawn rather than shipped: a D-pad ring with its OK button on a dark square that reaches the
 * edges, so phones can round or mask it their own way.
 */
final class Icon {
    private static final Map<Integer, byte[]> cache = new HashMap<>();

    private Icon() {
    }

    static synchronized byte[] png(int size) {
        byte[] cached = cache.get(size);
        if (cached != null) return cached;
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, size, 0xFF262B38, 0xFF12151C, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, size, size, p);
        p.setShader(null);
        float cx = size / 2f, cy = size / 2f;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(size * 0.075f);
        p.setColor(0xFF3A4152);
        c.drawCircle(cx, cy, size * 0.27f, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF8FA8FF);
        c.drawCircle(cx, cy, size * 0.13f, p);
        p.setColor(0xFFE6E9F2);
        float d = size * 0.27f, r = size * 0.026f;
        c.drawCircle(cx, cy - d, r, p);
        c.drawCircle(cx, cy + d, r, p);
        c.drawCircle(cx - d, cy, r, p);
        c.drawCircle(cx + d, cy, r, p);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, out);
        b.recycle();
        byte[] png = out.toByteArray();
        cache.put(size, png);
        return png;
    }
}
