# 电脑端设计：数据库、元数据与命令下发

本文描述 GCAgent 电脑端的**目标设计**。当前仓库里的 `pc/receiver.py` 是
v1 传输原型：只落盘原始批次、维护幂等键和粗粒度 coverage，不建数据库，
也没有真正的命令下发。本文用于指导下一阶段实现，避免把原型当成最终架构。

## 1. 角色与数据边界

```text
Android Agent（手机）
  ├─ 观察/采集：联系人、会话、消息元数据、媒体状态
  ├─ 本地轻量库：users / chats（只增不删）
  ├─ 执行器：VirtualDisplay、无障碍、点击/滚动/下载/发送
  └─ 主动连接电脑，发送 batch，拉取命令

PC Server（电脑）
  ├─ 接收/校验/幂等
  ├─ 完整消息库、媒体库、覆盖区间、缺口
  ├─ 任务队列与命令状态机
  ├─ 本地检索/本地 AI
  └─ 只监听本机/局域网/P2P，不暴露公网
```

原则：

- 手机是采集端，电脑是长期记忆端；聊天原文不进入第三方云。
- 手机只保留索引；完整消息、元素、媒体和覆盖区间在电脑。
- 所有写入必须幂等；网络可断、批次可重发、电脑可重启。
- 命令必须可审计、可重试、可过期；手机执行结果必须回传。

## 2. 传输层

当前 v1 协议：

```text
4 字节大端无符号长度 N + N 字节 UTF-8 JSON
```

- 单帧上限 16 MiB；
- 一个 TCP 连接可连续多帧；
- 服务端逐帧处理并立即回一帧；
- 手机主动连接电脑，适合 NAT/移动网络下的 P2P 隧道。

消息类型：

| 类型 | 方向 | 作用 |
|---|---|---|
| `hello` | 手机 → 电脑 | 设备、账号、客户端版本、协议版本 |
| `hello_ack` | 电脑 → 手机 | schema、coverage、待执行命令 |
| `batch` | 手机 → 电脑 | 一个会话的一批消息 + coverage |
| `ack` | 电脑 → 手机 | accepted/duplicates/rejected、watermark、need、media_request、commands |
| `media_update` | 手机 → 电脑 | 媒体状态/路径/校验和更新 |
| `heartbeat` | 手机 → 电脑 | 保活、拉取命令 |
| `heartbeat_ack` | 电脑 → 手机 | 服务端时间、命令、限流提示 |

生产环境应在其上叠加：

- TLS 或 mTLS；
- 设备 token/配对码；
- 重放保护（nonce + 时间窗）；
- 速率限制与最大帧/批次限制。

## 3. 数据库选型

建议 SQLite（WAL）作为单机默认：

- 零运维，适合个人电脑；
- 单用户、低并发，写入按批次事务；
- 后续需要多设备/远程检索时，可平滑迁移到 PostgreSQL；
- 原始批次仍保留 JSON 归档，便于重放与审计。

数据库文件：`data/gcagent.db`；原始批次：`data/inbox/YYYY-MM-DD/`；
媒体：`data/media/<account>/<chat>/<content_key>`。

## 4. 目标 Schema

以下为建议 DDL（SQLite 方言）。字段名保持稳定，便于手机端与查询层复用。

