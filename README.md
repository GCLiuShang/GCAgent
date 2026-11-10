# GCAgent

GCAgent 是一个**以 Android 手机为传感器与执行器的本地优先个人 Agent**。
它不把聊天内容上传到云端；手机负责采集与静默操作，电脑负责长期存储、
检索和本地 AI 推理。

> 当前状态：个人研究原型。可见 UI 已迁移到 Jetpack Compose；Android 端的
> 企鹅聊天备份链路已可用；电脑端目前是 v1 传输原型，数据库与命令下发仍是
> 设计阶段。微信、本地 AI、更多静默操作尚未实现。

## 为什么叫 Agent

目标不是做一个“导出工具”，而是让手机成为一个可被本地电脑调度的 Agent：

- **感知**：读取手机上的聊天元数据、联系人、文件与（在授权范围内）私有目录数据；
- **记忆**：手机保存轻量索引，电脑保存完整消息、媒体、覆盖区间和任务状态；
- **行动**：通过 VirtualDisplay + 无障碍/注入在后台完成点击、滚动、下载、发送等操作；
- **推理**：电脑上的本地模型读取结构化记忆，生成摘要、检索、提醒或下一步操作；
- **闭环**：电脑下发命令，手机执行并回传结果，失败可重试、可审计。

当前只实现了其中最小的一条闭环：**企鹅聊天记录的采集、入库与电脑端接收**。

## 当前功能

### Android 端

- LSPosed 模块：在企鹅客户端进程内观察聊天列表、消息记录和联系人；
- 本地 SQLite：`users`、`chats`，只增不删；
- root + VirtualDisplay：在独立虚拟屏中启动企鹅、点击联系人/会话、采集联系人与聊天；
- 联系人采集：好友与群列表，写入本地资料库；
- 聊天元数据：消息 ID、seq、时间、发送者、文本/图片/文件等元素；
- 媒体：文件消息可由 UI/自动流程下载，电脑端记录媒体状态；
- 电脑同步：手机主动连接电脑，发送 hello/batch，等待 ack；
- Compose UI：首页模块网格、企鹅备份页、资料库、设置、液态玻璃底栏、开屏动画。

### 电脑端

- `pc/receiver.py`：v1 framed-JSON TCP 接收器；
- hello / batch / ack / media_update / heartbeat；
- 幂等键 `account|chat_type|peer_uid|msg_id`；
- 原始批次落盘、`seen_keys.log`、`coverage.json`；
- 当前**不使用数据库**，作为传输与协议验证原型。

电脑端数据库、元数据处理、命令下发的设计见
[`docs/PC-SERVER.md`](docs/PC-SERVER.md)。

## 产品定位与路线

**定位**：本地优先的 Android 个人 Agent，而不是云同步服务。

计划中的能力：

1. **企鹅聊天备份**：当前已实现最小闭环；继续补齐历史区间、媒体、增量同步。
2. **微信备份**：用相同的“观察 + 本地索引 + 电脑归档”思路，但适配方式会不同。
3. **电脑组网与本地 AI**：手机通过局域网/P2P 访问电脑上的本地模型与知识库；
   聊天记录、文件、联系人只进入自己的电脑。
4. **私有目录读取**：在临时 root 授权下，让 Agent 读取应用私有数据、缓存与附件；
5. **VirtualDisplay 静默操作**：后台完成点击、滚动、下载、整理等操作，不打断当前手机使用；
6. **任务化**：电脑下发采集、补洞、下载、整理、摘要等任务，手机执行并回传。

这些能力会按“先只读、后可写；先单机、后组网；先显式确认、后自动”的顺序推进。

## 兼容性

- Android：最低 Android 13（API 33）；当前主要在 Android 15 / MagicOS 9 上验证。
- 企鹅客户端：当前 hook 适配版本为 **9.3.75**。
- 由于使用 LSPosed hook，客户端升级后类名、字段、Adapter 结构都可能变化；
  升级企鹅客户端前请先确认模块兼容性，否则可能出现采集为空、崩溃或数据不完整。
- 微信、本地 AI、电脑命令下发尚在规划中，不在当前可用功能内。

详细兼容性说明见 [`docs/COMPATIBILITY.md`](docs/COMPATIBILITY.md)。

## 仓库结构

```text
app/                 Android 应用（Compose UI + Java 后端）
  src/main/java/     LSPosed、Collector、Contacts、Exec、Db、Sync 等
  src/main/kotlin/   Compose UI、液态玻璃、开屏
  src/main/assets/   xposed_init、开屏视频、Three.js 本地资源
  src/main/res/      图标、Logo、主题
pc/                  电脑端 v1 接收器、示例客户端、测试
splash-source/       开屏动画生成源（icon.html、render.js、Three.js 页面）
docs/                产品/电脑端/兼容性说明
libs/                Xposed API stub、stubx.jar
dist/                已构建的 debug APK
```

## 构建 Android

要求：JDK 21、Android SDK platform 37、build-tools 36.0.0。

```bash
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk
export GRADLE_USER_HOME="$PWD/.gradle"
./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。

仓库包含公共 debug keystore（`keystore/debug.keystore`，alias `dbg`，
密码 `android`），仅用于让 debug 包可覆盖安装，不要用于正式发布。

## 运行电脑端接收器

```bash
cd pc
python3 receiver.py --host 0.0.0.0 --port 8765 --data-dir ./data
python3 sample_client.py --host 127.0.0.1 --port 8765 --count 3 --resend
python3 -m unittest discover -s tests -v
```

手机通过局域网或 P2P 隧道访问电脑的 `8765` 端口；当前版本没有鉴权/加密，
不要直接暴露到公网。

## 开屏动画资源

`splash-source/` 包含生成任意分辨率 MP4 的源：

- `icon.html`：Three.js 场景与逐帧渲染入口；
- `render.js`：Puppeteer 截帧 + FFmpeg 合成；
- `package.json`、`package-lock.json`、图标/Logo SVG。

示例：

```bash
cd splash-source
npm install
node render.js 1224 2700 60
```

生成的 `splash_<w>x<h>.mp4` 放入 `app/src/main/assets/splash/`。

## 风险与边界

- 此类客户端自动化通常不符合对应软件的用户协议，存在账号限制、封禁或数据风险；
  本项目仅用于个人设备上的个人数据备份与研究，不对外提供托管服务。
- 本项目与企鹅、微信等产品的官方无关联，也未获得其授权。
- 不承诺兼容所有客户端版本；hook 适配版本见兼容性文档。
- 请只处理你本人有权访问的数据，并自行承担使用风险。

## 许可

液态玻璃部分源自 KernelSU manager（GPL-3.0），因此合并作品按 GPL-3.0
分发；另有 Apache-2.0 组件。详见 `LICENSE`、`NOTICE`、`LICENSE-APACHE-2.0`。
