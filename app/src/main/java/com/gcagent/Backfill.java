package com.gcagent;

import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;

/** Root/VD backfill driver: launch QQ on a virtual display, open the target chat,
 *  scroll until the adapter reaches target_oldest_seq, then stop. */
public class Backfill {
    static final String QDIR = "/sdcard/Android/data/com.tencent.mobileqq/files/gcagent";
    static int displayId = -1;
    static VirtualDisplay vd;

    public static void main(String[] args) throws Exception {
        if (android.os.Looper.myLooper() == null) android.os.Looper.prepare();
        int w = 1224, h = 2700, dpi = 520;
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        Context ctx = (Context) at.getMethod("getSystemContext").invoke(thread);
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        ImageReader reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 4);
        int flags = 17985; // PUBLIC|OWN_CONTENT_ONLY|SUPPORTS_TOUCH|TRUSTED|OWN_FOCUS|SHOW_SYSTEM_DECORATIONS
        vd = dm.createVirtualDisplay("gcagent-backfill", w, h, dpi, reader.getSurface(), flags);
        displayId = vd.getDisplay().getDisplayId();
        log("VD_ID=" + displayId + " target=" + target());

        sh("am force-stop com.tencent.mobileqq");
        Thread.sleep(1500);
        sh("am start --display " + displayId + " -n com.tencent.mobileqq/.activity.SplashActivity");
        Thread.sleep(7000);

        int[] ys = {760, 900, 1050, 1200};
        boolean opened = false;
        for (int y : ys) {
            sh("input -d " + displayId + " tap 300 " + y);
            Thread.sleep(3500);
            if (lastRangePeer()) { opened = true; log("opened at y=" + y); break; }
            log("not target at y=" + y);
            sh("input -d " + displayId + " keyevent 4");
            Thread.sleep(1500);
        }
        if (!opened) { log("OPEN_FAILED"); cleanup(); return; }

        int target = target();
        int tries = 0;
        while (tries++ < 40) {
            long min = lastMinSeq();
            log("try=" + tries + " min=" + min);
            if (min > 0 && min <= target) { log("REACHED min=" + min); break; }
            sh("input -d " + displayId + " swipe 612 600 612 2400 150");
            Thread.sleep(2000);
        }
        log("DONE tries=" + tries);
        cleanup();
    }

    static int target() {
        try {
            File f = new File(QDIR, "config.json");
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                String l; while ((l = r.readLine()) != null) sb.append(l);
            }
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"target_oldest_seq\"\\s*:\\s*(\\d+)").matcher(sb.toString());
            return m.find() ? Integer.parseInt(m.group(1)) : 0;
        } catch (Throwable t) { return 0; }
    }

    static boolean lastRangePeer() { return lastMinSeq() >= 0; }

    static long lastMinSeq() {
        String[] lines = tail().split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("range peer=\\d+ min=(\\d+)").matcher(lines[i]);
            if (m.find()) return Long.parseLong(m.group(1));
        }
        return -1;
    }

    static String tail() {
        try {
            File f = new File(QDIR, "gcagent.log");
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r");
            long len = raf.length();
            raf.seek(Math.max(0, len - 8192));
            byte[] b = new byte[(int) (len - raf.getFilePointer())];
            raf.readFully(b); raf.close();
            return new String(b, "UTF-8");
        } catch (Throwable t) { return ""; }
    }

    static void cleanup() {
        try { sh("am force-stop com.tencent.mobileqq"); Thread.sleep(1000); } catch (Throwable ignored) {}
        try { vd.release(); } catch (Throwable ignored) {}
        log("released");
    }

    static void log(String s) {
        System.out.println("BACKFILL " + s);
        System.out.flush();
    }

    static void sh(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            p.waitFor();
        } catch (Throwable t) { log("sh err " + t); }
    }
}
