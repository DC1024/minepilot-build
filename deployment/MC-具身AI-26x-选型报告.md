# 支持 Minecraft 26.x 的具身 AI Agent 框架选型

> 目标：你的 bot 目前在 Paper 1.21.1 + mineflayer（Mindcraft 引擎）上，
> 反复被 `Invalid move player packet received` 踢出，根因是 mineflayer 走**协议层解析**，
> 而 minecraft-data/protodef 对 slot 组件数据缺失（PartialReadError），上游迟迟不修。
> 你已决定不再修这个老方案，转向「支持最新版 Minecraft」的具身 AI。
> 本文聚焦：**哪个框架真正支持 26.x 本体版本 + 怎么落地到你的部署**。

---

## 一、先厘清版本号（重要，别搞混）

2026 年起 Mojang 改用**年份命名**，Minecraft 本体版本和模组加载器版本是两套体系：

| 本体版本（Java） | 发布日 | 说明 | 对应加载器版本 |
|---|---|---|---|
| **1.21.1** | 2024-08 | 你当前在用的旧版 | Fabric/NeoForge 21 |
| 1.21.11 | 2025-12 | 1.x 体系最后一个 | 21 |
| **26.1** | 2026-03-24 | 年份命名第一个（即原 1.22） | 26.1 |
| **26.2** | 2026-06-16 | Chaos Cubed | 26.2 |
| **26.3** | 2026-09-15 | 最新版（Wilderness Bound） | 26.3（发布中） |

> 你要求「至少支持 26.0+」= 想要 **26.1 / 26.2 / 26.3** 这些**最新本体版本**。

---

## 二、候选框架对比

### 🏆 首选：Numen「言出法随」（强烈推荐）

- **仓库**：`Dwinovo/minecraft-numen`（GitHub，LGPL-3.0，中文社区活跃，QQ 群 1121662678）
- **定位**：**模组（Fabric / NeoForge）双端**，驱动**服务端真玩家（ServerPlayer）**，每个动作走原生玩家代码路径
- **版本支持**：`1.20.1~1.21.11` + **`26.1.2`、`26.2`**（26.3 分支在跟进中）；Java 21 / 25
- **为什么能绕开你的坑**：
  - 它**不依赖 mineflayer**，不走协议层二次解析 → **没有 PartialReadError，没有 Illegal Move 踢出**
  - 移动/挖掘/战斗全走服务端 API，和真实玩家同一套校验，**不存在「假玩家移动包不合法」问题**
- **能力**：寻路（搭桥/垫脚/隧道/游泳）、挖矿/建造/战斗/合成/熔炼、容器与机器 GUI、技能系统（Markdown 攻略）、跨存档记忆、语音进出、MCP 外接大脑
- **模型接口**：原生 OpenAI + Anthropic，内置 DeepSeek/Kimi/GLM/Qwen 等预设，**你的 DeepSeek-V4-Flash 直接能用**
- **部署要求**：
  - mc-server **必须换成 Fabric 或 NeoForge 服务端**（**不能继续用 Paper**）
  - **服务端和客户端都要装**同款 mod；客户端用自己的 API key 驱动自己的智能体
  - 现成发布在 CurseForge / Modrinth，直接下载 jar 放进 mods/

### 候选2：Numen 生态的 MineAI（Fabric 模组）

- **仓库**：`ailiujiarui/MineAI`（Numen 二开分支）
- 面向科技整合包（AE2/Mekanism/JEI）的集成增强，架构同上（Fabric/NeoForge 模组）
- 同样需换 Fabric/NeoForge 服务端，同样客户端要装
- **优势仅在你需要科技模组联动时**才体现；纯原版用不到

### 候选3：保留 mineflayer 路线（不推荐，你的坑就在这）

- dsh-minecraft-agent、Mindcraft 等仍依赖 mineflayer + minecraft-data
- minecraft-data 目前对 26.x 的 slot 组件数据**尚未跟全**，且 node 端协议解析对 26.x 是**从零适配**，风险比 1.21.1 更大
- 结论：**跟你的初衷相悖，排除**

