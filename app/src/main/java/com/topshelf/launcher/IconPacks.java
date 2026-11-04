package com.topshelf.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Custom app icons: Android icon packs (the kind made for Nova/ADW launchers) and your own pictures. */
final class IconPacks {
    private static final String[] PACK_ACTIONS = {
        "org.adw.launcher.THEMES", "com.novalauncher.THEME", "com.teslacoilsw.launcher.THEME",
        "org.adw.launcher.icons.ACTION_PICK_ICON", "com.gau.go.launcherex.theme", "com.fede.launcher.THEME_ICONPACK"
    };
    private final Context ctx;
    private final PackageManager pm;
    /** pack package → (component "pkg/cls" or "pkg" → drawable name) */
    private final Map<String, Map<String, String>> filters = new ConcurrentHashMap<>();

    IconPacks(Context ctx) { this.ctx = ctx; this.pm = ctx.getPackageManager(); }

    String list() {
        Map<String, String> found = new LinkedHashMap<>();
        for (String a : PACK_ACTIONS) {
            try {
                List<ResolveInfo> l = pm.queryIntentActivities(new Intent(a), 0);
                for (ResolveInfo ri : l) {
                    String pkg = ri.activityInfo.packageName;
                    if (!found.containsKey(pkg)) found.put(pkg, String.valueOf(ri.activityInfo.applicationInfo.loadLabel(pm)));
                }
            } catch (Exception ignored) { }
        }
        JSONArray out = new JSONArray();
        try { for (Map.Entry<String, String> e : found.entrySet()) out.put(new JSONObject().put("pkg", e.getKey()).put("label", e.getValue())); }
        catch (Exception ignored) { }
        return out.toString();
    }

    private Map<String, String> filter(String pack) {
        Map<String, String> m = filters.get(pack);
        if (m != null) return m;
        m = new HashMap<>();
        try {
            Resources res = pm.getResourcesForApplication(pack);
            XmlPullParser p = null;
            InputStream in = null;
            int id = res.getIdentifier("appfilter", "xml", pack);
            if (id != 0) p = res.getXml(id);
            else {
                in = ctx.createPackageContext(pack, 0).getAssets().open("appfilter.xml");
                p = XmlPullParserFactory.newInstance().newPullParser();
                p.setInput(in, "UTF-8");
            }
            try {
                for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                    if (ev != XmlPullParser.START_TAG || !"item".equals(p.getName())) continue;
                    String comp = p.getAttributeValue(null, "component"), dr = p.getAttributeValue(null, "drawable");
                    if (comp == null || dr == null || !comp.startsWith("ComponentInfo{")) continue;
                    String c = comp.substring(14, comp.length() - (comp.endsWith("}") ? 1 : 0)).trim();
                    if (!m.containsKey(c)) m.put(c, dr);
                    int slash = c.indexOf('/');
                    if (slash > 0 && !m.containsKey(c.substring(0, slash))) m.put(c.substring(0, slash), dr);
                }
            } finally { if (in != null) try { in.close(); } catch (Exception ignored) { } }
        } catch (Exception ignored) { }
        filters.put(pack, m);
        return m;
    }

    /** PNG of an app's icon from a pack, or null when the pack doesn't have one. */
    byte[] packIcon(String pack, String appId) {
        ComponentName cn = ComponentName.unflattenFromString(appId);
        if (cn == null) return null;
        try {
            File dir = new File(ctx.getCacheDir(), "packicons");
            dir.mkdirs();
            long ver = pm.getPackageInfo(pack, 0).lastUpdateTime;
            File f = new File(dir, (pack + "_" + cn.flattenToShortString()).replaceAll("[^A-Za-z0-9._-]", "_") + "_" + ver + ".png");
            if (f.exists()) return f.length() == 0 ? null : Files.readAllBytes(f.toPath());
            Map<String, String> m = filter(pack);
            String name = m.get(cn.getPackageName() + "/" + cn.getClassName());
            if (name == null) name = m.get(cn.getPackageName());
            byte[] out = null;
            if (name != null) {
                Resources res = pm.getResourcesForApplication(pack);
                int id = res.getIdentifier(name, "drawable", pack);
                if (id == 0) id = res.getIdentifier(name, "mipmap", pack);
                if (id != 0) {
                    Drawable d = res.getDrawable(id, null);
                    Bitmap b = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
                    d.setBounds(0, 0, 256, 256);
                    d.draw(new Canvas(b));
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    b.compress(Bitmap.CompressFormat.PNG, 100, bos);
                    b.recycle();
                    out = bos.toByteArray();
                }
            }
            try (FileOutputStream fos = new FileOutputStream(f)) { if (out != null) fos.write(out); } catch (Exception ignored) { }
            return out;
        } catch (Exception e) { return null; }
    }

    // ---- your own pictures -----------------------------------------------------------------

    private File customDir() { File d = new File(ctx.getFilesDir(), "customicons"); d.mkdirs(); return d; }
    private static String slug(String appId) { return Integer.toHexString(appId.hashCode()); }

    String saveCustom(String appId, byte[] data, String ext) {
        try {
            deleteCustom(appId);
            String name = slug(appId) + "-" + System.currentTimeMillis() + "." + ext;
            try (FileOutputStream out = new FileOutputStream(new File(customDir(), name))) { out.write(data); }
            return name;
        } catch (Exception e) { return null; }
    }
    void deleteCustom(String appId) {
        File[] fs = customDir().listFiles();
        if (fs != null) for (File f : fs) if (f.getName().startsWith(slug(appId) + "-")) f.delete();
    }
    void deleteAllCustom() {
        File[] fs = customDir().listFiles();
        if (fs != null) for (File f : fs) f.delete();
    }
    byte[] readCustom(String name) {
        if (name == null || name.contains("/") || name.contains("..")) return null;
        try { File f = new File(customDir(), name); return f.exists() ? Files.readAllBytes(f.toPath()) : null; }
        catch (Exception e) { return null; }
    }
}
