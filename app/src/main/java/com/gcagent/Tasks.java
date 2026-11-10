package com.gcagent;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plan-task table for one QQ account.
 *
 * Two task kinds:
 *  - gap:   a missing msgSeq range that still has to be fetched (between observed
 *           coverage intervals, or between target_oldest_seq and the oldest
 *           observed seq).
 *  - media: a captured message whose image/file payload is not on disk yet.
 *
 * The table is persisted to tasks.json and is intentionally independent from
 * the PC: the PC can later read the same file or receive the task summary.
 */
final class Tasks {
    static final String PENDING = "pending";
    static final String DONE = "done";
    static final String GAP = "gap";
    static final String MEDIA = "media";

    static final class Media {
        String key;
        String type;
        String name;
        String md5;
        String uuid;
        String localPath;
        long size;

        Media(String key, String type, String name, long size, String md5, String uuid, String localPath) {
            this.key = key;
            this.type = type;
            this.name = name;
            this.size = size;
            this.md5 = md5;
            this.uuid = uuid;
            this.localPath = localPath;
        }

        boolean local() {
            return localPath != null && !localPath.isEmpty();
        }
    }

    static final class Task {
        String id;
        String kind;
        String peer;
        int chatType;
        String name;
        String status = PENDING;
        long fromSeq;
        long toSeq;
        long msgSeq;
        long fileSize;
        long created;
        long updated;
        String msgId;
        String contentKey;
        String mediaType;
        String fileName;
        String md5;
        String uuid;
        String localPath;
    }

    static final class Chat {
        String peer;
        int chatType;
        String name;
        int target;
        final List<long[]> intervals = new ArrayList<>();

        Chat(String peer) { this.peer = peer; }
    }

    private final File file;
    private final Map<String, Chat> chats = new LinkedHashMap<>();
    private final Map<String, Task> tasks = new LinkedHashMap<>();
    private boolean dirty;
    private long lastFlush;
    private String lastSummary = "";

    Tasks(File dir) {
        this.file = new File(dir, "tasks.json");
        load();
    }

    synchronized boolean hasTarget(String peer) {
        Chat c = chats.get(peer);
        return c != null && c.target > 0;
    }

    synchronized boolean anyTarget() {
        for (Chat c : chats.values()) if (c.target > 0) return true;
        return false;
    }

    synchronized String firstTargetPeer() {
        for (Chat c : chats.values()) if (c.target > 0) return c.peer;
        return null;
    }

    synchronized void setTarget(String peer, int target) {
        if (peer == null || peer.isEmpty()) return;
        Chat c = chat(peer);
        if (target <= 0) return;
        if (c.target == target) return;
        c.target = target;
        recomputeGaps(c);
        dirty = true;
    }

    synchronized void observe(String peer, int chatType, String name, String msgId, long seq, List<Media> media) {
        if (peer == null || peer.isEmpty() || "unknown".equals(peer)) return;
        Chat c = chat(peer);
        if (chatType > 0) c.chatType = chatType;
        if (name != null && !name.isEmpty()) c.name = name;
        if (seq > 0) addSeq(c, seq);
        if (media != null) {
            for (Media m : media) {
                if (m == null || m.key == null || m.key.isEmpty()) continue;
                String id = "media:" + peer + ":" + m.key;
                Task t = tasks.get(id);
                if (m.local()) {
                    if (t != null && !DONE.equals(t.status)) {
                        t.status = DONE;
                        t.updated = now();
                        dirty = true;
                    }
                    continue;
                }
                if (t == null) {
                    t = new Task();
                    t.id = id;
                    t.kind = MEDIA;
                    t.peer = peer;
                    t.created = now();
                    tasks.put(id, t);
                }
                t.chatType = c.chatType;
                t.name = c.name;
                t.msgId = msgId;
                t.msgSeq = seq;
                t.contentKey = m.key;
                t.mediaType = m.type;
                t.fileName = m.name;
                t.fileSize = m.size;
                t.md5 = m.md5;
                t.uuid = m.uuid;
                t.status = PENDING;
                t.updated = now();
                dirty = true;
            }
        }
        recomputeGaps(c);
    }

