package com.nfsmw.androidevolved;

import android.os.Environment;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Detects the NFSMW game data folder on the phone's internal/shared storage.
 * The default location is {@code /storage/emulated/0/nfsmw}, but users may place the
 * folder in other common internal-storage locations or with different letter casing.
 */
public final class GamePaths {
    private static final String FOLDER = "nfsmw";
    private static final String[] REQUIRED_DIRS = {"GLOBAL", "TRACKS", "CARS", "FRONTEND", "LANGUAGES"};
    private static volatile String cachedPath;
    private static volatile long cachedAtMs;

    private GamePaths() {}

    /** Absolute path of the detected game folder, or the default path if none is found yet. */
    public static synchronized String getGameRootPath() {
        File detected = detectGameRoot();
        return detected != null ? detected.getAbsolutePath() : defaultGameRoot().getAbsolutePath();
    }

    /** Detected game folder, or the default location when nothing named nfsmw is found. */
    public static synchronized File getGameRoot() {
        File detected = detectGameRoot();
        return detected != null ? detected : defaultGameRoot();
    }

    public static File getModsRoot() {
        return new File(getGameRoot(), "Mods");
    }

    public static synchronized void clearCache() {
        cachedPath = null;
        cachedAtMs = 0;
    }

    private static File defaultGameRoot() {
        return new File(Environment.getExternalStorageDirectory(), FOLDER);
    }

    /**
     * Finds the best nfsmw folder in internal storage. Prefers a complete/ready installation,
     * then any folder that contains the game executable, then any existing nfsmw folder.
     */
    public static synchronized File detectGameRoot() {
        long now = System.currentTimeMillis();
        if (cachedPath != null && now - cachedAtMs < 3000) {
            File cached = new File(cachedPath);
            if (cached.isDirectory()) return cached;
            cachedPath = null;
        }

        File best = null;
        int bestScore = -1;
        for (File candidate : candidates()) {
            if (candidate == null || !candidate.isDirectory()) continue;
            int score = score(candidate);
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
                if (bestScore >= 100) break; // complete installation
            }
        }

        cachedPath = best != null ? best.getAbsolutePath() : null;
        cachedAtMs = now;
        return best;
    }

    /** True when the detected/default folder looks ready to launch. */
    public static boolean isReady(File root) {
        return root != null && hasGameExe(root) && hasLanguage(root) && score(root) >= 100;
    }

    public static boolean hasGameExe(File root) {
        return root != null && (new File(root, "Speed.exe").isFile() || new File(root, "speed.exe").isFile());
    }

    public static boolean hasLanguage(File root) {
        if (root == null) return false;
        File languages = new File(root, "LANGUAGES");
        if (!languages.isDirectory()) return false;
        File[] files = languages.listFiles();
        if (files == null) return false;
        for (File file : files) {
            String name = file.getName().toLowerCase();
            if (file.isFile() && name.endsWith(".bin")) return true;
        }
        return false;
    }

    private static int score(File root) {
        int score = 0;
        if (hasGameExe(root)) score += 40;
        for (String dir : REQUIRED_DIRS) {
            if (new File(root, dir).isDirectory()) score += 10;
        }
        if (hasLanguage(root)) score += 10;
        // If it only has the folder name but no expected content, keep it low so a better
        // candidate elsewhere can win, while still allowing the launcher to point at it.
        if (score == 0) score = 1;
        return score;
    }

    private static List<File> candidates() {
        List<File> out = new ArrayList<>();
        File primary = defaultGameRoot();
        add(out, primary);

        File external = Environment.getExternalStorageDirectory();
        if (external != null) {
            add(out, new File(external, FOLDER));
            addCaseInsensitiveChild(out, external, FOLDER);
            File[] children = external.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (child != null && child.isDirectory() && FOLDER.equalsIgnoreCase(child.getName())) {
                        add(out, child);
                    }
                }
            }
        }

        add(out, new File("/sdcard/" + FOLDER));
        add(out, new File("/storage/emulated/0/" + FOLDER));
        add(out, new File("/mnt/sdcard/" + FOLDER));

        // Common user mistake: placed inside another folder at storage root (e.g. Download/nfsmw).
        if (external != null) {
            File[] children = external.listFiles();
            if (children != null) {
                Arrays.sort(children, Comparator.comparing(File::getName));
                for (File child : children) {
                    if (child == null || !child.isDirectory()) continue;
                    if (FOLDER.equalsIgnoreCase(child.getName())) continue;
                    File nested = new File(child, FOLDER);
                    if (nested.isDirectory()) add(out, nested);
                    addCaseInsensitiveChild(out, child, FOLDER);
                }
            }
        }
        return out;
    }

    private static void addCaseInsensitiveChild(List<File> out, File parent, String name) {
        if (parent == null || !parent.isDirectory()) return;
        File[] children = parent.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child != null && child.isDirectory() && name.equalsIgnoreCase(child.getName())) add(out, child);
        }
    }

    private static void add(List<File> out, File file) {
        if (file == null) return;
        try {
            file = file.getAbsoluteFile();
        } catch (Exception ignored) {
        }
        String path = file.getAbsolutePath();
        for (File existing : out) {
            if (existing.getAbsolutePath().equals(path)) return;
        }
        out.add(file);
    }
}
