package com.nfsmw.androidevolved;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageManager;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * Driver/GPU probe. In full builds this runs in a disposable process and uses the native Vulkan
 * diagnostics (adrenotools/custom driver hooks). In launcher-only builds (no native libs/runtime),
 * it returns a best-effort Java report (device/GPU renderer and Vulkan feature support) so the UI
 * can still show useful GPU information.
 */
public final class GpuProbeActivity extends Activity {
    private static boolean diagnosticsLoaded;
    static {
        try { System.loadLibrary("nfsmw_diagnostics"); diagnosticsLoaded = true; }
        catch (UnsatisfiedLinkError | SecurityException e) { diagnosticsLoaded = false; }
    }
    private static native String nativeGpuReport(String hooks, String temp, String directory, String library);
    private boolean finished;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView status = new TextView(this);
        status.setText("Comprobando GPU/Vulkan…");
        status.setTextColor(0xFFFFFFFF); status.setTextSize(18);
        status.setGravity(android.view.Gravity.CENTER); status.setBackgroundColor(0xFF0B0F14);
        setContentView(status);
        Handler main = new Handler(Looper.getMainLooper());
        main.postDelayed(() -> finishReport("{\"probeError\":\"La prueba del driver excedió 20 segundos\",\"driverLoadFailed\":true}"), 20000);
        String[] loader = GpuDrivers.loaderArguments(this);
        new Thread(() -> {
            String result;
            if (!diagnosticsLoaded) {
                result = javaGpuReport();
            } else {
                try { result = nativeGpuReport(loader[0], loader[1], loader[2], loader[3]); }
                catch (Exception | LinkageError error) {
                    result = "{\"probeError\":\"No se pudo ejecutar la prueba: " + error.getClass().getSimpleName() + "\",\"driverLoadFailed\":true}";
                }
            }
            String report = result;
            main.post(() -> finishReport(report));
        }, "VulkanDriverProbe").start();
    }

    private String javaGpuReport() {
        try {
            JSONObject json = new JSONObject();
            String gpu = glRenderer();
            if (gpu == null || gpu.trim().isEmpty()) {
                gpu = Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.HARDWARE + ")";
            }
            json.put("gpu", gpu);

            String vulkan = vulkanSummary();
            json.put("vulkan", vulkan);

            GpuDrivers.Driver driver = GpuDrivers.selected(this);
            json.put("driver", driver == null ? "Sistema" : driver.name);
            json.put("driverName", driver == null ? "Sistema" : driver.name);
            json.put("driverInfo", "(información del sistema; diagnóstico nativo no disponible en esta compilación)");

            // Unknown from Java-only probe; keep conservative/neutral values.
            json.put("bcTextures", false);
            json.put("vertexFormatsMissing", "");
            json.put("compatible", vulkanSupported());
            json.put("missing", new org.json.JSONArray());
            json.put("probeNote", "Launcher-only: sin prueba nativa de driver/Vulkan del runtime del juego.");
            return json.toString();
        } catch (Exception e) {
            return "{\"probeError\":\"No se pudo obtener información de la GPU: " + e.getClass().getSimpleName() + "\",\"driverLoadFailed\":true}";
        }
    }

    private boolean vulkanSupported() {
        PackageManager pm = getPackageManager();
        return pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
                || pm.hasSystemFeature("android.hardware.vulkan.version");
    }

    private String vulkanSummary() {
        try {
            PackageManager pm = getPackageManager();
            boolean hasLevel = pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL);
            boolean hasVersion = pm.hasSystemFeature("android.hardware.vulkan.version");
            if (!hasLevel && !hasVersion) return "No reportado por el sistema";

            int level = 0;
            int version = 0;
            FeatureInfo[] features = pm.getSystemAvailableFeatures();
            if (features != null) {
                for (FeatureInfo f : features) {
                    if (f == null || f.name == null) continue;
                    if ("android.hardware.vulkan.level".equals(f.name)) level = f.version;
                    else if ("android.hardware.vulkan.version".equals(f.name)) version = f.version;
                }
            }
            StringBuilder sb = new StringBuilder();
            if (hasVersion || version != 0) {
                sb.append("API ").append(vkMajor(version)).append('.').append(vkMinor(version)).append('.').append(vkPatch(version));
            } else {
                sb.append("soportado");
            }
            if (hasLevel || level != 0) sb.append(" (nivel ").append(level).append(')');
            sb.append(" — compatible");
            return sb.toString();
        } catch (Exception e) {
            return "Información no disponible";
        }
    }

    private static int vkMajor(int v) { return (v >>> 22) & 0x3ff; }
    private static int vkMinor(int v) { return (v >>> 12) & 0x3ff; }
    private static int vkPatch(int v) { return v & 0xfff; }

    private String glRenderer() {
        EGLDisplay dpy = null;
        EGLContext ctx = EGL14.EGL_NO_CONTEXT;
        EGLSurface surf = EGL14.EGL_NO_SURFACE;
        try {
            dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (dpy == null || dpy == EGL14.EGL_NO_DISPLAY) return null;
            int[] ver = new int[2];
            if (!EGL14.eglInitialize(dpy, ver, 0, ver, 1)) return null;
            int[] cfgAttribs = {
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] num = new int[1];
            if (!EGL14.eglChooseConfig(dpy, cfgAttribs, 0, configs, 0, 1, num, 0) || num[0] <= 0) return null;
            int[] ctxAttribs = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE };
            ctx = EGL14.eglCreateContext(dpy, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0);
            if (ctx == null || ctx == EGL14.EGL_NO_CONTEXT) return null;
            int[] surfAttribs = { EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE };
            surf = EGL14.eglCreatePbufferSurface(dpy, configs[0], surfAttribs, 0);
            if (surf == null || surf == EGL14.EGL_NO_SURFACE) return null;
            if (!EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) return null;
            String renderer = GLES20.glGetString(GLES20.GL_RENDERER);
            String vendor = GLES20.glGetString(GLES20.GL_VENDOR);
            String version = GLES20.glGetString(GLES20.GL_VERSION);
            StringBuilder sb = new StringBuilder();
            if (renderer != null) sb.append(renderer.trim());
            if (vendor != null && !vendor.trim().isEmpty() && (renderer == null || !renderer.contains(vendor.trim()))) {
                if (sb.length() > 0) sb.append(" (").append(vendor.trim()).append(')');
                else sb.append(vendor.trim());
            }
            if (version != null && !version.trim().isEmpty()) sb.append(" — ").append(version.trim());
            return sb.length() == 0 ? null : sb.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                if (dpy != null) {
                    EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                    if (ctx != null && ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx);
                    if (surf != null && surf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, surf);
                    EGL14.eglTerminate(dpy);
                }
            } catch (Throwable ignored) { }
        }
    }

    private void finishReport(String report) {
        if (finished) return;
        finished = true;
        setResult(RESULT_OK, new Intent().putExtra("gpu_report", report));
        finish();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        // Vulkan handles and injected namespace hooks are owned by this disposable process.
        Process.killProcess(Process.myPid());
    }
}
