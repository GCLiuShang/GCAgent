#!/usr/bin/env python3
"""GCAgent PC receiver (minimal, no SQLite).

Speaks the v1 framed-JSON protocol defined in BACKUP-AGENT-SPEC.md:
  4-byte big-endian length + UTF-8 JSON object.

Persistence for this minimal version:
  data/inbox/<YYYY-MM-DD>/<batch_id>.json   raw accepted batches
  data/seen_keys.log                        one idempotency key per line
  data/coverage.json                        per-chat seq coverage
  data/server.log                           textual log

No database is used. The goal is to let the phone agent complete the
transport/ack loop first; SQLite can replace this storage later.
"""

from __future__ import annotations

import argparse
import datetime as _dt
import json
import os
import socket
import socketserver
import struct
import sys
import threading
import time
import traceback
from pathlib import Path
from typing import Any


MAX_FRAME = 16 * 1024 * 1024
SCHEMA_VERSION = 1
SERVER_NAME = "gcagent-pc-minimal"
_LOG_PATH: Path | None = None


def log(msg: str) -> None:
    ts = _dt.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    line = f"[{ts}] {msg}"
    print(line, flush=True)
    if _LOG_PATH is not None:
        try:
            with _LOG_PATH.open("a", encoding="utf-8") as f:
                f.write(line + "\n")
        except Exception:
            pass


def _json_default(o: Any) -> Any:
    if isinstance(o, (bytes, bytearray)):
        return o.hex()
    return str(o)


def encode_frame(obj: dict) -> bytes:
    raw = json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    if len(raw) > MAX_FRAME:
        raise ValueError(f"frame too large: {len(raw)}")
    return struct.pack(">I", len(raw)) + raw


def recv_exact(sock: socket.socket, n: int) -> bytes | None:
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            return None
        buf.extend(chunk)
    return bytes(buf)


def recv_frame(sock: socket.socket) -> dict | None:
    head = recv_exact(sock, 4)
    if head is None:
        return None
    (size,) = struct.unpack(">I", head)
    if size == 0 or size > MAX_FRAME:
        raise ValueError(f"bad frame size: {size}")
    body = recv_exact(sock, size)
    if body is None:
        return None
    obj = json.loads(body.decode("utf-8"))
    if not isinstance(obj, dict):
        raise ValueError("frame must be a JSON object")
    return obj