```sql
PRAGMA journal_mode=WAL;
PRAGMA foreign_keys=ON;

CREATE TABLE schema_migrations (
  version     INTEGER PRIMARY KEY,
  applied_at  INTEGER NOT NULL
);

CREATE TABLE accounts (
  id           INTEGER PRIMARY KEY,
  platform     TEXT NOT NULL,              -- 'penguin' | 'wechat' | ...
  account_uid  TEXT NOT NULL,
  display_name TEXT,
  created_at   INTEGER NOT NULL,
  UNIQUE(platform, account_uid)
);

CREATE TABLE devices (
  id           INTEGER PRIMARY KEY,
  account_id   INTEGER NOT NULL REFERENCES accounts(id),
  device_id    TEXT NOT NULL,
  name         TEXT,
  app_version  TEXT,
  last_seen_at INTEGER,
  UNIQUE(account_id, device_id)
);

CREATE TABLE chats (
  id            INTEGER PRIMARY KEY,
  account_id    INTEGER NOT NULL REFERENCES accounts(id),
  platform      TEXT NOT NULL,
  chat_type     INTEGER NOT NULL,          -- 1 私聊, 2 群聊
  peer_uid      TEXT NOT NULL,
  peer_kind     TEXT NOT NULL,             -- 'uin' | 'troop'
  name          TEXT,
  owner_uid     TEXT,
  metadata_json TEXT,
  first_seen_at INTEGER NOT NULL,
  last_seen_at  INTEGER NOT NULL,
  UNIQUE(account_id, chat_type, peer_uid)
);

CREATE TABLE messages (
  id           INTEGER PRIMARY KEY,
  chat_id      INTEGER NOT NULL REFERENCES chats(id),
  msg_id       TEXT NOT NULL,
  msg_seq      INTEGER,
  msg_time     INTEGER NOT NULL,           -- 毫秒
  sender_uid   TEXT,
  sender_uin   TEXT,
  sender_name  TEXT,
  msg_type     INTEGER,
  sub_type     INTEGER,
  raw_json     TEXT NOT NULL,
  ingested_at  INTEGER NOT NULL,
  UNIQUE(chat_id, msg_id)
);

CREATE INDEX idx_messages_chat_seq ON messages(chat_id, msg_seq);
CREATE INDEX idx_messages_time ON messages(msg_time);

CREATE TABLE elements (
  id          INTEGER PRIMARY KEY,
  message_id  INTEGER NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
  idx         INTEGER NOT NULL,
  kind        TEXT NOT NULL,               -- text/image/file/...
  text        TEXT,
  media_key   TEXT,
  meta_json   TEXT,
  UNIQUE(message_id, idx)
);

CREATE TABLE media (
  id           INTEGER PRIMARY KEY,
  content_key  TEXT NOT NULL,
  chat_id      INTEGER REFERENCES chats(id),
  message_id   INTEGER REFERENCES messages(id),
  file_name    TEXT,
  mime         TEXT,
  size_bytes   INTEGER,
  sha256       TEXT,
  local_path   TEXT,
  state        INTEGER NOT NULL DEFAULT 0, -- 0 missing,1 downloading,2 done,3 failed
  updated_at   INTEGER NOT NULL,
  UNIQUE(content_key)
);

CREATE TABLE coverage (
  chat_id      INTEGER PRIMARY KEY REFERENCES chats(id),
  oldest_seq   INTEGER,
  newest_seq   INTEGER,
  updated_at   INTEGER NOT NULL
);

CREATE TABLE gaps (
  id           INTEGER PRIMARY KEY,
  chat_id      INTEGER NOT NULL REFERENCES chats(id),
  from_seq     INTEGER NOT NULL,
  to_seq       INTEGER NOT NULL,
  state        TEXT NOT NULL DEFAULT 'open', -- open|requested|filled|ignored
  created_at   INTEGER NOT NULL,
  updated_at   INTEGER NOT NULL,
  UNIQUE(chat_id, from_seq, to_seq)
);

CREATE TABLE batches (
  id           INTEGER PRIMARY KEY,
  batch_id     TEXT NOT NULL UNIQUE,
  device_id    TEXT,
  account_id   INTEGER REFERENCES accounts(id),
  raw_path     TEXT NOT NULL,
  received_at  INTEGER NOT NULL,
  accepted     INTEGER NOT NULL,
  duplicates   INTEGER NOT NULL,
  rejected     INTEGER NOT NULL
);

CREATE TABLE commands (
  id           INTEGER PRIMARY KEY,
  command_id   TEXT NOT NULL UNIQUE,
  device_id    TEXT NOT NULL,
  account_id   INTEGER REFERENCES accounts(id),
  kind         TEXT NOT NULL,
  payload_json TEXT NOT NULL,
  state        TEXT NOT NULL,              -- pending|sent|acked|done|failed|expired
  created_at   INTEGER NOT NULL,
  sent_at      INTEGER,
  acked_at     INTEGER,
  expires_at   INTEGER,
  result_json  TEXT
);

CREATE INDEX idx_commands_device_state ON commands(device_id, state);
```

### 为什么这样分表

- `messages` 只放可检索的标量字段，元素放 `elements`，媒体放 `media`；
- `raw_json` 保留原始记录，便于字段升级后回放；
- `coverage` 与 `gaps` 分离：coverage 是“已知边界”，gaps 是“已知空洞”；
- `commands` 独立，避免命令状态和聊天数据混在一起；
- `batches` 记录每次接收，支持审计与重放。

## 5. 元数据处理

### 5.1 hello

手机发送设备、账号、客户端版本、协议版本。电脑返回：

- `schema`：数据库迁移版本；
- `coverage`：每个会话已知 oldest/newest seq；
- `commands`：待执行命令；
- `server_time`。

电脑根据 `platform + account_uid + device_id` 找/建 account/device。

### 5.2 batch 校验

逐条校验：

- `msg_id` 非空字符串；
- `msg_seq`/`msg_time` 为整数（当前手机端时间是秒或毫秒都可能出现，
  服务端统一归一化为毫秒：`< 100000000000` 视为秒）；
- `chat_type` 只能是 1/2；
- `peer_kind` 与 chat_type 匹配；
- elements 必须是数组；未知类型保留在 `raw_json`。

坏记录进入 `rejected`，好记录照常入库；不要整批拒绝。

