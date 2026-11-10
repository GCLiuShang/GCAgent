package com.gcagent;

import android.app.Activity;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Phase 1 collector: while QQ is foreground, diff the current chat's adapter
 * and write new messages to an outbox; a sender thread posts batches to the PC
 * receiver and deletes them after an ack.
 *
 * Runs inside the QQ process (LSPosed), so its files live under QQ's external
 * files directory.
 */
final class Collector {
    private static final String TAG = "GCAgent";
    private static final String QQ = "com.tencent.mobileqq";
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    private static File baseDir;
    private static Handler handler;
    private static volatile View chatRoot;
    private static volatile View chatRv;

    static void onChatRecyclerView(View v, Object adapter) {
        chatRv = v;
        xlog("chat rv " + v.getClass().getName() + " ad=" + (adapter == null ? "null" : adapter.getClass().getName()));
    }

    static void onChatActivity(android.app.Activity a) {
        try {
            chatRoot = a.getWindow().getDecorView();
            xlog("chat activity " + a.getClass().getName() + " root=" + chatRoot.getClass().getName());
        } catch (Throwable t) { xlog("chat activity err " + t); }
    }
    private static Thread senderThread;
    private static volatile boolean running = true;

    private static final Map<String, Set<String>> seen = new HashMap<>();
    private static int batchCounter = 0;
    private static long lastCollectLog = 0;
    private static long lastDiag = 0;

    private static String account = "default";
    private static String host = "127.0.0.1";
    private static int port = 8765;
    private static volatile boolean enabled = true;
    private static int targetOldestSeq = 0;
    private static String targetPeer = null;
    private static Tasks tasks;
    private static final Map<String, Integer> targets = new HashMap<>();
    private static long lastTaskLog = 0;

    private Collector() {}

    static void start(ClassLoader cl) {
        xlog("GC start enter");
        try {
            if (!STARTED.compareAndSet(false, true)) {
                xlog("GC start already");
                return;
            }
            baseDir = new File(Environment.getExternalStorageDirectory(),
                    "Android/data/" + QQ + "/files/gcagent");
            xlog("GC start baseDir=" + baseDir);
            mkdirs();
            xlog("GC start mkdirs ok");
            writeDefaultConfigIfMissing();
            loadConfig();
            tasks = new Tasks(baseDir);
            xlog("GC start config ok enabled=" + enabled + " host=" + host + ":" + port);
            xlog("GC start mainLooper=" + Looper.getMainLooper());
            handler = new Handler(Looper.getMainLooper());
            xlog("GC start handler ok");
            handler.postDelayed(Collector::tick, 1500);
            senderThread = new Thread(Collector::senderLoop, "gcagent-sender");
            senderThread.setDaemon(true);
            senderThread.start();
            xlog("GC start sender ok");
            log("collector started dir=" + baseDir.getAbsolutePath());
            xlog("GC start done");
        } catch (Throwable t) {
            xlog("GC start error " + t);
            t.printStackTrace();
        }
    }

    private static void xlog(String s) {
        Log.i(TAG, s);
        try {
            de.robv.android.xposed.XposedBridge.log("GCAgent " + android.os.Process.myPid() + " " + s);
        } catch (Throwable ignored) {}
    }

    private static void mkdirs() {
        new File(baseDir, "outbox").mkdirs();
    }

    private static File configFile() { return new File(baseDir, "config.json"); }
    private static File statusFile() { return new File(baseDir, "status.json"); }
    private static File logFile() { return new File(baseDir, "gcagent.log"); }

    static void log(String s) {
        String line = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date()) + " " + s;
        Log.i(TAG, line);
        try (FileOutputStream fo = new FileOutputStream(logFile(), true)) {
            fo.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {}
    }