class Store:
    """Append-only minimal store; safe for one process."""

    def __init__(self, data_dir: Path):
        self.data_dir = data_dir
        self.inbox = data_dir / "inbox"
        self.inbox.mkdir(parents=True, exist_ok=True)
        self.seen_path = data_dir / "seen_keys.log"
        self.coverage_path = data_dir / "coverage.json"
        self.seen: set[str] = set()
        self.lock = threading.Lock()
        self._load_seen()
        self.coverage: dict[str, dict[str, int]] = {}
        self._load_coverage()

    def _load_seen(self) -> None:
        if not self.seen_path.exists():
            return
        with self.seen_path.open("r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line:
                    self.seen.add(line)
        log(f"loaded {len(self.seen)} seen keys")

    def _load_coverage(self) -> None:
        if not self.coverage_path.exists():
            return
        try:
            self.coverage = json.loads(self.coverage_path.read_text(encoding="utf-8"))
        except Exception:
            self.coverage = {}

    def _save_coverage(self) -> None:
        tmp = self.coverage_path.with_suffix(".tmp")
        tmp.write_text(json.dumps(self.coverage, ensure_ascii=False, indent=2), encoding="utf-8")
        os.replace(tmp, self.coverage_path)

    def accept_batch(self, env: dict) -> dict:
        """Validate, dedupe, persist. Returns ack fields."""
        err = validate_batch(env)
        if err:
            return {"ok": False, "error": err}

        account = str(env["account"])
        chat = env["chat"]
        peer_uid = str(chat["peer_uid"])
        chat_type = int(chat.get("chat_type", 0) or 0)
        peer_kind = str(chat.get("peer_kind", "troop" if chat_type == 2 else "uin"))
        batch_id = str(env["batch_id"])

        accepted: list[str] = []
        duplicates: list[str] = []
        rejected: list[dict] = []

        with self.lock:
            keys_this_batch: set[str] = set()
            for idx, rec in enumerate(env.get("records", [])):
                if not isinstance(rec, dict):
                    rejected.append({"index": idx, "error": "record not an object"})
                    continue
                mid = rec.get("msg_id")
                if not isinstance(mid, str) or not mid:
                    rejected.append({"index": idx, "error": "msg_id must be non-empty string"})
                    continue
                if not isinstance(rec.get("msg_seq"), int) or not isinstance(rec.get("msg_time"), int):
                    rejected.append({"index": idx, "msg_id": mid, "error": "msg_seq/msg_time must be int"})
                    continue
                key = f"{account}|{chat_type}|{peer_uid}|{mid}"
                if key in self.seen or key in keys_this_batch:
                    duplicates.append(mid)
                    continue
                keys_this_batch.add(key)
                self.seen.add(key)
                accepted.append(mid)

            if accepted or rejected:
                today = _dt.date.today().isoformat()
                outdir = self.inbox / today
                outdir.mkdir(parents=True, exist_ok=True)
                outfile = outdir / f"{batch_id}.json"
                payload = dict(env)
                payload["_received_at"] = int(time.time())
                outfile.write_text(json.dumps(payload, ensure_ascii=False, indent=2, default=_json_default), encoding="utf-8")

                with self.seen_path.open("a", encoding="utf-8") as f:
                    for mid in accepted:
                        f.write(f"{account}|{chat_type}|{peer_uid}|{mid}\n")
                    f.flush()
                    os.fsync(f.fileno())

                cov = self.coverage.setdefault(f"{account}|{chat_type}|{peer_uid}", {})
                seqs = [int(r["msg_seq"]) for r in env.get("records", []) if str(r.get("msg_id")) in accepted]
                if seqs:
                    old = cov.get("oldest_seq")
                    new = cov.get("newest_seq")
                    cov["oldest_seq"] = min(seqs) if old is None else min(old, min(seqs))
                    cov["newest_seq"] = max(seqs) if new is None else max(new, max(seqs))
                    cov["updated_at"] = int(time.time())
                    self._save_coverage()

        ack = {
            "v": 1,
            "type": "ack",
            "batch_id": batch_id,
            "accepted": accepted,
            "duplicates": duplicates,
            "rejected": rejected,
            "watermark": {
                "chat": peer_uid,
                "chat_type": chat_type,
                "peer_kind": peer_kind,
                "newest_seq": self.coverage.get(f"{account}|{chat_type}|{peer_uid}", {}).get("newest_seq"),
            },
            "need": [],
            "media_request": [],
            "server_time": int(time.time()),
        }
        return {"ok": True, "ack": ack}


def validate_batch(env: dict) -> str | None:
    """Validate the envelope only.

    Per-record validation is done in Store.accept_batch so one bad record does
    not reject the whole batch; it is returned in the ack's "rejected" list.
    """
    for k in ("v", "type", "batch_id", "account", "chat", "records"):
        if k not in env:
            return f"missing field: {k}"
    if env.get("type") != "batch":
        return f"bad type: {env.get('type')}"
    chat = env.get("chat")
    if not isinstance(chat, dict) or "peer_uid" not in chat:
        return "bad chat"
    if not isinstance(env.get("records"), list):
        return "records must be a list"
    return None


class Handler(socketserver.BaseRequestHandler):
    def handle(self) -> None:
        peer = self.client_address
        log(f"connection from {peer}")
        sock: socket.socket = self.request
        sock.settimeout(60)
        try:
            while True:
                try:
                    env = recv_frame(sock)
                except socket.timeout:
                    log(f"{peer} idle timeout")
                    return
                except Exception as e:
                    log(f"{peer} frame error: {e}")
                    self._send_error(sock, "FRAME", str(e))
                    return
                if env is None:
                    log(f"{peer} disconnected")
                    return

                typ = env.get("type")
                if typ == "hello":
                    self._send(sock, {
                        "v": 1,
                        "type": "hello_ack",
                        "server": SERVER_NAME,
                        "schema": SCHEMA_VERSION,
                        "server_time": int(time.time()),
                        "coverage": self.server.store.coverage,  # type: ignore[attr-defined]
                    })
                    log(f"{peer} hello device={env.get('device_id')} account={env.get('account')}")
                elif typ == "batch":
                    res = self.server.store.accept_batch(env)  # type: ignore[attr-defined]
                    if not res.get("ok"):
                        self._send_error(sock, "VALIDATION", res.get("error", "bad batch"))
                        log(f"{peer} batch rejected: {res.get('error')}")
                    else:
                        ack = res["ack"]
                        self._send(sock, ack)
                        log(f"{peer} batch {ack['batch_id']} accepted={len(ack['accepted'])} dup={len(ack['duplicates'])}")
                elif typ == "media_update":
                    log(f"{peer} media_update batch={env.get('batch_id')} items={len(env.get('updates', []))}")
                    self._send(sock, {
                        "v": 1, "type": "ack", "batch_id": env.get("batch_id"),
                        "accepted": [str(x.get("content_key")) for x in env.get("updates", [])],
                        "duplicates": [], "rejected": [], "server_time": int(time.time()),
                    })
                elif typ == "heartbeat":
                    self._send(sock, {"v": 1, "type": "heartbeat_ack", "server_time": int(time.time())})
                elif typ == "ack":
                    log(f"{peer} client ack {env.get('batch_id')}")
                else:
                    self._send_error(sock, "TYPE", f"unknown type: {typ}")
                    log(f"{peer} unknown type {typ}")
        except Exception as e:
            log(f"{peer} handler error: {e}\n{traceback.format_exc()}")
        finally:
            try:
                sock.close()
            except Exception:
                pass
            log(f"connection closed {peer}")

    def _send(self, sock: socket.socket, obj: dict) -> None:
        sock.sendall(encode_frame(obj))

    def _send_error(self, sock: socket.socket, code: str, detail: str) -> None:
        try:
            self._send(sock, {"v": 1, "type": "error", "code": code, "detail": detail})
        except Exception:
            pass


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

    def __init__(self, addr, handler, store: Store):
        super().__init__(addr, handler)
        self.store = store


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="GCAgent PC receiver (minimal)")
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--data-dir", default=str(Path(__file__).resolve().parent / "data"))
    ns = ap.parse_args(argv)

    data_dir = Path(ns.data_dir).resolve()
    data_dir.mkdir(parents=True, exist_ok=True)
    global _LOG_PATH
    _LOG_PATH = data_dir / "server.log"
    store = Store(data_dir)

    srv = Server((ns.host, ns.port), Handler, store)
    log(f"{SERVER_NAME} listening on {ns.host}:{ns.port} data={data_dir}")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        log("interrupted")
    finally:
        srv.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
