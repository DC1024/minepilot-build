# Zspace AI Minecraft Agent — PartialReadError 追查结案报告

日期：2026-10-10 · 仓库：`github.com/DC1024/ai-agent`（main）· 部署：z4pro `192.168.43.102` Docker Compose 项目 `minecraft`

---

## 一、结论速览

| 问题 | 状态 | 关键结论 |
|---|---|---|
| `PartialReadError`（登录时一次） | **已定位，判定为非致命，维持现状** | 根因是 `minecraft-protocol@1.68.0` 用了**自己嵌套的 minecraft-data@3.117.0**，其 1.21.1 槽位组件数据缺项 |
| 该错的「降级修复」 | **已验证不可行，已回退** | 回退到 3.97.0 会导致 `unsupported protocol version: 26.1`，agent 完全无法登录 |
| `[LoginGuard] Disconnected: [object Object]` → agent 永久停摆 | **已修复并上线** | Paper 踢出被误判为「致命不可重连」，退避逻辑从未生效 |
| Paper 踢出本身（`Invalid move player packet received`） | **遗留** | 现改为退避重试（最多 10 次），但未根治 |

---

## 二、PartialReadError 根因（含证据链）

### 2.1 失败的就是 `declare_recipes` 这一个包

`FullPacketParser` 抓到的失败帧：`frame=108271`，首字节 varint `0x77 = 119` → **`declare_recipes`**，含 **1290 条配方**。

```
PartialReadError: Read error for undefined :
  Missing characters in string, found size is 108271 expected size was 114153
  at Object.string (compiled:107)
  at Object.ArmorTrimMaterial (compiled:898)
  at Object.SlotComponent (compiled:720)
```

**它是非致命的**：minecraft-protocol 的包都带长度前缀分帧，一个包解析失败只丢该包，不会污染后续流。所以**每次登录只报一次**，bot 随后正常登录、spawn、聊天（实测 `<AIBot> Hello world! I am AIBot`）。

### 2.2 镜像里其实有三份 minecraft-data

| 解析起点 | 实际解析到的路径 | 版本 |
|---|---|---|
| `mineflayer` | `mineflayer/node_modules/minecraft-data` | 3.117.0 |
| **`minecraft-protocol`（协议解析真正用的）** | `minecraft-protocol/node_modules/minecraft-data` | **3.117.0** |
| 顶层（`package.json` 声明的 `^3.97.0`） | `node_modules/minecraft-data` | 3.97.0 |

1.21.1 的 `protocol.json` 两份**并不相同**：3.97.0 = 244901 字节，3.117.0 = 247587 字节。
（此前「两份逐字节一致」的判断是错的，已作废。）

### 2.3 同一份字节，两份数据给出相反结果

把**同一份 108271 字节的失败帧**分别喂给三份数据解析：

| 数据 | 解释器 | 编译模式 |
|---|---|---|
| 顶层 **3.97.0** | ✅ 精确消费 108271 字节 | ✅ 精确消费 108271 字节 |
| 嵌套 **3.117.0**（mcp） | ❌ `101 is not in the mappings value`（recipe #790 `minecraft:smithing_trim`） | ❌ 越界（108271 vs 114153） |
| 嵌套 **3.117.0**（mineflayer） | ❌ 同上 | ❌ 同上 |

### 2.4 决定性的自证实验

在**真实运行进程中**挂 hook：解析失败时，用**同一份字节**当场新建一个 proto 重新解析。

```
[live] FIRST FAIL frame=108271  err=Missing characters in string ...
[live] interpreter re-parse OK consumed=108271
[live] compiled    re-parse OK consumed=108271
```

线上 deserializer 失败、同一进程内新建的解析器成功 ⇒ **不是 protodef 的代码生成 bug，而是「线上解析器所用的那份数据」不对**。

---

## 三、被否决的方案（重要，避免后人重踩）

**方案**：删掉 `minecraft-protocol` 的嵌套 minecraft-data，让它回退到顶层 3.97.0（3.97.0 对这份字节解析完全正确）。

**实测结果**：PartialReadError 确实消失，但 **agent 再也登录不进去了**：

```
[mindcraft] MC server found. （3.117.0 时是这样的）
→ 换成 3.97.0 后：
Error: unsupported protocol version: 26.1
```

