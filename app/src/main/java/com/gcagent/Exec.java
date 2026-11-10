package com.gcagent;

import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Root/VD orchestrator. All UI work happens on the virtual display via
 *  root `input -d`; node text/bounds come from the debug a11y dumper. */
public class Exec {
    static final String QDIR = "/sdcard/Android/data/com.tencent.mobileqq/files/gcagent";
    static final String ADIR = "/sdcard/Android/data/com.gcagent/files";
    static final String DBG = "/sdcard/Android/data/com.gcagent/files/vd_dump.txt";
    static VirtualDisplay vd;
    static int vdId = -1;

    public static void main(String[] args) throws Exception {
        if (android.os.Looper.myLooper() == null) android.os.Looper.prepare();
        if (args.length > 0 && "contacts".equals(args[0])) { runContacts(); return; }
        int w = 1224, h = 2700, dpi = 520;
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        Context ctx = (Context) at.getMethod("getSystemContext").invoke(thread);
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        ImageReader reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 4);
        vd = dm.createVirtualDisplay("gcagent-exec", w, h, dpi, reader.getSurface(), 17985);
        vdId = vd.getDisplay().getDisplayId();
        log("VD_ID=" + vdId);

        sh("settings put secure accessibility_enabled 1");
        sh("settings put secure enabled_accessibility_services 'com.gcagent/com.gcagent.FileNavService:com.omarea.vtools/com.omarea.vtools.AccessibilitySceneMode'");