    synchronized void flushIfDirty() {
        if (!dirty) return;
        long n = System.currentTimeMillis();
        if (n - lastFlush < 1000) return;
        flush();
    }

    synchronized List<Task> pendingMedia() {
        List<Task> out = new ArrayList<>();
        for (Task t : tasks.values()) {
            if (!PENDING.equals(t.status) || !MEDIA.equals(t.kind)) continue;
            if (t.fileName == null || t.fileName.isEmpty()) continue;
            out.add(t);
        }
        Collections.sort(out, new Comparator<Task>() {
            @Override public int compare(Task a, Task b) { return Long.compare(a.created, b.created); }
        });
        return out;
    }

    synchronized List<Chat> pendingChats() {
        List<Chat> out = new ArrayList<>();
        for (Chat c : chats.values()) {
            if (c.name == null || c.name.isEmpty()) continue;
            for (Task t : tasks.values()) {
                if (!PENDING.equals(t.status) || !c.peer.equals(t.peer)) continue;
                if (GAP.equals(t.kind) || MEDIA.equals(t.kind)) { out.add(c); break; }
            }
        }
        return out;
    }

    synchronized List<Task> pendingMedia(String peer) {
        List<Task> out = new ArrayList<>();
        for (Task t : tasks.values()) {
            if (!PENDING.equals(t.status) || !MEDIA.equals(t.kind) || !t.peer.equals(peer)) continue;
            if (t.fileName == null || t.fileName.isEmpty()) continue;
            out.add(t);
        }
        Collections.sort(out, new Comparator<Task>() {
            @Override public int compare(Task a, Task b) { return Long.compare(a.created, b.created); }
        });
        return out;
    }

    synchronized List<Task> doneMedia() {
        List<Task> out = new ArrayList<>();
        for (Task t : tasks.values()) {
            if (!DONE.equals(t.status) || !MEDIA.equals(t.kind)) continue;
            if (t.localPath == null || t.localPath.isEmpty()) continue;
            out.add(t);
        }
        return out;
    }

    synchronized boolean hasPendingGap(String peer) {
        for (Task t : tasks.values()) {
            if (PENDING.equals(t.status) && GAP.equals(t.kind) && t.peer.equals(peer)) return true;
        }
        return false;
    }

    synchronized String chatName(String peer) {
        Chat c = chats.get(peer);
        return c == null ? null : c.name;
    }

    synchronized boolean markMediaDone(String peer, String contentKey, String localPath) {
        if (peer == null || contentKey == null) return false;
        Task t = tasks.get("media:" + peer + ":" + contentKey);
        if (t == null) return false;
        t.status = DONE;
        t.localPath = localPath;
        t.updated = now();
        dirty = true;
        flush();
        return true;
    }

    synchronized void flushNow() { flush(); }

    synchronized String summary() {
        int gap = 0, media = 0;
        for (Task t : tasks.values()) {
            if (!PENDING.equals(t.status)) continue;
            if (GAP.equals(t.kind)) gap++;
            else if (MEDIA.equals(t.kind)) media++;
        }
        String s = "chats=" + chats.size() + " gaps=" + gap + " media=" + media;
        if (!s.equals(lastSummary)) {
            lastSummary = s;
            dirty = true;
        }
        return s;
    }

    // ---------------- coverage / gaps ----------------

    private Chat chat(String peer) {
        Chat c = chats.get(peer);
        if (c == null) {
            c = new Chat(peer);
            chats.put(peer, c);
            dirty = true;
        }
        return c;
    }

    private static void addSeq(Chat c, long seq) {
        List<long[]> in = c.intervals;
        in.add(new long[]{seq, seq});
        Collections.sort(in, new Comparator<long[]>() {
            @Override public int compare(long[] a, long[] b) {
                return Long.compare(a[0], b[0]);
            }
        });
        List<long[]> out = new ArrayList<>();
        for (long[] r : in) {
            if (out.isEmpty()) { out.add(r); continue; }
            long[] last = out.get(out.size() - 1);
            if (r[0] <= last[1] + 1) {
                if (r[1] > last[1]) last[1] = r[1];
            } else {
                out.add(r);
            }
        }
        in.clear();
        in.addAll(out);
    }

