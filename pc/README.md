# GCAgent PC Receiver (minimal)

电脑端最小接收器，用于跑通 Android Agent 与电脑之间的传输/确认闭环。
当前版本**不使用数据库**，只把批次原文落盘，并用追加日志做幂等去重。

协议与数据库设计见仓库 `docs/PC-SERVER.md`；当前实现是 v1 原型，不使用数据库。

## 文件

| 文件 | 作用 |
|---|---|
| `receiver.py` | TCP 接收服务，帧解析、校验、去重、ack、落盘 |
| `sample_client.py` | 模拟手机端，发送 hello/batch，可重发验证幂等 |
| `tests/test_receiver.py` | 单元/集成测试（hello、batch、去重、坏记录、分片、media_update） |
| `data/` | 运行时数据（默认在脚本目录下，可用 `--data-dir` 改） |

## 运行

```bash
cd pc
python3 receiver.py --host 0.0.0.0 --port 8765 \
  --data-dir ./data
```

手机通过局域网或 P2P 隧道访问 `电脑地址:8765`。生产环境不要直接暴露到公网。

模拟手机发送：

```bash
python3 sample_client.py --host 127.0.0.1 --port 8765 --count 3 --resend
```

跑测试：

```bash
python3 -m unittest discover -s tests -v
```

## 帧格式

```
4 字节大端无符号长度 N + N 字节 UTF-8 JSON 对象
```

- 单帧上限 16 MiB。
- 一个 TCP 连接可连续发送多帧。
- 服务端逐帧处理，处理完立即回一帧。

## 消息类型

### hello

```json
{"v":1,"type":"hello","device_id":"...","account":"...","client":"..."}
```

回 `hello_ack`，包含 `schema`、`coverage`（服务端已知的每个会话 seq 范围）。

### batch

```json
{
  "v": 1,
  "type": "batch",
  "batch_id": "b-...",
  "account": "u_...",
  "device_id": "...",
  "chat": {"peer_uid": "900000001", "chat_type": 2, "peer_kind": "troop", "name": "示例群"},
  "records": [
    {
      "msg_id": "...",
      "msg_seq": 519,
      "msg_time": 1794028153,
      "sender_uid": "u_...",
      "sender_uin": 900000002,
      "sender_name": "...",
      "msg_type": 2,
      "sub_type": 1,
      "elements": [{"type":"text","text":"..."}],
      "media": []
    }
  ],
  "coverage": {"oldest_seq": 500, "newest_seq": 519}
}
```

服务端逐条校验 `msg_id`（非空字符串）、`msg_seq`/`msg_time`（整数）。
坏记录进入 `rejected`，好记录照常接收，不会整批拒绝。

### ack

```json
{
  "v": 1, "type": "ack", "batch_id": "b-...",
  "accepted": ["msg_id..."],
  "duplicates": ["msg_id..."],
  "rejected": [{"index": 2, "msg_id": "...", "error": "..."}],
  "watermark": {"chat": "900000001", "newest_seq": 519},
  "need": [],
  "media_request": [],
  "server_time": 1794028153
}
```

- 幂等键：`account|chat_type|peer_uid|msg_id`（群号与企鹅号可能相同，必须带 chat_type）。
- coverage 键：`account|chat_type|peer_uid`。
- 同一批重发时，已收消息进入 `duplicates`，不会重复落盘。
- `need` / `media_request` 目前是占位，Phase 3 再实现缺口协商。

### media_update

```json
{"v":1,"type":"media_update","batch_id":"mu-...","updates":[{"content_key":"md5:...","state":1}]}
```

当前只记日志并回 ack，不保存媒体文件。

### heartbeat

```json
{"v":1,"type":"heartbeat"}
```

回 `heartbeat_ack`。

## 落盘结构

```
data/
  inbox/2026-11-07/batch-....json   # 收到的原始 batch（含 _received_at）
  seen_keys.log                     # 每行一个幂等键，启动时加载
  coverage.json                     # 每个会话的 oldest/newest seq
  server.log                        # 文本日志
```

## 当前边界（刻意保持简陋）

- 没有 SQLite，后续可用 `inbox/*.json` 回放导入。
- 没有鉴权/加密，默认依赖外层 P2P 隧道。
- 没有 `need`/`media_request` 的真实计算。
- 没有断点续传/批次游标；靠幂等键重发即可。
- `coverage.json` 只记录 min/max seq，不记录区间空洞；空洞检测在 Phase 3 做。

## 已验证

- 7 个单元/集成测试通过。
- `sample_client.py --resend` 实测：首次 `accepted=3`，重发 `duplicates=3`。
- 数据文件、seen_keys、coverage.json、server.log 均正确生成。