---

## 三、落地路径对比（对你现有部署的两条路）

你的现状：`mc-server = Paper 1.21.1`（itzg java21 镜像，数据卷 ./minecraft-data，RCON 已开），`ai-agent = Mindcraft(mineflayer) + Web 控制台 :3002`。

| 维度 | 方案A：换 Numen（Fabric/NeoForge） | 方案B：不换服务端 |
|---|---|---|
| **MC 本体** | 升级到 **26.2**（或等 26.3） | 停在 1.21.1（无法满足 26.x 要求） |
| **服务端镜像** | itzg 改 `TYPE=FABRIC`（或 NEOFORGE）+ 26.x | 维持 PAPER |
| **bot 引擎** | 扔掉 Mindcraft/mineflayer，用 Numen mod | 继续 mineflayer（老问题） |
| **客户端** | **每个玩家要装同款 mod**（含你） | 免装（纯服务端） |
| **你现有 ai-agent 容器** | 可退役（Web 控制台、API 配置都可弃用，Numen 自带面板/MCP） | 保留 |
| **PartialReadError / 非法移动踢出** | ✅ 彻底消除（服务端原生玩家） | ❌ 仍在 |
| **部署工作量** | 中（换服务端 + 装 mod） | 零（但问题不解决） |

**关键取舍**：Numen 是**双端模组**——这意味着**上服务器玩的每个玩家都得装这个 mod**（否则服务器会因「服务端装 mod 客户端没装」拒绝连接，或行为异常）。如果你的定位是「纯机器人常驻 + 其他玩家用原版客户端进服」混合，这条会引入客户端兼容问题。

---

## 四、给你的决策建议

**先回答你两个问题：**
1. **你的服务器是纯机器人测试用，还是真人也会来玩？**
   - 纯机器人/你自己控制 → **方案A 最干净**：直接换 Numen + Fabric 服务端 + 26.2，一次到位
   - 会有其他原版客户端玩家进服 → 需要再权衡（他们也得装 mod，或考虑 Numen 是否有「服务端-only」跑法的可能）

2. **愿意升到哪个版本？**
   - **26.2 是当前 Numen 稳定支持的最新**（26.3 分支在跟进，可能已出或即将出）
   - 如果你坚持 26.3，需确认 Numen 26.3 分支是否已发布

---

## 四·补、版本最新状态核实（2026-10-10 实测）

- **Numen 官方支持矩阵（README / Modrinth 同步）**：Minecraft `26.1.2`、`26.2`（Fabric / NeoForge，Java 25）。**26.3（2026-09-15 最新版）尚未纳入**——官方分支主推 `1.21.1` 系，26.x 分支在跟进。
- 结论：**选 Numen 能上到 26.2**，但**26.3 要等官方跟进**（通常几周内）。
- itzg 镜像部署 Fabric/NeoForge 服务端：`TYPE=FABRIC`（+`FABRIC_LOADER_VERSION`）或 `TYPE=NEOFORGE`，mods 放 `/data/mods`，可用 `MODRINTH_PROJECTS` 自动拉取——**完全支持 26.x**。

---

## 五、下一步（等你拍板后我立即执行）

- **路径A（推荐）**：我把 `mc-server` 从 Paper 换成 Fabric/NeoForge 服务端并升到 26.2，把 `ai-agent` 从 Mindcraft 换成 Numen（或做成 Numen 的 MCP 桥接），退役旧 Web 控制台，验证 bot 进服 + 对话 + 挖矿全链路。
- 在你决策前，我先把 Numen 的**最新分支版本、26.3 支持状态、Fabric/NeoForge 服务端搭法**再核实一遍，确保方案落地无坑。

---

*报告生成时间：2026-10-10 · 基于 GitHub/MC Wiki/CurseForge/Modrinth 公开信息*