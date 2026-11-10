package com.gcagent;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** GCAgent-owned, insert-only directory DB: users + chats. */
public final class Db {
    static final int VERSION = 2;

    public static synchronized SQLiteDatabase open(File dbFile) {
        File dir = dbFile.getParentFile();
        if (dir != null) dir.mkdirs();
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(dbFile, null);
        try { db.enableWriteAheadLogging(); } catch (Throwable ignored) {}
        db.execSQL("PRAGMA foreign_keys=ON");
        if (db.getVersion() < VERSION) {
            db.beginTransaction();
            try {
                long oldVersion = db.getVersion();
                db.execSQL("CREATE TABLE IF NOT EXISTS schema_info(k TEXT PRIMARY KEY,v TEXT NOT NULL)");
                db.execSQL("CREATE TABLE IF NOT EXISTS users(" +
                        "id INTEGER PRIMARY KEY," +
                        "account TEXT NOT NULL," +
                        "uin TEXT," +
                        "uid TEXT," +
                        "nick TEXT," +
                        "remark TEXT," +
                        "friend_type INTEGER," +
                        "profile_datetime_ms INTEGER," +
                        "first_seen_at INTEGER NOT NULL," +
                        "last_seen_at INTEGER NOT NULL," +
                        "UNIQUE(account,uin))");
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS ux_users_uid ON users(account,uid) WHERE uid IS NOT NULL AND uid<>''");
                db.execSQL("CREATE TABLE IF NOT EXISTS chats(" +
                        "id INTEGER PRIMARY KEY," +
                        "account TEXT NOT NULL," +
                        "chat_type INTEGER NOT NULL CHECK(chat_type IN (1,2))," +
                        "peer_uid TEXT NOT NULL," +
                        "peer_kind TEXT NOT NULL CHECK(peer_kind IN ('uin','troop'))," +
                        "name TEXT," +
                        "owner_uin TEXT," +
                        "last_msg_time INTEGER," +
                        "class_ext INTEGER," +
                        "flag_ext INTEGER," +
                        "first_seen_at INTEGER NOT NULL," +
                        "last_seen_at INTEGER NOT NULL," +
                        "UNIQUE(account,chat_type,peer_uid))");
                db.execSQL("CREATE INDEX IF NOT EXISTS ix_chats_recent ON chats(account,last_msg_time DESC)");
                if (oldVersion < 2) migrateTimestampsToMillis(db);
                db.execSQL("INSERT OR REPLACE INTO schema_info(k,v) VALUES('schema_version','2')");
                db.setVersion(VERSION);
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        }
        return db;
    }

    public static long normalizeEpochMs(long value) {
        if (value <= 0) return 0;
        return value < 100_000_000_000L ? value * 1000L : value;
    }

    private static void migrateTimestampsToMillis(SQLiteDatabase db) {
        db.execSQL("UPDATE users SET profile_datetime_ms=profile_datetime_ms*1000 " +
                "WHERE profile_datetime_ms > 0 AND profile_datetime_ms < 100000000000");
        db.execSQL("UPDATE users SET first_seen_at=first_seen_at*1000, last_seen_at=last_seen_at*1000 " +
                "WHERE first_seen_at > 0 AND first_seen_at < 100000000000");
        db.execSQL("UPDATE chats SET first_seen_at=first_seen_at*1000, last_seen_at=last_seen_at*1000 " +
                "WHERE first_seen_at > 0 AND first_seen_at < 100000000000");
        db.execSQL("UPDATE chats SET last_msg_time=last_msg_time*1000 " +
                "WHERE last_msg_time > 0 AND last_msg_time < 100000000000");
    }

    public static boolean ensureUser(File dbFile, String account, String uin, String uid, String nick, String remark,
                                     long friendType, long profileDatetimeMs) {
        if ((uin == null || uin.isEmpty()) && (uid == null || uid.isEmpty())) return false;
        SQLiteDatabase db = open(dbFile);
        profileDatetimeMs = normalizeEpochMs(profileDatetimeMs);
        long now = System.currentTimeMillis();
        try {
            Cursor c;
            if (uin != null && !uin.isEmpty()) c = db.rawQuery("SELECT id,nick,remark FROM users WHERE account=? AND uin=?", new String[]{account, uin});
            else c = db.rawQuery("SELECT id,nick,remark FROM users WHERE account=? AND uid=?", new String[]{account, uid});
            if (c.moveToFirst()) {
                long id = c.getLong(0);
                String n = c.isNull(1) ? null : c.getString(1);
                String r = c.isNull(2) ? null : c.getString(2);
                c.close();
                if ((n == null || n.isEmpty()) && nick != null) db.execSQL("UPDATE users SET nick=? WHERE id=?", new Object[]{nick, id});
                if ((r == null || r.isEmpty()) && remark != null) db.execSQL("UPDATE users SET remark=? WHERE id=?", new Object[]{remark, id});
                db.execSQL("UPDATE users SET last_seen_at=? WHERE id=?", new Object[]{now, id});
                return false;
            }
            c.close();
            db.execSQL("INSERT INTO users(account,uin,uid,nick,remark,friend_type,profile_datetime_ms,first_seen_at,last_seen_at) VALUES(?,?,?,?,?,?,?,?,?)",
                    new Object[]{account, emptyToNull(uin), emptyToNull(uid), nick, remark, friendType, profileDatetimeMs, now, now});
            return true;
        } catch (Throwable t) {
            Xlog.i("DB ensureUser err " + t);
            return false;
        } finally { db.close(); }
    }

    public static boolean ensurePrivateChat(File dbFile, String account, String userUin, String name) {
        if (userUin == null || userUin.isEmpty()) return false;
        SQLiteDatabase db = open(dbFile);
        long now = System.currentTimeMillis();
        try {
            Cursor c = db.rawQuery("SELECT id FROM chats WHERE account=? AND chat_type=1 AND peer_uid=?", new String[]{account, userUin});
            if (c.moveToFirst()) { c.close(); db.execSQL("UPDATE chats SET last_seen_at=? WHERE account=? AND chat_type=1 AND peer_uid=?", new Object[]{now, account, userUin}); return false; }
            c.close();
            db.execSQL("INSERT INTO chats(account,chat_type,peer_uid,peer_kind,name,first_seen_at,last_seen_at) VALUES(?,?,?,?,?,?,?)",
                    new Object[]{account, 1, userUin, "uin", name, now, now});
            return true;
        } catch (Throwable t) { Xlog.i("DB ensurePrivateChat err " + t); return false; }
        finally { db.close(); }
    }

    public static boolean ensureGroupChat(File dbFile, String account, String troopUin, String name, String owner,
                                          long lastMsgTime, long classExt, long flagExt) {
        if (troopUin == null || troopUin.isEmpty()) return false;
        SQLiteDatabase db = open(dbFile);
        lastMsgTime = normalizeEpochMs(lastMsgTime);
        long now = System.currentTimeMillis();
        try {
            Cursor c = db.rawQuery("SELECT id FROM chats WHERE account=? AND chat_type=2 AND peer_uid=?", new String[]{account, troopUin});
            if (c.moveToFirst()) {
                long id = c.getLong(0); c.close();
                db.execSQL("UPDATE chats SET last_seen_at=?, name=COALESCE(NULLIF(name,''),?), owner_uin=COALESCE(NULLIF(owner_uin,''),?), " +
                        "last_msg_time=CASE WHEN ? > COALESCE(last_msg_time,0) THEN ? ELSE last_msg_time END, " +
                        "class_ext=?, flag_ext=? WHERE id=?",
                        new Object[]{now, name, owner, lastMsgTime, lastMsgTime, classExt, flagExt, id});
                return false;
            }
            c.close();
            db.execSQL("INSERT INTO chats(account,chat_type,peer_uid,peer_kind,name,owner_uin,last_msg_time,class_ext,flag_ext,first_seen_at,last_seen_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    new Object[]{account, 2, troopUin, "troop", name, owner, lastMsgTime, classExt, flagExt, now, now});
            return true;
        } catch (Throwable t) { Xlog.i("DB ensureGroupChat err " + t); return false; }
        finally { db.close(); }
    }

    public static String importContacts(File dbFile, String account, File jsonFile) {
        int nu = 0, nc = 0, ng = 0;
        try {
            String s = new String(Files.readAllBytes(jsonFile.toPath()), StandardCharsets.UTF_8);
            Object o = Json.parse(s);
            if (!(o instanceof Map)) return "BAD_JSON";
            Map<?,?> m = (Map<?,?>) o;
            List<?> friends = (List<?>) m.get("friends");
            List<?> groups = (List<?>) m.get("groups");
            if (friends != null) for (Object x : friends) {
                Map<?,?> f = (Map<?,?>) x;
                String uin = str(f.get("uin"));
                String nick = str(f.get("nick"));
                String remark = str(f.get("remark"));
                if (ensureUser(dbFile, account, uin, null, nick, remark, num(f.get("friend_type")), num(f.get("datetime")))) nu++;
                if (ensurePrivateChat(dbFile, account, uin, remark != null && !remark.isEmpty() ? remark : nick)) nc++;
            }
            if (groups != null) for (Object x : groups) {
                Map<?,?> g = (Map<?,?>) x;
                String tu = str(g.get("troopuin"));
                if (ensureGroupChat(dbFile, account, tu, str(g.get("troopname")), str(g.get("owner")),
                        num(g.get("last_msg_time")), num(g.get("class_ext")), num(g.get("flag_ext")))) ng++;
            }
        } catch (Throwable t) { Xlog.i("DB importContacts err " + t); return "ERR " + t; }
        return "IMPORT users+" + nu + " chats+" + nc + " groups+" + ng;
    }

    public static String importUserQueue(File dbFile, String account, File queue) {
        int n = 0;
        if (!queue.exists()) return "QUEUE none";
        try {
            for (String line : Files.readAllLines(queue.toPath(), StandardCharsets.UTF_8)) {
                if (line.trim().isEmpty()) continue;
                Object o = Json.parse(line);
                if (!(o instanceof Map)) continue;
                Map<?,?> m = (Map<?,?>) o;
                if (ensureUser(dbFile, account, str(m.get("uin")), str(m.get("uid")), str(m.get("nick")), null, 0, 0)) n++;
            }
        } catch (Throwable t) { Xlog.i("DB importUserQueue err " + t); return "ERR " + t; }
        return "USERQ +" + n;
    }

    public static String counts(File dbFile) {
        SQLiteDatabase db = open(dbFile);
        try {
            Cursor u = db.rawQuery("SELECT COUNT(*) FROM users", null); u.moveToFirst(); int nu = u.getInt(0); u.close();
            Cursor c = db.rawQuery("SELECT COUNT(*) FROM chats", null); c.moveToFirst(); int nc = c.getInt(0); c.close();
            Cursor p = db.rawQuery("SELECT COUNT(*) FROM chats WHERE chat_type=1", null); p.moveToFirst(); int np = p.getInt(0); p.close();
            Cursor g = db.rawQuery("SELECT COUNT(*) FROM chats WHERE chat_type=2", null); g.moveToFirst(); int ng = g.getInt(0); g.close();
            return "DB users=" + nu + " chats=" + nc + " private=" + np + " groups=" + ng;
        } finally { db.close(); }
    }

    public static final class User {
        public long id;
        public String uin, uid, nick, remark;
        public long friendType, profileDatetimeMs, firstSeenAt, lastSeenAt;
        public String displayName() {
            if (remark != null && !remark.isEmpty()) return remark;
            if (nick != null && !nick.isEmpty()) return nick;
            return uin == null ? "未知好友" : uin;
        }
    }

    public static final class Chat {
        public long id;
        public int chatType;
        public String peerUid, peerKind, name, ownerUin;
        public long lastMsgTime, firstSeenAt, lastSeenAt;
        public boolean group() { return chatType == 2; }
        public String displayName() {
            return name == null || name.isEmpty() ? (group() ? "未命名群聊" : "未命名会话") : name;
        }
    }

    public static List<User> listUsers(File dbFile) {
        List<User> out = new ArrayList<>();
        SQLiteDatabase db = open(dbFile);
        try {
            Cursor c = db.rawQuery("SELECT id,uin,uid,nick,remark,friend_type,profile_datetime_ms,first_seen_at,last_seen_at " +
                    "FROM users ORDER BY COALESCE(NULLIF(remark,''),NULLIF(nick,''),uin) COLLATE NOCASE", null);
            while (c.moveToNext()) {
                User u = new User();
                u.id = c.getLong(0);
                u.uin = c.isNull(1) ? null : c.getString(1);
                u.uid = c.isNull(2) ? null : c.getString(2);
                u.nick = c.isNull(3) ? null : c.getString(3);
                u.remark = c.isNull(4) ? null : c.getString(4);
                u.friendType = c.getLong(5);
                u.profileDatetimeMs = c.getLong(6);
                u.firstSeenAt = c.getLong(7);
                u.lastSeenAt = c.getLong(8);
                out.add(u);
            }
            c.close();
        } finally { db.close(); }
        return out;
    }

    public static List<Chat> listChats(File dbFile, int chatType) {
        List<Chat> out = new ArrayList<>();
        SQLiteDatabase db = open(dbFile);
        try {
            Cursor c = db.rawQuery("SELECT id,chat_type,peer_uid,peer_kind,name,owner_uin,last_msg_time,first_seen_at,last_seen_at " +
                    "FROM chats WHERE chat_type=? ORDER BY COALESCE(last_msg_time,0) DESC, name COLLATE NOCASE", new String[]{String.valueOf(chatType)});
            while (c.moveToNext()) {
                Chat ch = new Chat();
                ch.id = c.getLong(0);
                ch.chatType = c.getInt(1);
                ch.peerUid = c.isNull(2) ? null : c.getString(2);
                ch.peerKind = c.isNull(3) ? null : c.getString(3);
                ch.name = c.isNull(4) ? null : c.getString(4);
                ch.ownerUin = c.isNull(5) ? null : c.getString(5);
                ch.lastMsgTime = c.getLong(6);
                ch.firstSeenAt = c.getLong(7);
                ch.lastSeenAt = c.getLong(8);
                out.add(ch);
            }
            c.close();
        } finally { db.close(); }
        return out;
    }

    static String str(Object o) { return o == null ? null : String.valueOf(o); }
    static long num(Object o) { return o instanceof Number ? ((Number) o).longValue() : 0L; }
    static String emptyToNull(String s) { return s == null || s.isEmpty() ? null : s; }
}