    private void recomputeGaps(Chat c) {
        Map<String, long[]> want = new LinkedHashMap<>();
        List<long[]> in = c.intervals;
        if (!in.isEmpty()) {
            long first = in.get(0)[0];
            if (c.target > 0 && first > c.target) {
                long[] r = new long[]{c.target, first - 1};
                want.put("gap:" + c.peer + ":" + r[0] + ":" + r[1], r);
            }
            for (int i = 1; i < in.size(); i++) {
                long a = in.get(i - 1)[1] + 1;
                long b = in.get(i)[0] - 1;
                if (a <= b) {
                    long[] r = new long[]{a, b};
                    want.put("gap:" + c.peer + ":" + a + ":" + b, r);
                }
            }
        }
        for (Task t : tasks.values()) {
            if (!GAP.equals(t.kind) || !c.peer.equals(t.peer)) continue;
            if (want.containsKey(t.id)) {
                if (!PENDING.equals(t.status)) {
                    t.status = PENDING;
                    t.updated = now();
                    dirty = true;
                }
            } else if (PENDING.equals(t.status)) {
                t.status = DONE;
                t.updated = now();
                dirty = true;
            }
        }
        int added = 0;
        for (Map.Entry<String, long[]> e : want.entrySet()) {
            if (tasks.containsKey(e.getKey())) continue;
            if (added >= 500) break;
            Task t = new Task();
            t.id = e.getKey();
            t.kind = GAP;
            t.peer = c.peer;
            t.chatType = c.chatType;
            t.name = c.name;
            t.fromSeq = e.getValue()[0];
            t.toSeq = e.getValue()[1];
            t.created = now();
            t.updated = t.created;
            tasks.put(t.id, t);
            added++;
            dirty = true;
        }
    }

    // ---------------- persistence ----------------

    private static long now() { return System.currentTimeMillis() / 1000; }

    private void flush() {
        try {
            List<String> chatJson = new ArrayList<>();
            for (Chat c : chats.values()) {
                List<String> iv = new ArrayList<>();
                for (long[] r : c.intervals) iv.add("[" + r[0] + "," + r[1] + "]");
                chatJson.add(Json.obj(
                        "peer", Json.q(c.peer),
                        "chat_type", String.valueOf(c.chatType),
                        "name", Json.q(c.name),
                        "target", String.valueOf(c.target),
                        "intervals", "[" + join(iv) + "]"));
            }
            List<String> taskJson = new ArrayList<>();
            for (Task t : tasks.values()) {
                taskJson.add(Json.obj(
                        "id", Json.q(t.id),
                        "kind", Json.q(t.kind),
                        "peer", Json.q(t.peer),
                        "chat_type", String.valueOf(t.chatType),
                        "name", Json.q(t.name),
                        "status", Json.q(t.status),
                        "from_seq", String.valueOf(t.fromSeq),
                        "to_seq", String.valueOf(t.toSeq),
                        "msg_id", Json.q(t.msgId),
                        "msg_seq", String.valueOf(t.msgSeq),
                        "content_key", Json.q(t.contentKey),
                        "media_type", Json.q(t.mediaType),
                        "file_name", Json.q(t.fileName),
                        "file_size", String.valueOf(t.fileSize),
                        "md5", Json.q(t.md5),
                        "uuid", Json.q(t.uuid),
                        "local_path", Json.q(t.localPath),
                        "created", String.valueOf(t.created),
                        "updated", String.valueOf(t.updated)));
            }
            String root = "{" +
                    "\"v\":1," +
                    "\"updated\":" + now() + "," +
                    "\"chats\":[" + join(chatJson) + "]," +
                    "\"tasks\":[" + join(taskJson) + "]" +
                    "}";
            File tmp = new File(file.getParentFile(), "tasks.tmp");
            try (FileOutputStream fo = new FileOutputStream(tmp)) {
                fo.write(root.getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file); }
            dirty = false;
            lastFlush = System.currentTimeMillis();
            prune();
        } catch (Throwable ignored) {}
    }

