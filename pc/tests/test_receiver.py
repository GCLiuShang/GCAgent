import json
import socket
import struct
import tempfile
import threading
import time
import unittest
from pathlib import Path

import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import receiver


def encode(obj):
    raw = json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode()
    return struct.pack(">I", len(raw)) + raw


def recv_exact(sock, n):
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("closed")
        buf.extend(chunk)
    return bytes(buf)


def recv_frame(sock):
    (size,) = struct.unpack(">I", recv_exact(sock, 4))
    return json.loads(recv_exact(sock, size))


class ReceiverTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = receiver.Store(Path(self.tmp.name))
        self.srv = receiver.Server(("127.0.0.1", 0), receiver.Handler, self.store)
        self.port = self.srv.server_address[1]
        self.thread = threading.Thread(target=self.srv.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.srv.shutdown()
        self.srv.server_close()
        self.tmp.cleanup()

    def connect(self):
        s = socket.create_connection(("127.0.0.1", self.port), timeout=5)
        s.settimeout(5)
        return s

    def batch(self, batch_id="b1", records=None, **overrides):
        now = int(time.time())
        if records is None:
            records = [
                {"msg_id": f"m-{batch_id}-1", "msg_seq": 101, "msg_time": now, "elements": []},
                {"msg_id": f"m-{batch_id}-2", "msg_seq": 102, "msg_time": now + 1, "elements": []},
            ]
        env = {
            "v": 1, "type": "batch", "batch_id": batch_id,
            "account": "u1", "chat": {"peer_uid": "900000001"}, "records": records,
        }
        env.update(overrides)
        return env

    def test_hello(self):
        with self.connect() as s:
            s.sendall(encode({"v": 1, "type": "hello", "device_id": "t", "account": "u1"}))
            ack = recv_frame(s)
            self.assertEqual(ack["type"], "hello_ack")
            self.assertEqual(ack["schema"], receiver.SCHEMA_VERSION)

    def test_batch_ack_and_dedupe(self):
        with self.connect() as s:
            env = self.batch("b-dedupe")
            s.sendall(encode(env))
            ack = recv_frame(s)
            self.assertEqual(ack["type"], "ack")
            self.assertEqual(sorted(ack["accepted"]), ["m-b-dedupe-1", "m-b-dedupe-2"])
            self.assertEqual(ack["duplicates"], [])
            self.assertEqual(ack["watermark"]["newest_seq"], 102)

            s.sendall(encode(env))
            ack2 = recv_frame(s)
            self.assertEqual(ack2["accepted"], [])
            self.assertEqual(sorted(ack2["duplicates"]), ["m-b-dedupe-1", "m-b-dedupe-2"])

    def test_bad_record_is_rejected_not_batch(self):
        with self.connect() as s:
            env = self.batch("b-bad", records=[
                {"msg_id": "ok-1", "msg_seq": 201, "msg_time": 1},
                {"msg_id": "bad-1", "msg_seq": "not-an-int", "msg_time": 2},
                {"msg_id": "", "msg_seq": 3, "msg_time": 3},
            ])
            s.sendall(encode(env))
            ack = recv_frame(s)
            self.assertEqual(ack["accepted"], ["ok-1"])
            self.assertEqual(len(ack["rejected"]), 2)

    def test_bad_envelope(self):
        with self.connect() as s:
            env = self.batch("b-missing")
            del env["account"]
            s.sendall(encode(env))
            err = recv_frame(s)
            self.assertEqual(err["type"], "error")
            self.assertEqual(err["code"], "VALIDATION")

    def test_media_update(self):
        with self.connect() as s:
            s.sendall(encode({"v": 1, "type": "media_update", "batch_id": "mu1",
                              "updates": [{"content_key": "md5:abc", "state": 1}]}))
            ack = recv_frame(s)
            self.assertEqual(ack["type"], "ack")
            self.assertEqual(ack["accepted"], ["md5:abc"])

    def test_unknown_type(self):
        with self.connect() as s:
            s.sendall(encode({"v": 1, "type": "nope"}))
            err = recv_frame(s)
            self.assertEqual(err["type"], "error")
            self.assertEqual(err["code"], "TYPE")

    def test_fragmented_frame(self):
        with self.connect() as s:
            data = encode(self.batch("b-frag"))
            for i in range(0, len(data), 7):
                s.sendall(data[i:i + 7])
                time.sleep(0.001)
            ack = recv_frame(s)
            self.assertEqual(ack["type"], "ack")
            self.assertEqual(len(ack["accepted"]), 2)


if __name__ == "__main__":
    unittest.main()