**原因**：`mcserver.js:getServer()` 会调用 minecraft-protocol 做**版本支持校验**，3.97.0 对 `minecraft-protocol@1.68.0` 太旧，校验抛错 → 被 `mindcraft.js:createAgent()` 的 catch 吞掉 → `settings.minecraft_version` 被置为 `null` → `createClient` 回退到 `defaultVersion`，而本环境该值恰好是**无效字符串 `26.1`** → 致命。

**另一条路也堵死**：`minecraft-data@latest` **就是 3.117.0**（一次性 node 容器 `npm i minecraft-data@latest` 实测），不存在「升到最新版就好了」。

**结论**：mcp 的数据解析与 mindcraft 的版本协商**耦合在同一份依赖上**，动版本会连带打断登录。故 PartialReadError 维持现状（非致命、每次登录一次、已被韧性改造兜住）。真正的修法需上游修 1.21.1 的 slot 组件数据，或改造「依赖嵌套 mcp 数据」这一结构。

提交记录：`a286ea4`（尝试）→ `15c5074`（回退，附完整原因）。

---

## 四、已修复并上线的真 bug：断线后永久停摆

### 现象（正是最初反馈的问题）

```
[mindcraft] AIBot spawned.
[mindcraft] [LoginGuard] Disconnected: [object Object]
[mindcraft] Agent process exited with code 2 and signal null
[mindcraft] Agent AIBot exited with a fatal, non-reconnectable error (code 2). Not restarting.
```

登录后约 10s 被 Paper 以 `Invalid move player packet received` 踢出，随即被判为「致命、不可重连」，**agent 永久离线**。韧性改造本身是对的（指数退避 + maxFailures=10 + 健康运行后重置计数），但退避逻辑**从未有机会生效**。

### 根因两条

1. **原因字符串化不可用**：Paper/vanilla 的踢出原因是 Minecraft chat component，`toString()` 得到 `"[object Object]"`；且服务端实际给的是**翻译键** `multiplayer.disconnect.invalid_player_movement`（下划线形式），而关键词表里写的是 `'invalid move'` → **一条都匹配不上**。
2. **兜底分支 `isFatal` 写死为 `true`**：任何「没认出来」的断线直接判死。

### 修改（`mindcraft/src/agent/connection_handler.js`）

- 原因归一化：string / Error / chat component 统一转成「可读文本 + JSON」的字符串，保证是 string 且不含 `[object Object]`；
- `movement` 关键字补充翻译键形式：`invalid_player_movement`、`moved_too_quickly`、`player_movement`、`moved wrongly`；
- **兜底 `isFatal: true → false`**（未识别断线按可重连处理，交给退避逻辑收口）；
- 空原因同样判为可重连（通常是 socket 直接关闭）；
- 兜底文案改为 `Disconnected: <reason>. Reconnecting.`

**fatal 只保留关键词命中的确定不可恢复场景**（被 ban / 名字冲突 / 版本不符）。回退保障：`AgentProcess` 仍有 `maxFailures=10` + 指数退避，且只在「从未稳定运行」时放弃，不会无限重启。

### 验证

本地跑 `parseKickReason` 11 组用例全部通过（Paper 踢出的三种形态均归入 `movement` 且 `isFatal=false`；banned / 名字冲突仍为 `isFatal=true`）。

部署后观察 170 秒：

| 指标 | 结果 |
|---|---|
| `Not restarting` | **0**（原先必现） |
| `Restarting agent` | 5 |
| `Movement validation`（日志可读了） | 5 |
| mc-server | 反复 `AIBot joined the game`，退避 3s→6s→12s→24s→48s |

提交：`3e0fb85`。

---

## 五、遗留问题与建议

**Paper 踢出本身没有解决。** mc-server 侧确认每次都是 `Invalid move player packet received`，且至少一次紧跟在 `AIBot was slain by Drowned` / 重生之后 —— 与既有判断一致：**bot 死亡/重生瞬间发出的移动包不符合 Paper 校验**。

当前行为：退避重试，但 `maxFailures=10` 且「从未稳定运行」时会放弃 ⇒ **长期仍会退化成「重试约 10 次后停摆」**。

建议的后续方向（按性价比排序）：
1. **抑制死亡/重生窗口期的移动包**：在 `spawn`（重生）后短暂屏蔽 `position`/`position_look` 发送，避开最易触发校验的瞬间；
2. **加固位置节流**：`mindcraft/src/utils/mcdata.js::initBot` 已有 `POSITION_THROTTLE_MS = 50`，可评估是否需按 Paper 限制进一步收紧；
3. **绕开战斗**：bot 频繁「Fighting drowned!」并因此死亡，可评估是否禁用 pvp 自动反击（同时也能减少死亡→重生→踢出的连锁）；
4. 长期：跟踪上游 mineflayer / minecraft-data 对 1.21.1 slot 组件与 Paper 移动校验的修复。