    private void prune() {
        if (tasks.size() <= 3000) return;
        List<Task> done = new ArrayList<>();
        for (Task t : tasks.values()) if (DONE.equals(t.status)) done.add(t);
        Collections.sort(done, new Comparator<Task>() {
            @Override public int compare(Task a, Task b) { return Long.compare(a.updated, b.updated); }
        });
        int remove = tasks.size() - 3000;
        for (Task t : done) {
            if (remove <= 0) break;
            tasks.remove(t.id);
            remove--;
        }
    }

    private void load() {
        if (!file.isFile()) return;
        try {
            String s = new String(readAll(file), StandardCharsets.UTF_8);
            Object root = Json.parse(s);
            if (!(root instanceof Map)) return;
            Map<?, ?> r = (Map<?, ?>) root;
            Object cs = r.get("chats");
            if (cs instanceof List) {
                for (Object o : (List<?>) cs) {
                    if (!(o instanceof Map)) continue;
                    Map<?, ?> m = (Map<?, ?>) o;
                    String peer = str(m.get("peer"));
                    if (peer == null || peer.isEmpty()) continue;
                    Chat c = new Chat(peer);
                    c.chatType = (int) num(m.get("chat_type"));
                    c.name = str(m.get("name"));
                    c.target = (int) num(m.get("target"));
                    Object iv = m.get("intervals");
                    if (iv instanceof List) {
                        for (Object x : (List<?>) iv) {
                            if (!(x instanceof List) || ((List<?>) x).size() < 2) continue;
                            List<?> p = (List<?>) x;
                            c.intervals.add(new long[]{num(p.get(0)), num(p.get(1))});
                        }
                    }
                    Collections.sort(c.intervals, new Comparator<long[]>() {
                        @Override public int compare(long[] a, long[] b) { return Long.compare(a[0], b[0]); }
                    });
                    chats.put(peer, c);
                }
            }
            Object ts = r.get("tasks");
            if (ts instanceof List) {
                for (Object o : (List<?>) ts) {
                    if (!(o instanceof Map)) continue;
                    Map<?, ?> m = (Map<?, ?>) o;
                    String id = str(m.get("id"));
                    if (id == null || id.isEmpty()) continue;
                    Task t = new Task();
                    t.id = id;
                    t.kind = str(m.get("kind"));
                    t.peer = str(m.get("peer"));
                    t.chatType = (int) num(m.get("chat_type"));
                    t.name = str(m.get("name"));
                    t.status = str(m.get("status"));
                    t.fromSeq = num(m.get("from_seq"));
                    t.toSeq = num(m.get("to_seq"));
                    t.msgId = str(m.get("msg_id"));
                    t.msgSeq = num(m.get("msg_seq"));
                    t.contentKey = str(m.get("content_key"));
                    t.mediaType = str(m.get("media_type"));
                    t.fileName = str(m.get("file_name"));
                    t.fileSize = num(m.get("file_size"));
                    t.md5 = str(m.get("md5"));
                    t.uuid = str(m.get("uuid"));
                    t.localPath = str(m.get("local_path"));
                    t.created = num(m.get("created"));
                    t.updated = num(m.get("updated"));
                    if (t.status == null) t.status = PENDING;
                    tasks.put(id, t);
                }
            }
        } catch (Throwable ignored) {
            chats.clear();
            tasks.clear();
        }
    }

    private static byte[] readAll(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n <= 0) break;
                off += n;
            }
            return b;
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static long num(Object o) {
        if (o instanceof Number) return ((Number) o).longValue();
        if (o instanceof String) {
            try { return Long.parseLong((String) o); } catch (Throwable ignored) {}
        }
        return 0;
    }

    private static String join(List<String> xs) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < xs.size(); i++) {
            if (i > 0) b.append(',');
            b.append(xs.get(i));
        }
        return b.toString();
    }
}
