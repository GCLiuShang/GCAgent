# 兼容性与版本标注

GCAgent 依赖 LSPosed hook 与 Android 版本能力，**不是版本无关的通用工具**。
本文件明确当前已验证的边界，避免把“某台机器上可用”误认为“所有环境可用”。

## Android

| 项目 | 当前值 |
|---|---|
| minSdk | 33（Android 13） |
| targetSdk | 35 |
| 主要验证环境 | Android 15 / MagicOS 9 |
| 设备 | HONOR 90 Pro（REP-AN00） |
| root | 临时 root（KernelSU LKM + GhostLock 方案） |
| LSPosed | 2.2.1（API 102） |

要求：

- LSPosed 已激活，模块作用域包含企鹅客户端；
- root 可用，能执行 `app_process` 与创建 VirtualDisplay；
- 无障碍服务可用（部分 UI 自动化路径依赖）；
- Android 13 以下不在当前支持范围。

## 企鹅客户端

| 项目 | 当前值 |
|---|---|
| 适配版本 | **9.3.75** |
| 适配方式 | LSPosed hook + View/Adapter 反射 |
| 风险 | 客户端升级后类名、字段、Adapter 结构变化会导致采集失败 |

**版本标注规则**：

- README、Release Notes、模块设置页必须写明“适配企鹅 9.3.75”；
- 每次适配新版本，新增一条兼容性记录：客户端版本、Android 版本、验证日期、
  已验证功能、已知问题；
- 未验证的客户端版本不要默认宣称可用；
- hook 失效时优先保证“不崩溃、不写坏数据”，宁可采集为空并记录错误。

已知 hook 依赖（高层）：

- 聊天列表/消息 Adapter 的 differ list 与消息记录字段；
- 联系人页的字母分组 Adapter 与群列表 Adapter；
- 聊天 Activity 生命周期与 RecyclerView 替换；
- 部分文件/媒体入口（调试用途，非主链路）。

这些点对客户端版本非常敏感，因此**不要在没有版本说明的情况下分发**。

## 微信

微信备份尚未实现。未来适配时需要单独评估：

- 客户端版本与 hook 点；
- 数据库/缓存格式；
- 是否会与企鹅模块共享 Collector/DB/PC 协议。

在实现前，文档不得把微信列为“已支持”。

## 电脑端

| 项目 | 当前值 |
|---|---|
| Python | 3.10+ |
| 协议 | v1 framed JSON，4 字节大端长度 |
| 数据库 | 尚未接入（原型只落盘） |
| 鉴权 | 尚未实现 |
| 网络 | 局域网/P2P；不要暴露公网 |

## 版本记录模板

```text
日期：
Android 版本 / ROM：
企鹅客户端版本：
GCAgent versionName/versionCode：
LSPosed 版本：
已验证：联系人 / 群列表 / 消息元数据 / 媒体 / 补历史 / PC 同步
已知问题：
```

## 免责声明

本项目与企鹅、微信等产品官方无关联。项目涉及客户端自动化与本地数据访问，
仅用于个人设备上的个人数据备份与研究，请自行评估并承担风险。