---

## 六、附：本次改动清单

| 提交 | 内容 |
|---|---|
| `3e0fb85` | fix(agent)：让无法识别的断线可重连（已上线） |
| `6f99e6d` | fix(console)：控制台改为订阅 MindServer 真实状态，修复状态恒显示离线（已上线） |
| `15c5074` | Revert：回退「移除嵌套 minecraft-data」方案（附完整原因） |
| `a286ea4` | fix(docker)：尝试移除嵌套 minecraft-data（已由 15c5074 回退） |

代码改动文件：`mindcraft/src/agent/connection_handler.js`、`Dockerfile`（净变化为零，已回退至原状）。

诊断产生的临时文件（容器内 `/data` 下的 dump/hook/探针脚本、构建机 `/tmp/mcdtest`）**均已清理**；`mindcraft/src/process/init_agent.js` 里的临时 hook 已移除并确认线上 `[dump]` 日志计数为 0。

---

## 七、追加：控制台状态「假离线」修复（commit `6f99e6d`，已上线）

**反馈**：网页 `http://192.168.43.102:3002/` 上「AI 机器人」「MC 服务器」两个圆点恒为红（离线），但 bot 实际在服里（mc-server 日志 `AIBot joined the game` + 游戏内中文聊天）。

**根因**（与 PartialReadError 无关，是纯粹的占位实现）：
```js
// ai-agent/src/index.js（修复前）
startServer({
  getBot: () => null,                               // ← 恒 null
  getMcInfo: () => ({ online: false, note: '...' }), // ← 恒 false
});
// ai-agent/src/server.js（修复前）
botOnline: Boolean(bot?.entity),   // 恒 false
mc: getMcInfo ? getMcInfo() : null // 恒 offline
```
bot 跑在 Mindcraft 子进程里，本进程从不持有 mineflayer 实例 ⇒ 两个字段恒为 false，页面与真实状态完全脱钩。

**修复**（只改 `src/`，未动 `mindcraft/`）：
1. `server.js` 连上 MindServer(:8080) 后订阅真实信号：`agents-status`（`[{name,in_game,socket_connected}]`）+ `listen-to-agents` 触发服务端每 1s 推 `state-update`。
2. 在线判定：**5s 内收到过推送 且 `liveStates[agentName]` 存在**。因为无人在线时服务端也推 `{}`，时间戳始终新鲜，可安全用于过期判定。
3. MC 服务器状态：容器内对 `host:port`（取 Mindcraft settings）做 TCP 探测，4s 缓存；版本取 `settings.minecraft_version`。
4. `/api/status` 新增 `engine{connected,inGame,processConnected,statusTs}` 与 `live{dimension,position,biome,weather,timeLabel,activity,nearbyPlayers}`；血量/饥饿取自 `state.gameplay`。
5. 前端：离线时区分「引擎在线，bot 未进服/重启中」与「引擎未连接」；MC 行显示版本与 `host:port`。

**部署与验证**：
- 三处 md5 一致（本地 = 构建上下文 = 容器内 `/app/src`）：`server.js 6c8b5c49…`、`index.js 78b7f1a5…`；旧文件备份为 `src/*.bak-pre6f99e6d`。
- `docker compose build ai-agent` 147s（npm/apt 层命中缓存）+ `up -d`。
- `/api/status` 实测：`botOnline:true`、`botHealth:18`、`botFood:19`、`mc.online:true (mc-server:25565, 1.21.1)`、`engine.{connected,inGame,processConnected}=true`、`live={overworld, river, 105.87/62.08/209.5, ...}`。
- **连续采样第 3 次翻为 false** —— 正好抓到 Paper 踢出→重启的窗口，证明状态是真实采集而非写死。
- 网页端最终显示：`AI 机器人：AIBot 在线` / `MC 服务器：在线 · 1.21.1 · mc-server:25565` / `血量 10 · 饥饿 17 · overworld @ 103.63,61.82,209.55 · river · mode:self_defense`。

**副产品**：控制台现在能直接**看见**「踢出 → 退避 → 重连」的循环，第五节遗留问题（Paper 非法移动包踢出）从此有可观测的界面，不再需要靠翻 docker logs 判断死活。
