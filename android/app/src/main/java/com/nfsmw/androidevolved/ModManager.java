package com.nfsmw.androidevolved;

import android.content.Context;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * File-overlay mod manager for NFSMW Android Evolved.
 * <p>
 * Mods live under {@code nfsmw/Mods/<folder>/} mirroring the game data layout (GLOBAL, CARS,
 * TEXTURES, etc.). Enabled mods are listed in priority order in {@code Mods/mods.json} and a
 * companion {@code Mods/mods.conf} read by the native guest runtime before the game boots.
 * Higher-priority mods (earlier in the list) override lower-priority mods and base game files.
 */
public final class ModManager {
    public static final class Mod {
        public final String folder;   // directory name inside Mods/
        public String name;           // display name
        public boolean enabled;
        public String description;
        public String version;
        public String author;
        public String category;       // e.g. graphics, textures, cars, vinyls, bodykits, other

        public Mod(String folder) {
            this.folder = folder;
            this.name = folder;
        }

        File dir(File modsRoot) { return new File(modsRoot, folder); }
    }

    private ModManager() {}

    public static File gameRoot() {
        return GamePaths.getGameRoot();
    }

    public static File modsRoot() {
        return new File(gameRoot(), "Mods");
    }

    public static List<Mod> loadMods() {
        File root = modsRoot();
        List<Mod> mods = new ArrayList<>();
        if (!root.isDirectory()) return mods;

        // Read config for order/enabled state; discover unlisted folders as disabled.
        List<Mod> ordered = readConfig(root);
        java.util.LinkedHashMap<String, Mod> byFolder = new java.util.LinkedHashMap<>();
        for (Mod m : ordered) byFolder.put(m.folder.toLowerCase(java.util.Locale.ROOT), m);

        File[] children = root.listFiles();
        if (children != null) {
            List<File> dirs = new ArrayList<>();
            for (File c : children) if (c.isDirectory()) dirs.add(c);
            Collections.sort(dirs, Comparator.comparing(f -> f.getName().toLowerCase(java.util.Locale.ROOT)));
            for (File d : dirs) {
                String key = d.getName().toLowerCase(java.util.Locale.ROOT);
                if (!byFolder.containsKey(key)) {
                    Mod m = new Mod(d.getName());
                    m.enabled = false;
                    readMetadata(m, d);
                    byFolder.put(key, m);
                } else {
                    readMetadata(byFolder.get(key), d);
                }
            }
        }
        mods.addAll(byFolder.values());
        // Enabled mods keep config order at front; disabled mods follow alphabetically.
        Collections.sort(mods, (a, b) -> {
            if (a.enabled != b.enabled) return a.enabled ? -1 : 1;
            if (a.enabled) return 0; // preserve relative order of enabled (already in config order)
            return a.name.toLowerCase(java.util.Locale.ROOT).compareTo(b.name.toLowerCase(java.util.Locale.ROOT));
        });
        // Re-stabilize enabled order based on config sequence.
        List<Mod> result = new ArrayList<>();
        for (Mod m : ordered) {
            Mod live = byFolder.get(m.folder.toLowerCase(java.util.Locale.ROOT));
            if (live != null && live.enabled) result.add(live);
        }
        for (Mod m : mods) if (!m.enabled) result.add(m);
        return result;
    }

    private static boolean isSafeFolderName(String folder) {
        if (folder == null) return false;
        String f = folder.trim();
        if (f.isEmpty() || f.length() > 128) return false;
        if (f.contains("/") || f.contains("\\") || f.contains("..") || f.equals(".") ) return false;
        return true;
    }