    private static void writeDefaultConfigIfMissing() {
        try {
            File f = configFile();
            if (f.exists()) return;
            String s = "{\n  \"host\": \"127.0.0.1\",\n  \"port\": 8765,\n  \"enabled\": true,\n  \"account\": \"default\"\n}\n";
            try (FileOutputStream fo = new FileOutputStream(f)) {
                fo.write(s.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable t) { log("config write err " + t); }
    }

    private static void loadConfig() {
        try {
            String s = new String(java.nio.file.Files.readAllBytes(configFile().toPath()), StandardCharsets.UTF_8);
            String h = grab(s, "host");
            if (h != null && !h.isEmpty()) host = h;
            Integer p = grabInt(s, "port");
            if (p != null) port = p;
            String a = grab(s, "account");
            if (a != null && !a.isEmpty()) account = a;
            enabled = !s.replace(" ", "").contains("\"enabled\":false");
            Integer t = grabInt(s, "target_oldest_seq");
            if (t != null) targetOldestSeq = t;
            String tp = grab(s, "target_peer");
            if (tp != null && !tp.isEmpty()) targetPeer = tp;
            targets.clear();
            java.util.regex.Matcher tm = java.util.regex.Pattern
                    .compile("\"targets\"\\s*:\\s*\\{([^}]*)\\}").matcher(s);
            if (tm.find()) {
                java.util.regex.Matcher pm = java.util.regex.Pattern
                        .compile("\"([^\"]+)\"\\s*:\\s*(\\d+)").matcher(tm.group(1));
                while (pm.find()) {
                    try { targets.put(pm.group(1), Integer.parseInt(pm.group(2))); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
    }

    private static String grab(String s, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static Integer grabInt(String s, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*(\\d+)").matcher(s);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    // ---------------- collection ----------------

    private static void tick() {
        if (!running) return;
        handler.postDelayed(Collector::tick, 2000);
        try {
            if (!enabled) return;
            loadConfig();
            java.io.File df = new java.io.File(baseDir, "dump.txt");
            if (df.exists()) { try { dumpRvs(); } catch (Throwable t) { log("dump err " + t); } df.delete(); }
            java.io.File ff = new java.io.File(baseDir, "fragdump.txt");
            if (ff.exists()) { try { dumpFragments(); } catch (Throwable t) { log("fragdump err " + t); } ff.delete(); }
            boolean fg = isQqForeground();
            if (!fg) {
                flushTasks();
                diag("not foreground");
                return;
            }
            collectOnce();
            flushTasks();
        } catch (Throwable t) {
            log("tick err " + t);
        }
    }

    private static boolean isQqForeground() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("currentActivityThread").invoke(null);
            Object rec = field(thread, "mResumedActivity");
            if (rec != null) {
                Object act = field(rec, "activity");
                if (act instanceof Activity) {
                    return QQ.equals(((Activity) act).getPackageName());
                }
            }
        } catch (Throwable ignored) {}
        // If the field is unavailable, prefer collecting rather than losing data.
        return true;
    }

    private static void collectOnce() {
        View rv = findChatRecyclerView();
        if (rv == null) { diag("no AIORecycleView"); return; }
        Object adapter = call(rv, "getAdapter");
        if (adapter == null) { diag("no adapter"); return; }
        List<?> items = itemsOf(adapter);
        if (items == null || items.isEmpty()) { diag("no items"); return; }

        List<String> records = new ArrayList<>();
        long minSeq = Long.MAX_VALUE, maxSeq = Long.MIN_VALUE;
        String peerUid = null;
        int chatType = 0;
        String chatName = null;

        for (Object item : items) {
            try {
                Object rec = fieldByType(item, "MsgRecord");
                if (rec == null) continue;
                String mid = str(field(rec, "msgId"));
                Object seqObj0 = field(rec, "msgSeq");
                log("item mid=" + mid + " seq=" + seqObj0 + " peer=" + field(rec, "peerUin"));
                if (mid == null || mid.isEmpty()) continue;
                long seqVal = 0;
                if (seqObj0 instanceof Number) {
                    seqVal = ((Number) seqObj0).longValue();
                    if (seqVal <= 0) continue;
                }
                if (seqVal > 0) {
                    if (seqVal < minSeq) minSeq = seqVal;
                    if (seqVal > maxSeq) maxSeq = seqVal;
                }
                String peer = str(field(rec, "peerUin"));
                if (peer == null) peer = str(field(rec, "peerUid"));
                if (peer == null) peer = "unknown";
                if (peerUid == null) {
                    peerUid = peer;
                    Object ct = field(rec, "chatType");
                    chatType = ct instanceof Number ? ((Number) ct).intValue() : 0;
                    Object pn = field(rec, "peerName");
                    chatName = pn == null ? null : String.valueOf(pn);
                }
                List<Tasks.Media> media = new ArrayList<>();
                String record = buildRecord(item, rec, mid, media);
                if (tasks != null && seqVal > 0) {
                    Integer tt = targets.get(peer);
                    if (targetPeer == null || targetPeer.isEmpty()) {
                        String tp0 = tasks.firstTargetPeer();
                        if (tp0 != null) targetPeer = tp0;
                    }
                    if (tt == null && targetOldestSeq > 0 && (targetPeer == null || targetPeer.isEmpty())
                            && !tasks.anyTarget()) {
                        targetPeer = peer;
                    }
                    if (tt == null && targetOldestSeq > 0 && peer.equals(targetPeer)) tt = targetOldestSeq;
                    if (tt != null) tasks.setTarget(peer, tt);
                    tasks.observe(peer, chatType, chatName, mid, seqVal, media);
                }
                Set<String> s = seen.get(peer);
                if (s == null) {
                    s = new HashSet<>();
                    seen.put(peer, s);
                }
                if (s.contains(mid)) continue;

                if (record != null) {
                    s.add(mid);
                    records.add(record);
                    queueUser(rec);
                }
            } catch (Throwable t) {
                log("item err " + t);
            }
        }

        if (minSeq != Long.MAX_VALUE) log("range peer=" + peerUid + " min=" + minSeq + " max=" + maxSeq + " n=" + items.size());
        if (records.isEmpty() || peerUid == null) {
            diag("no new records items=" + items.size());
            return;
        }
        writeBatch(peerUid, chatType, chatName, records);
        if (System.currentTimeMillis() - lastCollectLog > 10000) {
            lastCollectLog = System.currentTimeMillis();
            log("collected " + records.size() + " new records peer=" + peerUid);
        }
    }

    private static void diag(String s) {
        log(s);
    }

    private static Object unwrapElement(Object raw) {
        try {
            String cn = raw.getClass().getSimpleName();
            if (!cn.equals("MsgElement")) return raw;
            for (java.lang.reflect.Field f : raw.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                String fn = f.getName().toLowerCase();
                if (!(fn.contains("element") || fn.equals("ark"))) continue;
                if (fn.contains("id") || fn.contains("type")) continue;
                Object v = f.get(raw);
                if (v == null) continue;
                String vn = v.getClass().getSimpleName();
                if (vn.contains("Text") || vn.contains("Face") || vn.contains("Pic")
                        || vn.contains("File") || vn.contains("Ark") || vn.contains("Ptt")
                        || vn.contains("Video") || vn.contains("Market")) return v;
            }
        } catch (Throwable ignored) {}
        return raw;
    }

    private static Object fallbackName(Object item, Object rec) {
        try {
            Object mi = field(item, "m");
            if (mi != null) {
                for (String n : new String[]{"autoRemark", "cardName", "troopNick", "nick", "remark"}) {
                    Object v = field(mi, n);
                    String s = str(v);
                    if (s != null && !s.isEmpty()) return s;
                }
            }
            for (String n : new String[]{"h", "i", "sendMemberName"}) {
                Object v = field(item, n);
                String s = str(v);
                if (s != null && !s.isEmpty()) return s;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static final Map<String, String> md5Cache = new HashMap<>();

    private static String findLocalByMd5(String md5, Object sizeObj) {
        if (md5 == null || md5.isEmpty()) return null;
        if (md5Cache.containsKey(md5)) return md5Cache.get(md5);
        long want = sizeObj instanceof Number ? ((Number) sizeObj).longValue() : -1;
        String[] roots = {
                "/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/MobileQQ/chatpic/chatraw",
                "/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/MobileQQ/chatpic/chatthumb"
        };
        for (String r : roots) {
            String hit = walkMd5(new File(r), md5, want, 0);
            if (hit != null) { md5Cache.put(md5, hit); return hit; }
        }
        md5Cache.put(md5, null);
        return null;
    }

    private static String walkMd5(File f, String md5, long want, int depth) {
        if (f == null || depth > 6) return null;
        if (f.isFile()) {
            if (want > 0 && f.length() != want) return null;
            if (want <= 0 && f.length() > 8 * 1024 * 1024) return null;
            String m = md5File(f);
            return md5.equalsIgnoreCase(m) ? f.getAbsolutePath() : null;
        }
        File[] cs = f.listFiles();
        if (cs == null) return null;
        for (File c : cs) {
            String r = walkMd5(c, md5, want, depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    private static String md5File(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            MessageDigest d = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) d.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : d.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) { return null; }
    }

    private static String findLocalFile(String name) {
        if (name == null || name.isEmpty()) return null;
        File f = new File("/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/QQfile_recv", name);
        return f.isFile() ? f.getAbsolutePath() : null;
    }

    private static String buildRecord(Object item, Object rec, String mid, List<Tasks.Media> mediaOut) {
        Object seq = field(rec, "msgSeq");
        Object time = field(rec, "msgTime");
        Object senderUid = field(rec, "senderUid");
        Object senderUin = field(rec, "senderUin");
        Object nick = field(rec, "sendNickName");
        String nameSource = "record";
        if (str(nick) == null || str(nick).isEmpty()) {
            nick = fallbackName(item, rec);
            nameSource = str(nick) == null || str(nick).isEmpty() ? "none" : "member";
        }
        String nameState = str(nick) == null || str(nick).isEmpty() ? "missing" : "ok";
        String nameJson = str(nick) == null || str(nick).isEmpty() ? "null" : Json.q(str(nick));
        Object msgType = field(rec, "msgType");
        Object subType = field(rec, "subMsgType");

        List<String> elements = new ArrayList<>();
        List<String> media = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        Object elsObj = field(rec, "elements");
        if (elsObj instanceof List) {
            for (Object raw : (List<?>) elsObj) {
                if (raw == null) continue;
                Object el = unwrapElement(raw);
                String cls = el.getClass().getSimpleName();
                String content = str(field(el, "content"));
                if (cls.contains("Text") && content != null) {
                    text.append(content);
                    elements.add(Json.obj("type", Json.q("text"), "text", Json.q(content)));
                } else if (cls.contains("Face")) {
                    Object idx = field(el, "faceIndex");
                    elements.add(Json.obj("type", Json.q("face"), "face_index", numJson(idx)));
                } else if (cls.contains("Pic")) {
                    String md5 = str(field(el, "md5HexStr"));
                    String uuid = str(field(el, "fileUuid"));
                    String name = str(field(el, "fileName"));
                    Object size = field(el, "fileSize");
                    Object w = field(el, "picWidth");
                    Object h = field(el, "picHeight");
                    String local = findLocalByMd5(md5, size);
                    elements.add(Json.obj(
                            "type", Json.q("image"),
                            "file_name", Json.q(name),
                            "file_size", numJson(size),
                            "md5", Json.q(md5),
                            "uuid", Json.q(uuid),
                            "width", numJson(w),
                            "height", numJson(h),
                            "local_path", Json.q(local)));
                    String key = md5 != null && !md5.isEmpty() ? "md5:" + md5
                            : (uuid != null && !uuid.isEmpty() ? "file:" + uuid : null);
                    if (key != null) {
                        media.add(Json.obj(
                                "content_key", Json.q(key),
                                "type", Json.q("image"),
                                "file_name", Json.q(name),
                                "file_size", numJson(size),
                                "md5", Json.q(md5),
                                "local_path", Json.q(local),
                                "state", Json.q(local != null ? "1" : "0")));
                        mediaOut.add(new Tasks.Media(key, "image", name, longOf(size), md5, uuid, local));
                    }
                } else if (cls.contains("File")) {
                    String uuid = str(field(el, "fileUuid"));
                    String name = str(field(el, "fileName"));
                    Object size = field(el, "fileSize");
                    String local = findLocalFile(name);
                    elements.add(Json.obj(
                            "type", Json.q("file"),
                            "file_name", Json.q(name),
                            "file_size", numJson(size),
                            "uuid", Json.q(uuid),
                            "local_path", Json.q(local)));
                    if (uuid != null && !uuid.isEmpty()) {
                        media.add(Json.obj(
                                "content_key", Json.q("file:" + uuid),
                                "type", Json.q("file"),
                                "file_name", Json.q(name),
                                "file_size", numJson(size),
                                "local_path", Json.q(local),
                                "state", Json.q(local != null ? "1" : "0")));
                        mediaOut.add(new Tasks.Media("file:" + uuid, "file", name, longOf(size), null, uuid, local));
                    }
                } else {
                    elements.add(Json.obj("type", Json.q("other"), "class", Json.q(cls)));
                }
            }
        }
        if (text.length() == 0) {
            String fallback = str(field(item, "j1"));
            if (fallback == null) fallback = str(field(item, "q1"));
            if (fallback != null) text.append(fallback);
        }

        return Json.obj(
                "msg_id", Json.q(mid),
                "msg_seq", numJson(seq),
                "msg_time", numJson(time),
                "sender_uid", Json.q(str(senderUid)),
                "sender_uin", numJson(senderUin),
                "sender_name", nameJson,
                "sender_name_source", Json.q(nameSource),
                "sender_name_state", Json.q(nameState),
                "msg_type", numJson(msgType),
                "sub_type", numJson(subType),
                "text", Json.q(text.toString()),
                "elements", Json.arr(elements),
                "media", Json.arr(media));
    }

    private static long longOf(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : 0L;
    }

    private static void flushTasks() {
        if (tasks == null) return;
        try {
            tasks.flushIfDirty();
            String sum = tasks.summary();
            if (System.currentTimeMillis() - lastTaskLog > 5000) {
                lastTaskLog = System.currentTimeMillis();
                log("tasks " + sum);
            }
        } catch (Throwable t) { log("tasks flush err " + t); }
    }

    private static void writeBatch(String peerUid, int chatType, String chatName, List<String> records) {
        try {
            File outbox = new File(baseDir, "outbox");
            outbox.mkdirs();
            File[] existing = outbox.listFiles();
            if (existing != null && existing.length > 1000) {
                log("outbox too large, drop batch");
                return;
            }
            String batchId = "b" + System.currentTimeMillis() + "-" + (batchCounter++);
            String chat = Json.obj(
                    "peer_uid", Json.q(peerUid),
                    "chat_type", String.valueOf(chatType),
                    "peer_kind", Json.q(chatType == 2 ? "troop" : "uin"),
                    "name", Json.q(chatName == null ? "" : chatName));
            String env = "{" +
                    "\"v\":1," +
                    "\"type\":\"batch\"," +
                    "\"batch_id\":" + Json.q(batchId) + "," +
                    "\"account\":" + Json.q(account) + "," +
                    "\"device_id\":\"android-gcagent\"," +
                    "\"chat\":" + chat + "," +
                    "\"records\":[" + join(records) + "]" +
                    "}";
            File tmp = new File(outbox, batchId + ".tmp");
            File dst = new File(outbox, batchId + ".json");
            try (FileOutputStream fo = new FileOutputStream(tmp)) {
                fo.write(env.getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(dst)) {
                tmp.delete();
                log("rename batch failed");
            }
        } catch (Throwable t) {
            log("write batch err " + t);
        }
    }

    private static void queueUser(Object rec) {
        try {
            Object uin = field(rec, "senderUin");
            Object uid = field(rec, "senderUid");
            Object nick = field(rec, "sendNickName");
            if (uin == null && uid == null) return;
            String line = Json.obj(
                    "uin", uin == null ? "null" : String.valueOf(uin),
                    "uid", Json.q(str(uid)),
                    "nick", Json.q(str(nick)));
            File f = new File(baseDir, "user_queue.jsonl");
            synchronized (Collector.class) {
                try (FileOutputStream fo = new FileOutputStream(f, true)) {
                    fo.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (Throwable t) { log("queue user err " + t); }
    }

    private static String join(List<String> xs) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < xs.size(); i++) {
            if (i > 0) b.append(',');
            b.append(xs.get(i));
        }
        return b.toString();
    }

    private static View findChatRecyclerView() {
        View direct = chatRv;
        if (direct != null && direct.isShown()) return direct;
        View root = chatRoot;
        if (root != null) {
            View v = best(root, 0);
            if (v != null) return v;
        }
        View fromWmg = findInWindowManager();
        if (fromWmg != null) return fromWmg;
        return findInActivities();
    }

    private static void dumpRvs() {
        log("DUMP begin");
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            Object inst = wmg.getMethod("getInstance").invoke(null);
            Object views = field(inst, "mViews");
            if (views instanceof List) for (Object o : (List<?>) views) if (o instanceof View) dumpTree((View) o, 0);
            Object roots = field(inst, "mRoots");
            if (roots instanceof List) for (Object r : (List<?>) roots) {
                Object mv = field(r, "mView"); if (!(mv instanceof View)) mv = call(r, "getView");
                if (mv instanceof View) dumpTree((View) mv, 0);
            }
        } catch (Throwable t) { log("DUMP wmg err " + t); }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("currentActivityThread").invoke(null);
            Object map = field(thread, "mActivities");
            if (map instanceof java.util.Map) for (Object rec : ((java.util.Map<?,?>) map).values()) {
                Object act = field(rec, "activity");
                if (act instanceof Activity) {
                    log("DUMP activity " + act.getClass().getName());
                    dumpTree(((Activity) act).getWindow().getDecorView(), 0);
                }
            }
        } catch (Throwable t) { log("DUMP act err " + t); }
        log("DUMP end");
    }

    private static void dumpTree(View v, int d) {
        if (v == null || d > 40) return;
        String cn = v.getClass().getName();
        if (cn.contains("RecyclerView") || cn.contains("RecycleView")) {
            Object ad = call(v, "getAdapter");
            Object ic = call(v, "getItemCount");
            android.graphics.Rect r = new android.graphics.Rect();
            v.getGlobalVisibleRect(r);
            log("DUMP rv " + cn + " ad=" + (ad == null ? "null" : ad.getClass().getName()) + " n=" + ic + " b=" + r + " shown=" + v.isShown());
        } else if (cn.contains("Compose") || cn.contains("Surface") || cn.contains("Chat") || cn.contains("AIO") || cn.contains("msg")) {
            android.graphics.Rect r = new android.graphics.Rect();
            v.getGlobalVisibleRect(r);
            log("DUMP v " + cn + " b=" + r + " shown=" + v.isShown());
        }
        if (v instanceof ViewGroup) { ViewGroup g = (ViewGroup) v; for (int i = 0; i < g.getChildCount(); i++) dumpTree(g.getChildAt(i), d + 1); }
    }

    private static View findInWindowManager() {
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            Object inst = wmg.getMethod("getInstance").invoke(null);
            Object views = field(inst, "mViews");
            if (views instanceof List) {
                List<?> list = (List<?>) views;
                diag("wmg views=" + list.size());
                for (Object o : list) {
                    if (!(o instanceof View)) continue;
                    View v = best((View) o, 0);
                    if (v != null) return v;
                }
            }
            Object roots = field(inst, "mRoots");
            if (roots instanceof List) {
                List<?> rl = (List<?>) roots;
                diag("wmg roots=" + rl.size());
                for (Object r : rl) {
                    Object mv = field(r, "mView");
                    if (!(mv instanceof View)) mv = call(r, "getView");
                    diag("root " + r.getClass().getName() + " view=" + (mv == null ? "null" : mv.getClass().getName()));
                    if (!(mv instanceof View)) continue;
                    View v = best((View) mv, 0);
                    if (v != null) return v;
                }
            }
        } catch (Throwable t) {
            diag("wmg err " + t);
        }
        return null;
    }

    private static View findInActivities() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("currentActivityThread").invoke(null);
            Object map = field(thread, "mActivities");
            if (map instanceof java.util.Map) {
                java.util.Map<?, ?> m = (java.util.Map<?, ?>) map;
                diag("activities=" + m.size());
                for (Object rec : m.values()) {
                    Object act = field(rec, "activity");
                    if (!(act instanceof Activity)) continue;
                    Activity a = (Activity) act;
                    diag("activity " + a.getClass().getName());
                    View v = best(a.getWindow().getDecorView(), 0);
                    if (v != null) return v;
                }
            }
        } catch (Throwable t) {
            diag("activities err " + t);
        }
        return null;
    }

    private static View best(View v, int depth) {
        if (v == null || depth > 40) return null;
        String cn = v.getClass().getName();
        if (cn.contains("RecyclerView") || cn.contains("RecycleView")) {
            Object ad = call(v, "getAdapter");
            String an = ad == null ? "" : ad.getClass().getName();
            diag("rv " + cn + " ad=" + an);
            if (cn.contains("AIORecycleView") || an.contains("msglist") || an.contains("aio.msg") || an.contains("aio.part")) {
                return v;
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = best(g.getChildAt(i), depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static List<?> itemsOf(Object adapter) {
        try {
            log("adapter " + adapter.getClass().getName());
            StringBuilder fs = new StringBuilder("fields:");
            for (Field f : adapter.getClass().getDeclaredFields()) fs.append(' ').append(f.getName()).append(':').append(f.getType().getSimpleName());
            log(fs.toString());
            Object differ = field(adapter, "m");
            if (differ != null) {
                log("differ " + differ.getClass().getName());
                List<?> dl = listIn(differ, 0);
                if (dl != null) { log("differ list " + dl.size()); return dl; }
            }
            for (Class<?> c = adapter.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    f.setAccessible(true);
                    Object v = f.get(adapter);
                    if (v == null) continue;
                    List<?> list = listIn(v, 0);
                    if (list != null && !list.isEmpty()) return list;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static List<?> listIn(Object o, int depth) {
        if (o == null || depth > 3) return null;
        if (o instanceof List) {
            List<?> l = (List<?>) o;
            if (!l.isEmpty()) {
                String cn = l.get(0).getClass().getName();
                if (cn.contains("aio.msg") || cn.contains("MsgItem")) return l;
            }
            return null;
        }
        try {
            for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    f.setAccessible(true);
                    Object v = f.get(o);
                    if (v == null) continue;
                    List<?> l = listIn(v, depth + 1);
                    if (l != null) return l;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // ---------------- sender ----------------

    private static void senderLoop() {
        while (running) {
            try {
                loadConfig();
                if (enabled) sendPending();
            } catch (Throwable t) {
                writeStatus(-1, -1, String.valueOf(t));
            }
            try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
        }
    }

    private static void sendPending() {
        File outbox = new File(baseDir, "outbox");
        File[] files = outbox.listFiles((d, n) -> n.endsWith(".json"));
        if (files == null || files.length == 0) return;
        Arrays.sort(files);
        int sent = 0;
        for (File f : files) {
            if (sent >= 10) break;
            if (sendOne(f)) sent++;
        }
    }

    private static boolean sendOne(File f) {
        try {
            String payload = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, port), 3000);
                s.setSoTimeout(5000);
                DataOutputStream out = new DataOutputStream(s.getOutputStream());
                DataInputStream in = new DataInputStream(s.getInputStream());

                String hello = "{\"v\":1,\"type\":\"hello\",\"device_id\":\"android-gcagent\",\"account\":" + Json.q(account) + ",\"client\":\"gcagent/2.0\"}";
                writeFrame(out, hello);
                String helloAck = readFrame(in);
                if (helloAck == null) return false;

                writeFrame(out, payload);
                String ack = readFrame(in);
                if (ack == null) return false;
                if (!ack.contains("\"type\":\"ack\"")) {
                    writeStatus(-1, -1, "bad ack: " + trim(ack, 200));
                    return false;
                }
                int accepted = arrLen(ack, "accepted");
                int duplicates = arrLen(ack, "duplicates");
                writeStatus(accepted, duplicates, null);
                f.delete();
                log("sent " + f.getName() + " accepted=" + accepted + " dup=" + duplicates);
                return true;
            }
        } catch (Throwable t) {
            writeStatus(-1, -1, String.valueOf(t));
            return false;
        }
    }

    private static int arrLen(String ack, String key) {
        int i = ack.indexOf("\"" + key + "\"");
        if (i < 0) return -1;
        int a = ack.indexOf('[', i), b = ack.indexOf(']', a + 1);
        if (a < 0 || b < 0) return -1;
        String s = ack.substring(a + 1, b).trim();
        if (s.isEmpty()) return 0;
        return s.split(",").length;
    }

    private static void writeStatus(int accepted, int duplicates, String error) {
        try {
            String s = "{" +
                    "\"time\":" + (System.currentTimeMillis() / 1000) + "," +
                    "\"accepted\":" + accepted + "," +
                    "\"duplicates\":" + duplicates + "," +
                    "\"error\":" + Json.q(error) + "," +
                    "\"host\":" + Json.q(host) + "," +
                    "\"port\":" + port +
                    "}";
            File tmp = new File(baseDir, "status.tmp");
            try (FileOutputStream fo = new FileOutputStream(tmp)) {
                fo.write(s.getBytes(StandardCharsets.UTF_8));
            }
            File dst = statusFile();
            dst.delete();
            if (!tmp.renameTo(dst)) tmp.delete();
        } catch (Throwable ignored) {}
    }

    private static void writeFrame(DataOutputStream out, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        out.writeInt(b.length);
        out.write(b);
        out.flush();
    }

    private static String readFrame(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n <= 0 || n > 16 * 1024 * 1024) throw new IOException("bad frame " + n);
        byte[] b = new byte[n];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    private static String trim(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }

    // ---------------- reflection helpers ----------------

    private static Object field(Object o, String name) {
        if (o == null) return null;
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Object fieldByType(Object o, String suffix) {
        if (o == null) return null;
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType().getName().endsWith(suffix)) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(o);
                        if (v != null) return v;
                    } catch (Throwable ignored) {}
                }
            }
        }
        return null;
    }

    private static Object call(Object o, String method) {
        if (o == null) return null;
        try {
            Method m = o.getClass().getMethod(method);
            m.setAccessible(true);
            return m.invoke(o);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String numJson(Object o) {
        if (o instanceof Number) return String.valueOf(((Number) o).longValue());
        return "0";
    }

    private static void dumpFragments() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("currentActivityThread").invoke(null);
            Object map = field(thread, "mActivities");
            if (map instanceof java.util.Map) {
                for (Object rec : ((java.util.Map<?,?>) map).values()) {
                    Object act = field(rec, "activity");
                    if (!(act instanceof Activity)) continue;
                    Activity a = (Activity) act;
                    Object fm = null;
                    try { fm = a.getClass().getMethod("getSupportFragmentManager").invoke(a); } catch (Throwable ignored) {}
                    if (fm == null) fm = a.getFragmentManager();
                    java.util.List<?> fs = null;
                    try { fs = (java.util.List<?>) fm.getClass().getMethod("getFragments").invoke(fm); } catch (Throwable ignored) {}
                    log("FRAG activity=" + a.getClass().getName() + " n=" + (fs == null ? -1 : fs.size()));
                    if (fs == null) continue;
                    for (Object f : fs) {
                        if (f == null) continue;
                        StringBuilder sb = new StringBuilder("FRAG " + f.getClass().getName());
                        dumpFields(sb, f, 0);
                        log(sb.toString());
                    }
                }
            }
        } catch (Throwable t) { log("FRAG err " + t); }
    }

    private static void dumpFields(StringBuilder sb, Object o, int depth) {
        if (o == null || depth > 2) return;
        java.util.List<java.lang.reflect.Field> all = new java.util.ArrayList<>();
        Class<?> cc = o.getClass();
        for (int lv = 0; cc != null && lv < 4; lv++, cc = cc.getSuperclass()) {
            for (java.lang.reflect.Field f : cc.getDeclaredFields()) all.add(f);
        }
        int n = 0;
        for (java.lang.reflect.Field f : all) {
            if (n++ >= 40) break;
            try {
                f.setAccessible(true);
                Object v = f.get(o);
                sb.append(" ").append(f.getName()).append("=").append(v == null ? "null" : v.getClass().getName());
                if (v instanceof java.util.Map) {
                    java.util.Map<?,?> mm = (java.util.Map<?,?>) v;
                    sb.append(" mapSize=").append(mm.size());
                    int z = 0;
                    for (java.util.Map.Entry<?,?> en : mm.entrySet()) {
                        if (z++ >= 6) break;
                        Object ev = en.getValue();
                        sb.append(" [").append(en.getKey()).append("=").append(ev == null ? "null" : (ev instanceof String || ev instanceof Number || ev instanceof Boolean ? ev : ev.getClass().getName())).append("]");
                    }
                }
                String cn = v == null ? "" : v.getClass().getName().toLowerCase();
                if (v != null && depth < 4 && (cn.contains("kuikly") || cn.contains("render") || f.getName().equals("x") || f.getName().equals("z") || f.getName().equals("j") || f.getName().equals("l") || f.getName().equals("t") || f.getName().equals("f") || f.getName().equals("E") || f.getName().equals("L"))) {
                    sb.append("{");
                    dumpFields(sb, v, depth + 1);
                    sb.append("}");
                }
            } catch (Throwable ignored) {}
        }
    }
}
