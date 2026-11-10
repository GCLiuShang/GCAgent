package com.gcagent;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.util.SparseArray;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class FileNavService extends AccessibilityService {
    static volatile FileNavService inst;
    static volatile File DIR;
    final Handler h = new Handler(Looper.getMainLooper());

    @Override public void onServiceConnected() {
        inst = this;
        DIR = getExternalFilesDir(null);
        if (DIR != null) DIR.mkdirs();
        android.util.Log.i("GCAgent", "FileNavService connected dir=" + DIR);
        log("service connected");
        new Thread(new Runnable() { public void run() { poll(); } }, "filenav-poll").start();
    }

    void poll() {
        File d = DIR;
        if (d == null) return;
        File c = new File(d, "nav_cmd.txt");
        while (inst != null) {
            try {
                if (c.exists()) {
                    String s = new String(java.nio.file.Files.readAllBytes(c.toPath()), StandardCharsets.UTF_8).trim();
                    c.delete();
                    log("cmd " + s);
                    if (s.length() > 0) nav(s);
                }
                Thread.sleep(1500);
            } catch (Throwable t) { log("poll err " + t); }
        }
    }
    volatile long lastContentEvent = 0;

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int t = event.getEventType();
        if (t == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence p = event.getPackageName();
            if (p != null && "com.tencent.mobileqq".contentEquals(p)) lastContentEvent = System.currentTimeMillis();
        }
    }

    int countNodes(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 40) return 0;
        int c = 1;
        for (int i = 0; i < n.getChildCount() && c < 3000; i++) {
            AccessibilityNodeInfo ch = n.getChild(i);
            if (ch != null) c += countNodes(ch, depth + 1);
        }
        return c;
    }

    /** Wait until QQ's window on the VD actually exposes a populated a11y tree. */
    boolean awaitUi(long timeoutMs) {
        File marker = new File(DIR, "vd_ui_ready");
        marker.delete();
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            int nodes = 0, roots = 0;
            for (AccessibilityNodeInfo r : allRoots()) {
                roots++;
                nodes += countNodes(r, 0);
            }
            log("ui wait roots=" + roots + " nodes=" + nodes + " eventAge=" + (lastContentEvent == 0 ? -1 : System.currentTimeMillis() - lastContentEvent));
            if (roots > 0 && nodes > 15) {
                try {
                    FileOutputStream fo = new FileOutputStream(marker);
                    fo.write(String.valueOf(System.currentTimeMillis()).getBytes());
                    fo.close();
                } catch (Throwable ignored) {}
                log("ui ready nodes=" + nodes);
                return true;
            }
            sleep(800);
        }
        log("ui timeout");
        return false;
    }
    @Override public void onInterrupt() {}
    @Override public void onDestroy() { if (inst == this) inst = null; super.onDestroy(); }

    List<AccessibilityNodeInfo> allRoots() {
        List<AccessibilityNodeInfo> rs = new ArrayList<>();
        try {
            SparseArray<List<AccessibilityWindowInfo>> m = getWindowsOnAllDisplays();
            for (int i = 0; i < m.size(); i++) {
                for (AccessibilityWindowInfo w : m.valueAt(i)) {
                    if (w.getDisplayId() == 0) continue;
                    AccessibilityNodeInfo r = w.getRoot();
                    log("win d=" + w.getDisplayId() + " type=" + w.getType() + " pkg=" + (r == null || r.getPackageName() == null ? "null" : r.getPackageName()));
                    if (r != null && r.getPackageName() != null && "com.tencent.mobileqq".contentEquals(r.getPackageName())) rs.add(r);
                }
            }
        } catch (Throwable t) { log("roots err " + t); }
        return rs;
    }

    int vdDisplay() {
        try {
            SparseArray<List<AccessibilityWindowInfo>> m = getWindowsOnAllDisplays();
            for (int i = 0; i < m.size(); i++) {
                for (AccessibilityWindowInfo w : m.valueAt(i)) {
                    if (w.getDisplayId() == 0) continue;
                    AccessibilityNodeInfo r = w.getRoot();
                    if (r != null && r.getPackageName() != null && "com.tencent.mobileqq".contentEquals(r.getPackageName())) return w.getDisplayId();
                }
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    static void log(String s) {
        android.util.Log.i("GCAgent", s);
        try {
            File d = DIR;
            if (d == null) return;
            d.mkdirs();
            FileOutputStream fo = new FileOutputStream(new File(d, "filenav.log"), true);
            fo.write((System.currentTimeMillis() + " " + s + "\n").getBytes(StandardCharsets.UTF_8));
            fo.close();
        } catch (Throwable ignored) {}
    }

    static void download(final String kind, final String chat, final String file, final long size) {
        log("a11y download disabled; use root Exec");
    }

    static void download(final String kind, final String chat, final String file, final long size,
                         final String peer, final String msgId, final String contentKey) {
        log("a11y download disabled; use root Exec");
    }

    static void nav(final String kind) {
        log("a11y nav disabled; use root Exec");
    }

    void doNav(String kind) {
        try {
            log("nav start " + kind);
            if (vdDisplay() < 0) { log("no VD, abort"); return; }
            if (!clickDesc("聊天设置")) { log("no 聊天设置"); return; }
            sleep(900);
            if (!clickTextScroll("查找聊天记录")) { log("no 查找聊天记录"); dump("nav-fail1"); return; }
            sleep(1200);
            if (!clickTextScroll("文件")) { log("no 文件 in history"); dump("nav-fail2"); return; }
            sleep(2200);
            dump("history-files");
            log("nav done " + kind);
        } catch (Throwable t) { log("nav err " + t); }
    }

    void doDownload(String kind, String chat, String file, long size,
                    String peer, String msgId, String contentKey) {
        try {
            log("download start " + kind + " chat=" + chat + " file=" + file + " size=" + size
                    + " peer=" + peer + " msg=" + msgId + " key=" + contentKey);
            if (vdDisplay() < 0) { log("no VD, abort download"); return; }
            if (!awaitUi(90000)) { log("ui not ready, abort download"); return; }
            boolean inChat = false;
            for (int i = 0; i < 4 && !inChat; i++) {
                inChat = clickChat(chat);
                if (!inChat) sleep(1200);
            }
            if (!inChat) inChat = openChatBySearch(chat);
            if (!inChat) { log("no chat " + chat); dump("dl-nochat"); return; }
            sleep(1200);
            if (!clickDesc("聊天设置")) { log("no 聊天设置"); return; }
            sleep(900);
            boolean opened = openFiles(kind);
            if (!opened) { log("no file list " + kind); dump("dl-nofiles"); return; }
            sleep(2200);
            AccessibilityNodeInfo entry = findFileEntry(file);
            if (entry == null) { log("no entry " + file); dump("dl-nofile"); return; }
            click(entry);
            log("tapped entry " + file);
            boolean ok = waitLocal(file, size, 25000);
            String local = "/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/QQfile_recv/" + file;
            log((ok ? "download ok " : "download timeout ") + file + " size=" + size);
            writeDownloadResult(kind, chat, file, size, peer, msgId, contentKey, ok, local);
            // return to the chat list for the next task; never force-stop here
            performGlobalAction(GLOBAL_ACTION_BACK);
            sleep(800);
            performGlobalAction(GLOBAL_ACTION_BACK);
        } catch (Throwable t) { log("download err " + t); }
    }

    /** Always use chat history -> 文件, which is the native document page
     *  with exact MsgRecord/FileElement keys (group and private alike). */
    boolean openFiles(String kind) {
        if (clickTextScroll("查找聊天记录")) {
            sleep(1200);
            if (clickTextScroll("文件")) { sleep(2200); return true; }
        }
        return false;
    }

    boolean clickChat(String chat) {
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        for (AccessibilityNodeInfo root : allRoots()) collect(root, all);
        AccessibilityNodeInfo loose = null;
        for (AccessibilityNodeInfo n : all) {
            CharSequence t = n.getText();
            CharSequence d = n.getContentDescription();
            String ts = t == null ? "" : t.toString().trim();
            String ds = d == null ? "" : d.toString();
            if (ts.equals(chat)) return click(n);
            if (loose == null && (ds.equals(chat) || ds.startsWith(chat + ",") || ds.startsWith(chat + "，"))) loose = n;
        }
        if (!debugLogged) {
            debugLogged = true;
            int k = 0;
            for (AccessibilityNodeInfo n : all) {
                CharSequence t = n.getText();
                CharSequence d = n.getContentDescription();
                String ts = t == null ? "" : t.toString().replace('\n', ' ');
                String ds = d == null ? "" : d.toString().replace('\n', ' ');
                if (!ts.isEmpty() || !ds.isEmpty()) {
                    log("nd t=" + (ts.length() > 40 ? ts.substring(0, 40) : ts) + " d=" + (ds.length() > 60 ? ds.substring(0, 60) : ds));
                    if (++k >= 60) break;
                }
            }
        }
        return loose != null && click(loose);
    }
    boolean debugLogged = false;

    AccessibilityNodeInfo findSearchBox() {
        for (AccessibilityNodeInfo root : allRoots()) {
            List<AccessibilityNodeInfo> all = new ArrayList<>();
            collect(root, all);
            for (AccessibilityNodeInfo n : all) {
                String c = n.getClassName() == null ? "" : n.getClassName().toString();
                if (!(c.contains("EditText") || c.contains("TextView"))) continue;
                String t = n.getText() == null ? "" : n.getText().toString();
                String d = n.getContentDescription() == null ? "" : n.getContentDescription().toString();
                if (t.contains("搜索") || d.contains("搜索")) return n;
            }
        }
        return null;
    }

    boolean openChatBySearch(String chat) {
        try {
            AccessibilityNodeInfo box = findSearchBox();
            if (box == null) { log("search box not found"); return false; }
            click(box);
            sleep(800);
            box = findSearchBox();
            if (box == null) return false;
            android.os.Bundle b = new android.os.Bundle();
            b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, chat);
            boolean set = box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
            log("search set=" + set + " " + chat);
            sleep(2000);
            for (AccessibilityNodeInfo root : allRoots()) {
                List<AccessibilityNodeInfo> all = new ArrayList<>();
                collect(root, all);
                for (AccessibilityNodeInfo n : all) {
                    String t = n.getText() == null ? "" : n.getText().toString().trim();
                    String d = n.getContentDescription() == null ? "" : n.getContentDescription().toString();
                    if (t.equals(chat) || d.equals(chat) || d.startsWith(chat + ",") || d.startsWith(chat + "，")) {
                        log("search result " + chat);
                        return click(n);
                    }
                }
            }
        } catch (Throwable t) { log("search err " + t); }
        log("search no result " + chat);
        return false;
    }

    void scrollListForward() {
        for (AccessibilityNodeInfo root : allRoots()) {
            List<AccessibilityNodeInfo> all = new ArrayList<>();
            collect(root, all);
            for (AccessibilityNodeInfo n : all) {
                if (!n.isScrollable()) continue;
                CharSequence cn = n.getClassName();
                String c = cn == null ? "" : cn.toString();
                if (c.contains("RecyclerView") || c.contains("ListView") || c.contains("ScrollView")) {
                    n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                    return;
                }
            }
        }
    }

    boolean clickTextEquals(String needle) {
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        for (AccessibilityNodeInfo root : allRoots()) collect(root, all);
        for (AccessibilityNodeInfo n : all) {
            CharSequence t = n.getText();
            if (t != null && t.toString().trim().equals(needle)) return click(n);
        }
        return false;
    }

    AccessibilityNodeInfo findFileEntry(String file) {
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        for (AccessibilityNodeInfo root : allRoots()) collect(root, all);
        AccessibilityNodeInfo loose = null;
        for (AccessibilityNodeInfo n : all) {
            CharSequence t = n.getText(), d = n.getContentDescription();
            String hay = (t == null ? "" : t.toString()) + " " + (d == null ? "" : d.toString());
            if (!hay.contains(file)) continue;
            if (hay.contains("KB") || hay.contains("MB") || hay.contains("已下载") || hay.contains("未下载")) return n;
            if (loose == null) loose = n;
        }
        return loose;
    }

    boolean waitLocal(String file, long size, long timeoutMs) {
        File f = new File("/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/QQfile_recv", file);
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (f.isFile()) {
                if (size <= 0 || f.length() == size) return true;
            }
            sleep(1000);
        }
        return false;
    }

    void writeDownloadResult(String kind, String chat, String file, long size,
                             String peer, String msgId, String contentKey, boolean ok, String local) {
        try {
            File d = DIR;
            if (d == null) return;
            d.mkdirs();
            String line = Json.obj(
                    "time", String.valueOf(System.currentTimeMillis()),
                    "ok", ok ? "true" : "false",
                    "kind", Json.q(kind),
                    "chat", Json.q(chat),
                    "peer", Json.q(peer),
                    "msg_id", Json.q(msgId),
                    "content_key", Json.q(contentKey),
                    "file", Json.q(file),
                    "size", String.valueOf(size),
                    "local_path", Json.q(ok ? local : null));
            FileOutputStream fo = new FileOutputStream(new File(d, "downloads.jsonl"), true);
            fo.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            fo.close();
            FileOutputStream fl = new FileOutputStream(new File(d, "downloads.log"), true);
            fl.write((System.currentTimeMillis() + " " + (ok ? "ok " : "fail ") + file + " " + size + "\n").getBytes(StandardCharsets.UTF_8));
            fl.close();
        } catch (Throwable ignored) {}
    }

    boolean clickDesc(String needle) {
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        for (AccessibilityNodeInfo root : allRoots()) collect(root, all);
        AccessibilityNodeInfo best = null;
        for (AccessibilityNodeInfo n : all) {
            CharSequence d = n.getContentDescription();
            if (d != null && d.toString().contains(needle)) {
                Rect nr = new Rect(); n.getBoundsInScreen(nr);
                Rect br = new Rect(); if (best != null) best.getBoundsInScreen(br);
                if (best == null || nr.top < br.top) best = n;
            }
        }
        return best != null && click(best);
    }

    boolean clickTextNear(String needle, String anchor) {
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        for (AccessibilityNodeInfo root : allRoots()) collect(root, all);
        AccessibilityNodeInfo a = null;
        for (AccessibilityNodeInfo n : all) {
            CharSequence t = n.getText();
            if (t != null && t.toString().contains(anchor)) { a = n; break; }
        }
        if (a == null) return false;
        Rect ar = new Rect(); a.getBoundsInScreen(ar);
        AccessibilityNodeInfo best = null;
        for (AccessibilityNodeInfo n : all) {
            CharSequence t = n.getText();
            if (t == null || !t.toString().trim().equals(needle)) continue;
            Rect r = new Rect(); n.getBoundsInScreen(r);
            if (r.top >= ar.top && r.top <= ar.top + 500) { best = n; break; }
        }
        return best != null && click(best);
    }

    boolean clickTextScroll(String needle) {
        for (int i = 0; i < 4; i++) {
            List<AccessibilityNodeInfo> all = new ArrayList<>();
            for (AccessibilityNodeInfo root : allRoots()) collect(root, all);
            for (AccessibilityNodeInfo n : all) {
                CharSequence t = n.getText();
                if (t != null && t.toString().trim().equals(needle)) return click(n);
            }
            for (AccessibilityNodeInfo root : allRoots()) scrollForward(root);
            sleep(600);
        }
        return false;
    }

    void scrollForward(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        collect(root, all);
        for (AccessibilityNodeInfo n : all) {
            if (n.isScrollable()) {
                if (n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return;
            }
        }
    }

    boolean click(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; i < 8 && cur != null; i++) {
            if (cur.isClickable()) {
                if (cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                Rect r = new Rect(); cur.getBoundsInScreen(r);
                return tap(r.centerX(), r.centerY());
            }
            cur = cur.getParent();
        }
        Rect r = new Rect(); n.getBoundsInScreen(r);
        return tap(r.centerX(), r.centerY());
    }

    boolean tap(int x, int y) {
        Path p = new Path(); p.moveTo(x, y);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 60)).build();
        return dispatchGesture(g, null, null);
    }

    void collect(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out) {
        if (n == null || out.size() > 3000) return;
        out.add(n);
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), out);
    }

    static void dumpVd() {
        final FileNavService s = inst;
        if (s == null) { log("dumpVd no service"); return; }
        s.h.post(new Runnable() { public void run() { s.dumpExec(); } });
    }

    void dumpExec() {
        try {
            int did = vdDisplay();
            StringBuilder sb = new StringBuilder();
            if (did >= 0) for (AccessibilityNodeInfo root : allRoots()) dumpNodeExec(root, sb, did, 0);
            File d = DIR; if (d == null) return; d.mkdirs();
            File f = new File(d, "vd_dump.txt");
            FileOutputStream fo = new FileOutputStream(f);
            fo.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            fo.close();
            log("dumpExec d=" + did + " len=" + sb.length());
        } catch (Throwable t) { log("dumpExec err " + t); }
    }

    void dumpNodeExec(AccessibilityNodeInfo n, StringBuilder sb, int did, int depth) {
        if (n == null || depth > 40 || sb.length() > 200000) return;
        CharSequence t = n.getText(), d = n.getContentDescription();
        if ((t != null && t.length() > 0) || (d != null && d.length() > 0)) {
            Rect r = new Rect(); n.getBoundsInScreen(r);
            sb.append(did).append("|").append(depth).append("|").append(n.getPackageName()).append("|")
              .append(n.getClassName()).append("|t=").append(t == null ? "" : t)
              .append("|d=").append(d == null ? "" : d)
              .append("|b=").append(r.left).append(",").append(r.top).append(",").append(r.right).append(",").append(r.bottom).append("\n");
        }
        for (int i = 0; i < n.getChildCount(); i++) dumpNodeExec(n.getChild(i), sb, did, depth + 1);
    }

    void dump(String tag) {
        try {
            StringBuilder sb = new StringBuilder("== " + tag + " ==\n");
            for (AccessibilityNodeInfo root : allRoots()) dumpNode(root, sb, 0);
            File d = DIR;
            if (d == null) return;
            d.mkdirs();
            File f = new File(d, "filelist-" + tag + ".txt");
            FileOutputStream fo = new FileOutputStream(f);
            fo.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            fo.close();
            log("dump " + tag + " -> " + f + " len=" + sb.length());
        } catch (Throwable t) { log("dump err " + t); }
    }

    void dumpNode(AccessibilityNodeInfo n, StringBuilder sb, int depth) {
        if (n == null || depth > 40 || sb.length() > 200000) return;
        CharSequence t = n.getText(), d = n.getContentDescription();
        if ((t != null && t.length() > 0) || (d != null && d.length() > 0)) {
            Rect r = new Rect(); n.getBoundsInScreen(r);
            sb.append(n.getPackageName()).append("|").append(n.getClassName()).append("|t=")
              .append(t == null ? "" : t).append("|d=").append(d == null ? "" : d)
              .append("|b=").append(r.left).append(",").append(r.top).append(",").append(r.right).append(",").append(r.bottom).append("\n");
        }
        for (int i = 0; i < n.getChildCount(); i++) dumpNode(n.getChild(i), sb, depth + 1);
    }

    void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }
}