    private static List<Mod> readConfig(File modsRoot) {
        List<Mod> list = new ArrayList<>();
        File json = new File(modsRoot, "mods.json");
        if (json.isFile()) {
            try {
                StringBuilder sb = new StringBuilder();
                BufferedReader br = new BufferedReader(new FileReader(json));
                String line; while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                JSONObject obj = new JSONObject(sb.toString());
                JSONArray enabled = obj.optJSONArray("enabled");
                if (enabled != null) {
                    for (int i = 0; i < enabled.length(); i++) {
                        Object item = enabled.get(i);
                        String folder = item instanceof String ? (String) item
                                : (item instanceof JSONObject ? ((JSONObject) item).optString("folder", null) : null);
                        if (!isSafeFolderName(folder)) continue;
                        folder = folder.trim();
                        Mod m = new Mod(folder);
                        if (item instanceof JSONObject) {
                            JSONObject o = (JSONObject) item;
                            String nm = o.optString("name", m.folder);
                            m.name = (nm == null || nm.trim().isEmpty()) ? m.folder : nm;
                            m.enabled = o.optBoolean("enabled", true);
                        } else {
                            m.enabled = true;
                        }
                        list.add(m);
                    }
                }
            } catch (Exception ignored) { }
        }
        if (list.isEmpty()) {
            // Fallback: mods.conf / enabled.txt line-based format.
            for (String name : new String[] {"mods.conf", "enabled.txt"}) {
                File f = new File(modsRoot, name);
                if (!f.isFile()) continue;
                try {
                    BufferedReader br = new BufferedReader(new FileReader(f));
                    String line;
                    while ((line = br.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("#")) continue;
                        if (!isSafeFolderName(line)) continue;
                        Mod m = new Mod(line);
                        m.enabled = true;
                        list.add(m);
                    }
                    br.close();
                    break;
                } catch (Exception ignored) { }
            }
        }
        return list;
    }

    public static void saveConfig(List<Mod> mods) {
        File root = modsRoot();
        if (!root.exists()) root.mkdirs();
        try {
            JSONObject obj = new JSONObject();
            JSONArray enabled = new JSONArray();
            for (Mod m : mods) {
                if (!m.enabled) continue;
                JSONObject o = new JSONObject();
                o.put("folder", m.folder);
                o.put("name", m.name);
                o.put("enabled", true);
                enabled.put(o);
            }
            obj.put("enabled", enabled);
            BufferedWriter bw = new BufferedWriter(new FileWriter(new File(root, "mods.json")));
            bw.write(obj.toString(2));
            bw.close();
        } catch (Exception ignored) { }
        // Write native-readable mods.conf (highest priority first).
        try {
            BufferedWriter bw = new BufferedWriter(new FileWriter(new File(root, "mods.conf")));
            bw.write("# Enabled NFSMW mods, one folder per line (highest priority first).\n");
            for (Mod m : mods) if (m.enabled) { bw.write(m.folder); bw.newLine(); }
            bw.close();
        } catch (Exception ignored) { }
    }