### 5.3 幂等键

```text
account|chat_type|peer_uid|msg_id
```

必须带 `chat_type`，因为群号和用户号可能相同。电脑端唯一索引
`messages(chat_id,msg_id)` 与 `seen_keys` 双重保证。

### 5.4 单批事务

```text
BEGIN IMMEDIATE
  upsert account/device/chat
  insert batch 记录
  for record:
    insert message ON CONFLICT DO NOTHING
    insert elements
    update coverage min/max
  detect gaps
  update commands/watermarks
COMMIT
```

任何一步失败回滚；原始 batch 已先落盘，可重放。

### 5.5 coverage 与 need

- coverage 只记录 min/max，不表示中间无洞；
- 当新 batch 的 seq 与已有区间不连续时，生成/合并 gap；
- `ack.need` 返回需要补的区间（按优先级排序，限制条数）；
- 手机端用 `Backfill` 补洞，补完后电脑端把 gap 标记 `filled`；
- `need` 是建议不是命令，手机可以拒绝/延后。

### 5.6 media

- batch 中媒体元素带 `content_key`（如 md5）与元数据；
- 电脑端在 `media` 表建 `state=missing`；
- `ack.media_request` 请求手机上传/回传本地路径；
- 手机 `media_update` 上报 state/size/sha256/local_path；
- 电脑端校验后 state=done；失败保留错误并可重试。

## 6. 命令下发

当前 `ack` 里 `commands` 是占位。目标设计：

```json
{
  "v": 1,
  "type": "ack",
  "batch_id": "...",
  "commands": [
    {
      "command_id": "cmd-20261110-0001",
      "kind": "backfill",
      "payload": {"chat_id": 123, "from_seq": 400, "to_seq": 480},
      "created_at": 1794300000000,
      "expires_at": 1794303600000
    }
  ]
}
```

手机回：

```json
{"v":1,"type":"command_ack","command_id":"cmd-...","state":"accepted"}
```

执行完回：

```json
{"v":1,"type":"command_result","command_id":"cmd-...",
 "state":"done","result":{"filled":80,"errors":[]} }
```

命令类型（建议）：

| kind | 作用 |
|---|---|
| `collect_contacts` | 触发联系人/群列表采集 |
| `backfill` | 指定会话补历史区间 |
| `download_media` | 下载指定 content_key/消息的媒体 |
| `sync` | 回传指定会话/时间范围 |
| `probe` | 检查客户端版本、hook 状态、VD 可用性 |
| `cancel` | 取消 pending 任务 |

状态机：

```text
pending -> sent -> acked -> done
                    \-> failed
pending/sent/acked -> expired
```

规则：

- `command_id` 全局唯一，手机端持久化已执行 ID，重复下发直接回 `done`；
- `expires_at` 过期不再执行；
- 命令按设备隔离，不能跨设备重放；
- 高风险命令（写操作/发送消息）必须显式 `confirm=true` 或电脑端二次确认；
- 结果包含结构化 error，不要只回“失败”。

## 7. 本地 AI 与组网

目标不是把聊天上传到云，而是：

```text
手机 Agent --LAN/P2P--> 电脑 Gateway --localhost--> 本地模型/向量库
```

- 电脑只监听局域网/P2P 地址；默认 `127.0.0.1` 自测；
- 手机通过配对码/token 连接；
- AI 读取的是电脑上的结构化消息库，不是原始 UI；
- 需要“读私有目录/静默操作”时，由电脑下发命令，手机执行并回传审计结果；
- 所有模型调用、命令、文件访问都记录本地审计日志。

## 8. 当前实现与差距

`pc/receiver.py` 已实现：

- framed JSON、hello/batch/ack/media_update/heartbeat；
- 幂等键、原始 batch 落盘、coverage min/max、坏记录隔离；
- 7 个单元/集成测试。

尚未实现：

- SQLite schema/迁移；
- elements/media 的规范化入库；
- gap 检测与 `need` 计算；
- command 表/状态机/结果回传；
- 鉴权、TLS、速率限制；
- 多设备冲突处理与去重合并；
- 本地 AI 检索层。

下一步建议按 `schema -> batch ingest -> coverage/gaps -> commands -> auth` 的顺序实现，
不要先把命令下发做成无鉴权的远程执行通道。

## 9. 安全与隐私

- 聊天记录、联系人、媒体属于高敏感数据；默认不离开本机/本局域网。
- 电脑端数据库建议启用磁盘加密（SQLCipher 或 OS 级加密）。
- 原始 batch 与媒体目录设置最小权限；不要提交到 Git。
- 命令通道必须鉴权；禁止把 8765 端口直接映射到公网。
- 日志避免打印消息正文、手机号、企鹅号；必要时脱敏。
- 删除策略：提供按账号/会话/时间范围的本地删除与媒体清理。