        Tasks tasks = new Tasks(new File(QDIR));
        Sync.start(tasks, new File(QDIR));
        List<Tasks.Chat> chats = tasks.pendingChats();
        log("pending chats=" + chats.size());
        int max = args.length > 0 && "one".equals(args[0]) ? 1 : 6;
        int n = 0;
        for (Tasks.Chat c : chats) {
            if (n++ >= max) break;
            log("CHAT " + c.name + " peer=" + c.peer + " type=" + c.chatType);
            sh("am force-stop com.tencent.mobileqq");
            Thread.sleep(1000);
            sh("am start --display " + vdId + " -n com.tencent.mobileqq/.activity.SplashActivity");
            Thread.sleep(8000);
            if (!openChat(c.name)) { log("FAIL no chat " + c.name); continue; }
            fillGaps(c.peer, tasks);
            List<Tasks.Task> media = tasks.pendingMedia(c.peer);
            log("CHAT media=" + media.size() + " gaps=" + tasks.hasPendingGap(c.peer));
            if (!media.isEmpty()) {
                if (openSettings() && openHistoryFiles()) {
                    for (Tasks.Task t : media) {
                        log("TASK " + t.name + " file=" + t.fileName + " size=" + t.fileSize);
                        boolean ok = downloadOnPage(t, tasks);
                        log("TASK_RESULT " + ok + " " + t.fileName);
                    }
                } else {
                    log("FAIL file page for " + c.name);
                }
            }
            tasks.flushNow();
            log("CHAT_DONE " + c.name);
        }
        try { Sync.flush(tasks, new File(QDIR)); } catch (Throwable ignored) {}
        log("DONE");
        try { sh("am force-stop com.tencent.mobileqq"); Thread.sleep(1000); } catch (Throwable ignored) {}
        try { vd.release(); } catch (Throwable ignored) {}
    }


    static void runContacts() throws Exception {
        int w = 1224, h = 2700, dpi = 520;
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        Context ctx = (Context) at.getMethod("getSystemContext").invoke(thread);
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        ImageReader reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 4);
        vd = dm.createVirtualDisplay("gcagent-contacts", w, h, dpi, reader.getSurface(), 17985);
        vdId = vd.getDisplay().getDisplayId();
        log("CONTACTS VD_ID=" + vdId);
        // root protection for the temporary run
        try { sh("cmd deviceidle whitelist +com.gcagent"); } catch (Throwable ignored) {}
        try { sh("cmd deviceidle whitelist +com.tencent.mobileqq"); } catch (Throwable ignored) {}
        try { sh("am set-standby-bucket com.tencent.mobileqq active"); } catch (Throwable ignored) {}
        sh("am force-stop com.tencent.mobileqq");
        Thread.sleep(1000);
        sh("am start --display " + vdId + " -n com.tencent.mobileqq/.activity.SplashActivity");
        Thread.sleep(8000);
        // 联系人 tab (bottom)
        sh("input -d " + vdId + " tap 450 2600");
        Thread.sleep(2500);
        // 好友 tab
        sh("input -d " + vdId + " tap 388 1037");
        Thread.sleep(2500);
        String r1 = collect("friends");
        log("CONTACTS friends " + r1);
        // 群聊 tab
        sh("input -d " + vdId + " tap 606 1037");
        Thread.sleep(2500);
        String r2 = collect("groups");
        log("CONTACTS groups " + r2);
        String json = read(new File(QDIR, "contacts.json"));
        log("CONTACTS json bytes=" + json.length());
        try { sh("cmd deviceidle whitelist +com.gcagent"); } catch (Throwable ignored) {}
        try { sh("am set-standby-bucket com.gcagent active"); } catch (Throwable ignored) {}
        File appDir = new File("/sdcard/Android/data/com.gcagent/files");
        appDir.mkdirs();
        try { sh("cp " + QDIR + "/contacts.json " + appDir + "/contacts.json"); sh("chmod 666 " + appDir + "/contacts.json"); } catch (Throwable ignored) {}
        try { sh("cp " + QDIR + "/user_queue.jsonl " + appDir + "/user_queue.jsonl"); sh("chmod 666 " + appDir + "/user_queue.jsonl"); } catch (Throwable ignored) {}
        File dbOut = new File(appDir, "db_result.txt");
        if (dbOut.exists()) dbOut.delete();
        sh("am start-foreground-service -n com.gcagent/.DbImportService --es account default");
        String r3 = "DB timeout";
        long dbEnd = System.currentTimeMillis() + 30000;
        while (System.currentTimeMillis() < dbEnd) {
            if (dbOut.exists() && dbOut.length() > 0) { r3 = read(dbOut).trim(); break; }
            Thread.sleep(500);
        }
        log("CONTACTS DB " + r3);
        try { java.nio.file.Files.write(new File(QDIR, "contacts_result.txt").toPath(), (r1 + "\n" + r2 + "\n" + r3 + "\n").getBytes("UTF-8")); } catch (Throwable ignored) {}
        try { sh("am force-stop com.tencent.mobileqq"); Thread.sleep(1000); } catch (Throwable ignored) {}
        try { vd.release(); } catch (Throwable ignored) {}
    }

    static String collect(String mode) throws Exception {
        File out = new File(QDIR, "contacts_out.txt");
        if (out.exists()) out.delete();
        File cmd = new File(QDIR, "contacts_cmd.txt");
        java.nio.file.Files.write(cmd.toPath(), (mode + "\n").getBytes("UTF-8"));
        try { sh("chmod 666 " + cmd.getAbsolutePath()); } catch (Throwable ignored) {}
        long end = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < end) {
            if (out.exists() && out.length() > 0) {
                String s = read(out);
                if (s.startsWith("OK ") || s.startsWith("ERR ")) return s.trim();
            }
            Thread.sleep(500);
        }
        return "TIMEOUT " + mode;
    }

    static String read(File f) {
        try { return new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8"); } catch (Throwable t) { return ""; }
    }
    static void fillGaps(String peer, Tasks tasks) throws Exception {
        for (int i = 0; i < 40; i++) {
            if (!tasks.hasPendingGap(peer)) return;
            log("gap swipe " + i);
            sh("input -d " + vdId + " swipe 612 800 612 1800 150");
            Thread.sleep(1500);
        }
    }

    static boolean downloadOnPage(Tasks.Task t, Tasks tasks) throws Exception {
        List<N> ns = dumpNodes();
        N f = findAny(ns, t.fileName);
        if (f == null) {
            log("FAIL no entry " + t.fileName + " nodes=" + ns.size());
            try {
                StringBuilder sb = new StringBuilder();
                for (N x : ns) sb.append(x.cls).append("|t=").append(x.t).append("|d=").append(x.d).append("|b=").append(x.x1).append(",").append(x.y1).append(",").append(x.x2).append(",").append(x.y2).append("\n");
                java.nio.file.Files.write(new File(QDIR, "exec-fail.txt").toPath(), sb.toString().getBytes());
            } catch (Throwable ignored) {}
            return false;
        }
        log("tap entry " + t.fileName + " @" + f.cx() + "," + f.cy());
        tap(f);
        String local = "/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/QQfile_recv/" + t.fileName;
        boolean ok = waitLocal(local, t.fileSize, 30000);
        log("download " + (ok ? "ok " : "timeout ") + local);
        if (ok) {
            boolean marked = tasks.markMediaDone(t.peer, t.contentKey, local);
            log("mark " + marked + " " + t.contentKey);
        }
        return ok;
    }

    static boolean openChat(String chat) throws Exception {
        for (int i = 0; i < 12; i++) {
            List<N> ns = dumpNodes();
            N c = findChat(ns, chat);
            if (c != null) { tap(c); Thread.sleep(1500); log("chat opened " + chat); return true; }
            sh("input -d " + vdId + " swipe 612 1800 612 800 300");
            Thread.sleep(1200);
        }
        return false;
    }

    static boolean openSettings() throws Exception {
        List<N> ns = dumpNodes();
        N s = find(ns, "聊天设置", true);
        if (s == null) return false;
        tap(s);
        Thread.sleep(1800);
        return true;
    }

    static boolean openHistoryFiles() throws Exception {
        List<N> ns = dumpNodes();
        N h = find(ns, "查找聊天记录", false);
        if (h == null) return false;
        tap(h);
        Thread.sleep(2200);
        for (int i = 0; i < 3; i++) {
            ns = dumpNodes();
            N f = findButton(ns, "文件");
            if (f == null) return false;
            tap(f);
            Thread.sleep(2800);
            ns = dumpNodes();
            if (find(ns, "搜索文件名", false) != null) { log("file category ok"); return true; }
            log("file category retry " + i);
        }
        return true;
    }

    static N findChat(List<N> ns, String chat) {
        for (N n : ns) {
            if (n.t.equals(chat)) return n;
            if (n.d.equals(chat) || n.d.startsWith(chat + ",") || n.d.startsWith(chat + "，")) return n;
        }
        return null;
    }

    static N findButton(List<N> ns, String text) {
        for (N n : ns) if (n.t.equals(text) && (n.cls.contains("Button") || n.cls.contains("TextView"))) return n;
        return null;
    }

    static N findAny(List<N> ns, String needle) {
        for (N n : ns) if (n.t.contains(needle) || n.d.contains(needle)) return n;
        return null;
    }

    static N find(List<N> ns, String needle, boolean desc) {
        for (N n : ns) if ((desc ? n.d : n.t).contains(needle)) return n;
        return null;
    }

    static void tap(N n) throws Exception {
        sh("input -d " + vdId + " tap " + n.cx() + " " + n.cy());
        Thread.sleep(1300);
    }

    static List<N> dumpNodes() throws Exception {
        sh("am broadcast -n com.gcagent/.FileNavReceiver -a com.gcagent.DUMP");
        Thread.sleep(2500);
        List<N> out = new ArrayList<>();
        File f = new File(DBG);
        if (!f.isFile()) return out;
        for (String line : java.nio.file.Files.readAllLines(f.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
            String[] p = line.split("\\|");
            if (p.length < 7) continue;
            if (!p[0].equals(String.valueOf(vdId))) continue;
            if (!p[2].equals("com.tencent.mobileqq")) continue;
            N n = new N();
            n.cls = p[3];
            n.t = p[4].startsWith("t=") ? p[4].substring(2) : "";
            n.d = p[5].startsWith("d=") ? p[5].substring(2) : "";
            String b = p[6].startsWith("b=") ? p[6].substring(2) : "";
            String[] xy = b.split(",");
            if (xy.length != 4) continue;
            try {
                n.x1 = Integer.parseInt(xy[0]); n.y1 = Integer.parseInt(xy[1]);
                n.x2 = Integer.parseInt(xy[2]); n.y2 = Integer.parseInt(xy[3]);
            } catch (Throwable t) { continue; }
            out.add(n);
        }
        return out;
    }

    static boolean waitLocal(String path, long size, int ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        File f = new File(path);
        while (System.currentTimeMillis() < end) {
            if (f.isFile() && (size <= 0 || f.length() == size)) return true;
            Thread.sleep(700);
        }
        return f.isFile() && (size <= 0 || f.length() == size);
    }

    static class N {
        String cls = "", t = "", d = "";
        int x1, y1, x2, y2;
        int cx() { return (x1 + x2) / 2; }
        int cy() { return (y1 + y2) / 2; }
    }

    static void sh(String cmd) throws Exception {
        Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
        p.waitFor();
    }

    static void log(String s) {
        System.out.println("EXEC " + s);
        try {
            File d = new File(QDIR); d.mkdirs();
            java.io.FileOutputStream fo = new java.io.FileOutputStream(new File(d, "exec.log"), true);
            fo.write((System.currentTimeMillis() + " " + s + "\n").getBytes());
            fo.close();
        } catch (Throwable ignored) {}
    }
}