    private static void readMetadata(Mod mod, File dir) {
        File meta = new File(dir, "mod.json");
        if (!meta.isFile()) meta = new File(dir, "nfsmw_mod.json");
        if (!meta.isFile()) return;
        try {
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new FileReader(meta));
            String line; while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            JSONObject o = new JSONObject(sb.toString());
            if (o.has("name")) mod.name = o.optString("name", mod.name);
            mod.description = o.optString("description", mod.description);
            mod.version = o.optString("version", mod.version);
            mod.author = o.optString("author", mod.author);
            mod.category = o.optString("category", mod.category);
        } catch (Exception ignored) { }
    }

    /** Imports a mod zip from a content URI into Mods/<name>/ and registers it (disabled by default). */
    public static Mod importZip(Context context, Uri uri, String displayName) throws Exception {
        File root = modsRoot();
        if (!root.exists() && !root.mkdirs()) throw new Exception("No se pudo crear la carpeta Mods");

        String base = sanitizeName(displayName != null ? stripZipExt(displayName) : "mod");
        if (base.isEmpty()) base = "mod";
        String folder = uniqueFolderName(root, base);
        File dest = new File(root, folder);
        if (!dest.mkdirs()) throw new Exception("No se pudo crear la carpeta del mod");

        InputStream in = context.getContentResolver().openInputStream(uri);
        if (in == null) throw new Exception("No se pudo abrir el archivo");
        try {
            unzip(in, dest);
        } catch (Exception e) {
            deleteRecursive(dest);
            throw e;
        } finally {
            try { in.close(); } catch (Exception ignored) { }
        }

        // If zip had a single top-level folder, flatten it for convenience.
        flattenSingleRoot(dest);

        Mod mod = new Mod(folder);
        mod.name = folder;
        mod.enabled = false;
        readMetadata(mod, dest);
        List<Mod> mods = loadMods();
        boolean exists = false;
        for (Mod m : mods) if (m.folder.equalsIgnoreCase(folder)) { exists = true; break; }
        if (!exists) mods.add(mod);
        saveConfig(mods);
        return mod;
    }

    public static void deleteMod(Mod mod) {
        List<Mod> mods = loadMods();
        List<Mod> remaining = new ArrayList<>();
        for (Mod m : mods) if (!m.folder.equalsIgnoreCase(mod.folder)) remaining.add(m);
        saveConfig(remaining);
        deleteRecursive(new File(modsRoot(), mod.folder));
    }

    private static void unzip(InputStream in, File destDir) throws Exception {
        ZipInputStream zis = new ZipInputStream(new BufferedInputStream(in));
        byte[] buf = new byte[8192];
        ZipEntry entry;
        String destPath = destDir.getCanonicalPath();
        while ((entry = zis.getNextEntry()) != null) {
            String name = entry.getName();
            if (name == null) continue;
            name = name.replace('\\', '/');
            if (name.startsWith("/") || name.contains("../") || name.contains("..\\")) {
                throw new Exception("Archivo ZIP no seguro: " + name);
            }
            File out = new File(destDir, name);
            String outPath = out.getCanonicalPath();
            if (!outPath.equals(destPath) && !outPath.startsWith(destPath + File.separator)) {
                throw new Exception("Archivo ZIP fuera de destino: " + name);
            }
            if (entry.isDirectory()) {
                out.mkdirs();
            } else {
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();
                OutputStream os = new BufferedOutputStream(new FileOutputStream(out));
                int n; while ((n = zis.read(buf)) > 0) os.write(buf, 0, n);
                os.close();
            }
            zis.closeEntry();
        }
        zis.close();
    }

    private static void flattenSingleRoot(File dest) {
        File[] children = dest.listFiles();
        if (children == null) return;
        List<File> visible = new ArrayList<>();
        for (File c : children) {
            String n = c.getName();
            if (n.equals("mod.json") || n.equals("nfsmw_mod.json") || n.equals("__MACOSX")) continue;
            visible.add(c);
        }
        if (visible.size() != 1 || !visible.get(0).isDirectory()) return;
        File inner = visible.get(0);
        File[] innerChildren = inner.listFiles();
        if (innerChildren == null) return;
        for (File c : innerChildren) {
            File target = new File(dest, c.getName());
            if (!target.exists()) c.renameTo(target);
        }
        // remove empty inner dir
        File[] left = inner.listFiles();
        if (left == null || left.length == 0) inner.delete();
    }

    private static String sanitizeName(String name) {
        StringBuilder sb = new StringBuilder();
        for (char c : name.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == ' ' || c == '.') sb.append(c);
            else sb.append('_');
        }
        String s = sb.toString().trim();
        while (s.startsWith(".")) s = s.substring(1).trim();
        return s.length() > 60 ? s.substring(0, 60).trim() : s;
    }

    private static String stripZipExt(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".zip")) return name.substring(0, name.length() - 4);
        return name;
    }

    private static String uniqueFolderName(File root, String base) {
        String name = base;
        int i = 2;
        while (new File(root, name).exists()) name = base + " (" + i++ + ")";
        return name;
    }

    public static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
        }
        f.delete();
    }

    public static String describeMod(Mod mod) {
        StringBuilder sb = new StringBuilder();
        if (mod.category != null && !mod.category.isEmpty()) sb.append(mod.category);
        if (mod.version != null && !mod.version.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("v").append(mod.version);
        }
        if (mod.author != null && !mod.author.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(mod.author);
        }
        return sb.toString();
    }
}
