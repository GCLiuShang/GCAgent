#!/usr/bin/env python3
"""Minimal phone-side simulator for the GCAgent PC receiver.

Usage:
  python3 sample_client.py --host 127.0.0.1 --port 8765 --count 3
  python3 sample_client.py --host 127.0.0.1 --port 8765 --resend

It sends a hello, then one batch. With --resend it sends the same batch
again to demonstrate idempotent duplicate handling.
"""

from __future__ import annotations

import argparse
import json
import socket
import struct
import time

MAX_FRAME = 16 * 1024 * 1024


def encode_frame(obj: dict) -> bytes:
    raw = json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    if len(raw) > MAX_FRAME:
        raise ValueError("frame too large")
    return struct.pack(">I", len(raw)) + raw


def recv_exact(sock: socket.socket, n: int) -> bytes:
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("peer closed")
        buf.extend(chunk)
    return bytes(buf)


def recv_frame(sock: socket.socket) -> dict:
    (size,) = struct.unpack(">I", recv_exact(sock, 4))
    if size <= 0 or size > MAX_FRAME:
        raise ValueError(f"bad frame size {size}")
    return json.loads(recv_exact(sock, size).decode("utf-8"))


def send(sock: socket.socket, obj: dict) -> dict:
    sock.sendall(encode_frame(obj))
    return recv_frame(sock)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--account", default="u_demo")
    ap.add_argument("--peer-uid", default="900000001")
    ap.add_argument("--count", type=int, default=3)
    ap.add_argument("--resend", action="store_true")
    ns = ap.parse_args()

    now = int(time.time())
    records = []
    for i in range(ns.count):
        records.append({
            "msg_id": f"demo-{now}-{i}",
            "msg_seq": 1000 + i,
            "msg_time": now + i,
            "sender_uid": "u_demo",
            "sender_uin": 900000002,
            "sender_name": "demo",
            "msg_type": 2,
            "sub_type": 1,
            "elements": [{"type": "text", "text": f"demo message {i}"}],
            "media": [],
        })

    batch = {
        "v": 1,
        "type": "batch",
        "batch_id": f"batch-{now}",
        "account": ns.account,
        "device_id": "wsl-demo",
        "chat": {"peer_uid": ns.peer_uid, "chat_type": 2, "name": "示例群"},
        "records": records,
        "coverage": {"oldest_seq": 1000, "newest_seq": 1000 + ns.count - 1},
    }

    with socket.create_connection((ns.host, ns.port), timeout=10) as s:
        s.settimeout(10)
        print("hello ->", json.dumps(send(s, {
            "v": 1, "type": "hello", "device_id": "wsl-demo",
            "account": ns.account, "client": "sample/0.1",
        }), ensure_ascii=False))
        print("batch ->", json.dumps(send(s, batch), ensure_ascii=False))
        if ns.resend:
            print("resend ->", json.dumps(send(s, batch), ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
