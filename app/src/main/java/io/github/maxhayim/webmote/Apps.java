package io.github.maxhayim.webmote;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The TV's apps for the page's Apps sheet: those with a TV launcher entry, by name, with their banners. */
final class Apps {
    private static final LruCache<String, byte[]> icons = new LruCache<String, byte[]>(4 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, byte[] value) {
            return value.length;
        }
    };

    private Apps() {
    }

    private static List<ResolveInfo> tvApps(Context c) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = c.getPackageManager().queryIntentActivities(i, 0);
        List<ResolveInfo> out = new ArrayList<>();
        for (ResolveInfo r : found) if (!r.activityInfo.packageName.equals(c.getPackageName())) out.add(r);
        return out;
    }

    static boolean isTvApp(Context c, String pkg) {
        for (ResolveInfo r : tvApps(c)) if (r.activityInfo.packageName.equals(pkg)) return true;
        return false;
    }

    static String json(Context c) {
        PackageManager pm = c.getPackageManager();
        List<String[]> apps = new ArrayList<>();
        for (ResolveInfo r : tvApps(c)) apps.add(new String[]{r.loadLabel(pm).toString(), r.activityInfo.packageName});
        Collections.sort(apps, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        StringBuilder b = new StringBuilder("[");
        for (String[] a : apps) {
            if (b.length() > 1) b.append(',');
            b.append("{\"label\":").append(Json.quote(a[0])).append(",\"pkg\":").append(Json.quote(a[1])).append('}');
        }
        return b.append(']').toString();
    }

    /** The app's banner (or icon) as a PNG of at most 320 by 180, or null for an app that isn't a TV app. */
    static byte[] icon(Context c, String pkg) {
        byte[] cached = icons.get(pkg);
        if (cached != null) return cached;
        PackageManager pm = c.getPackageManager();
        for (ResolveInfo r : tvApps(c)) {
            if (!r.activityInfo.packageName.equals(pkg)) continue;
            Drawable d = r.activityInfo.loadBanner(pm);
            if (d == null) d = r.activityInfo.applicationInfo.loadBanner(pm);
            if (d == null) d = r.loadIcon(pm);
            int w = Math.max(1, d.getIntrinsicWidth()), h = Math.max(1, d.getIntrinsicHeight());
            float scale = Math.min(1f, Math.min(320f / w, 180f / h));
            int bw = Math.max(1, Math.round(w * scale)), bh = Math.max(1, Math.round(h * scale));
            Bitmap bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, bw, bh);
            d.draw(new Canvas(bmp));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
            bmp.recycle();
            byte[] png = out.toByteArray();
            icons.put(pkg, png);
            return png;
        }
        return null;
    }
}
