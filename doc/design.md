# Cluster Tool 调度模拟器 — 设计文档

> **目标读者**：AI Agent / LLM / 后续开发者  
> **编写原则**：结构化的需求、约束、架构、算法描述，减少歧义，便于 AI 理解和维护

---

## 目录

1. [项目概述](#1-项目概述)
2. [需求列表](#2-需求列表)
3. [硬件拓扑与腔室布局](#3-硬件拓扑与腔室布局)
4. [工艺约束](#4-工艺约束)
5. [配置系统](#5-配置系统)
6. [架构与技术栈](#6-架构与技术栈)
7. [核心算法](#7-核心算法)
8. [腔室状态机](#8-腔室状态机)
9. [API 端点](#9-api-端点)
10. [关键设计决策与理由](#10-关键设计决策与理由)
11. [AM (Auto Maintenance) 逻辑](#11-am-auto-maintenance-逻辑)
12. [报告生成](#12-报告生成)
13. [回放系统](#13-回放系统)
14. [Bug 修复历史](#14-bug-修复历史)
15. [构建与运行](#15-构建与运行)

---

## 1. 项目概述

**Cluster Tool Scheduler** 是一个半导体集群设备（Cluster Tool）的离散事件模拟调度器。

- **设备类型**：EPI（外延生长）Cluster Tool
- **核心目标**：在满足硬性最大驻留时间（Max Dwell Time）约束的前提下，最大化晶圆吞吐量（WPH）
- **特点**：纯配置驱动，所有设备参数、工艺时间、AM 任务、生产任务均从 JSON 配置文件读取，无硬编码数值

**关键指标**：
- 目标 WPH ≥ 5.6（33 wafer / ~7.2h 模拟时间）
- Dwell 违例 = 0（硬约束）
- OnLoadClean 到首片 wafer 放置间隔 ≤ 100s

---

## 2. 需求列表

### 2.1 功能需求

| ID | 需求 | 说明 |
|----|------|------|
| F1 | 配置驱动 | 所有设备、工艺、任务、AM 从 JSON 读取，无硬编码 |
| F2 | 秒级离散模拟 | 1 模拟秒 = 1 tick，离散事件驱动 |
| F3 | Job 调度 | 支持 ControlJob（CJ）包含多个 ProcessJob（PJ），serial/parallel 模式 |
| F4 | AM 集成 | OnLoadClean（每腔一次/CJ）、1X Clean（每片前）、IdlePurge（空闲阈值触发） |
| F5 | 在线贪心调度 | 每个 tick 评估全局状态，做出即时决策 |
| F6 | 实时状态推送 | WebSocket STOMP 推送模拟状态到前端 |
| F7 | HTML 报告 | 完整统计、Wafer×Station 矩阵、甘特图、Wafer History |
| F8 | PPT 报告 | 12 页自动生成演示文稿 |
| F9 | 回放系统 | HTML 报告中嵌入全流程 SVG 动画回放，支持播放/暂停/调速 |
| F10 | 数据自愈 | 自动检测并修复 wafer 位置与腔室状态不一致 |

### 2.2 非功能需求

| ID | 需求 | 说明 |
|----|------|------|
| NF1 | 速度可调 | 1x ~ 100x 模拟速度 |
| NF2 | 前后端分离 | Vue 3 + Spring Boot，REST + WebSocket |
| NF3 | 单臂无 Swap | Robot 单臂单指，不支持原子交换 |
| NF4 | 中文界面 | 前端和报告均为中文 |

---

## 3. 硬件拓扑与腔室布局

```
┌─────────────────────────────────────────────────────────────────┐
│  EFEM (大气环境)                                                  │
│  ┌──────┐ ┌──────┐ ┌──────┐     ┌─────────┐                    │
│  │ LP1  │ │ LP2  │ │ LP3  │     │ Aligner │                    │
│  │25slots│ │25slots│ │25slots│     │ (4.4s)  │                    │
│  └──────┘ └──────┘ └──────┘     └─────────┘                    │
│                  ↕ ATM Robot (单臂单指)                           │
├─────────────────────────────────────────────────────────────────┤
│  真空腔                                                            │
│  ┌──────┐ ┌──────┐                                              │
│  │ LL1  │ │ LL2  │  Batch LoadLock (容量 25, Pump 126s)        │
│  └──────┘ └──────┘                                              │
│       ↕ TM1 (Robot1, 单臂)                                       │
│  ┌──────────┐ ┌──────────┐                                      │
│  │PreClean1 │ │PreClean2 │  (280s ±10s)                         │
│  └──────────┘ └──────────┘                                      │
│       ↕ TM1                                                      │
│  ┌────┐┌────┐ ┌────┐┌────┐                                     │
│  │PT1 ││PT1 │ │PT2 ││PT2 │  Passthrough (各2槽)                 │
│  │_S0 ││_S1 │ │_S0 ││_S1 │  PT1_S0 & PT2_S1 = CoolingStation   │
│  │ ❄  ││    │ │    ││ ❄  │                                     │
│  └────┘└────┘ └────┘└────┘                                     │
│       ↕ TM2 (Robot2, 单臂)                                       │
│  ┌────┐┌────┐┌────┐┌────┐                                      │
│  │EPI1││EPI2││EPI3││EPI4│  (2120s ±30s)                        │
│  └────┘└────┘└────┘└────┘                                      │
└─────────────────────────────────────────────────────────────────┘
```

### 3.1 腔室类型与数量

| 类型 | ID | 数量 | 关键参数 |
|------|-----|------|---------|
| LoadPort (FOUP) | LP1, LP2 | 2（有效使用） | 每 FOUP 25 slots |
| Aligner | ALIGNER | 1 | 对准 4.4s |
| LoadLock (BLL) | LL1, LL2 | 2 | Pump 126s, Vent 168s, Load 5s, Unload 5s |
| PreClean | PreClean1, PreClean2 | 2 | 工艺 280s±10s, MaxDwell 120s |
| Passthrough | PT1_S0, PT1_S1, PT2_S0, PT2_S1 | 4 | MaxDwell 300s, Cooling 60s (指定槽) |
| EPI | EPI1, EPI2, EPI3, EPI4 | 4 | 工艺 2120s±30s, MaxDwell 100s |

### 3.2 机械手

| Robot | ID (引擎内部) | tmId | 覆盖范围 |
|-------|-------------|------|---------|
| ATM | ATM1 | EFEM | FOUP ↔ Aligner ↔ LL |
| TM1 | Robot1 | TM1 | LL ↔ PreClean ↔ PT |
| TM2 | Robot2 | TM2 | PT ↔ EPI |

**约束**：均为单臂单指，不支持原子交换（swap）。机械手传输时间 = pick + rotate + place，每对腔室有独立配置。

PT fwd 方向（PreClean→EPI）和 PT ret 方向（EPI→LL）偏好不同 PT 槽：fwd 优先 buffer 槽，ret 优先 cooling 槽。

---

## 4. 工艺约束

### 4.1 最大驻留时间（Max Dwell）— 硬约束

| 腔室类型 | Max Dwell | 计算公式 | 说明 |
|---------|-----------|---------|------|
| PreClean | 120s | `currentTime - processStart - totalTime` | 工艺结束后等待被取走的时间 |
| EPI | 100s | 同上 | 最关键约束，2110s 工艺后不允许长等待 |
| PT | 300s | `currentTime - lastUsedTime` | 包含 fwd 和 ret 方向 |
| LoadLock | 300s | 同上 | wafer 返回后等待 batch vent |

### 4.2 驻留安全检查

```java
// 伪代码: checkMaxDwellTimes()
for each chamber with wafer:
    if wafer is being handled by a robot (armWaferId matches) → skip
    if chamber is COOLING → skip (cooling is intentional dwell)
    if chamber is CLEANING/PURGING → skip
    dwell = computeDwell(chamber)
    if dwell > maxDwell and not already warned:
        emit WARN event (dedup per wafer per chamber)
```

### 4.3 其他硬约束

| 约束 | 值 | 说明 |
|------|-----|------|
| CoolingStation 冷却时间 | 60s | EPI 返回的 wafer 必须经过冷却槽 |
| 安全裕度 (safety margin) | 10s | 在死锁预防的 forward-look 中使用的保守裕量。EPI 工艺波动仅 ~3s，10s 提供充足缓冲 |
| BLL batch vent | 168s | 仅在 batch 内所有 wafer 返回后才开始 |

---

## 5. 配置系统

所有配置位于 `conf/` 目录，JSON 格式。

### 5.1 device.json — 硬件拓扑

定义 Cluster Tool 的完整硬件布局：腔室类型与数量、机械手参数、LoadPort 容量、Passthrough 槽位分配。

**关键字段**：`efem`(大气环境/ATM/Aligner)、`loadlocks`(Batch LL)、`transferModules`(TM1/TM2 机械手及操作时间)、`chambers`(PreClean/EPI)、`passthroughs`(槽位数/冷却槽分配)。

> 详细字段说明与完整 JSON 示例参见 **[device.md](conf/device.md)**。

### 5.2 schedule.json — 工艺与模拟参数

定义各腔室的工艺 recipe（时间/波动/最大驻留）、调度策略（wafer 启动间隔/安全裕度）、时序参数（LL Pump/Vent/Cooling）、模拟参数（速度/wafer 数）。

**关键字段**：`recipes`(PRECLEAN/EPI/PASSTHROUGH/LOADLOCK)、`scheduling`(policy/waferStartIntervalSec/dwellSafetyMarginSec)、`timing`、`simulation`。

> 详细字段说明与完整 JSON 示例参见 **[schedule.md](conf/schedule.md)**。

### 5.3 sequence.json — Wafer 流程定义

定义 wafer 从 FOUP 到返回 FOUP 的完整流转步骤。9 步流程：`LP → Aligner → BLL → PreClean → PT_FWD → EPI → PT_RET → BLL_RET → LP`。

每步定义：step, station, action, robot (入站/出站), next, recipeKey。

> 详细说明参见 **[sequence.md](conf/sequence.md)**。

### 5.4 job.json — 生产任务

定义生产任务（Job）层级：`Job → ControlJob(CJ) → ProcessJob(PJ) → WaferSubset`。

支持 `serial` 模式（PJ 顺序执行）。Wafer 编号格式支持单编号 `"5"` 和范围 `"1-25"`。命名规则：`W{LP_INDEX}.{SLOT_NUMBER}`。

> 详细字段说明、模式说明与示例参见 **[job.md](conf/job.md)**。

### 5.5 am.json — Auto Maintenance

定义腔室的自动维护（AM）任务，三种类型：

| 类型 | 触发时机 | 适用腔室 |
|------|---------|---------|
| `ON_LOAD_CLEAN` | CJ 启动时，每腔执行一次 | EPI、PRECLEAN |
| `PRE_PROCESS` | 每片 wafer 工艺前 | EPI（1X Clean） |
| `IDLE_PURGE` | 腔室空闲超过阈值 | PRECLEAN |

调度器从 `appliesTo[].chamberType` 动态收集腔室类型，无硬编码。

> 详细字段说明、触发逻辑与完整示例参见 **[am.md](conf/am.md)**。

### 5.6 Profile 系统

通过 `conf/context.json` 中的 `activeProfile` 字段选择配置集：

```json
{ "activeProfile": "sige-epi" }
```

配置文件实际路径为 `conf/{activeProfile}/`，如 `conf/sige-epi/device.json`。切换 profile 只需修改 `context.json` 并 reload（`POST /api/config/reload`）。

### 5.7 可配置项总览

| 类别 | 可配置项 | 配置文件 |
|------|---------|---------|
| 设备拓扑 | 腔室数量、类型、机械手操作时间 | device.json |
| 工艺 | 处理时间、变化范围、最大驻留 | schedule.json |
| 调度 | 策略、最大同时 wafer、启动间隔、安全裕度 | schedule.json |
| 模拟 | 速度、总 wafer 数、时间步长 | schedule.json |
| 流程 | wafer 流转步骤、每步的 recipe 和 robot | sequence.json |
| 任务 | CJ/PJ 模式、LP 分配、wafer 范围 | job.json |
| AM | OnLoadClean、1X Clean、IdlePurge 的时间和阈值 | am.json |
| LL 时序 | Pump/Vent/Load/Unload 时间 | schedule.json |
| Cooling | 冷却时间、冷却槽分配 | schedule.json / device.json |

---

## 6. 架构与技术栈

### 6.1 技术栈

| 层 | 技术 | 版本 |
|----|------|------|
| 后端 | Java + Spring Boot | 21 / 3.2.5 |
| 前端 | Vue 3 + Vite | 3.4.21 / 5.2 |
| 通信 | REST (HTTP) + WebSocket (STOMP/SockJS) | — |
| 报告 | Python 3 | — |
| PPT | python-pptx | — |

### 6.2 架构图

```
┌──────────────────────────────────────────────────────────┐
│                     Frontend (Vue 3)                      │
│  ┌────────────┐ ┌──────────────┐ ┌────────────────────┐  │
│  │ControlPanel│ │ ToolLayout   │ │ GanttChart         │  │
│  │(start/stop)│ │ (SVG layout) │ │ (interactive bars) │  │
│  └────────────┘ └──────────────┘ └────────────────────┘  │
│         │              │                   │              │
│         └──────────────┼───────────────────┘              │
│                        │ HTTP REST + WebSocket STOMP       │
├────────────────────────┼──────────────────────────────────┤
│                 Backend (Spring Boot 3.2.5)                │
│  ┌────────────────────┼────────────────────────────────┐  │
│  │   SchedulerController (/api/*)                       │  │
│  │   - simulation CRUD, config, report, replay          │  │
│  └────────────┬───────┴────────────────────────────────┘  │
│  ┌────────────┴───────────────────────────────────────┐   │
│  │   SimulationService                                 │   │
│  │   - @Scheduled(fixedRate=10ms) loop                 │   │
│  │   - tick accumulator & speed control                │   │
│  │   - WebSocket broadcast                             │   │
│  └────────────┬───────────────────────────────────────┘   │
│  ┌────────────┴───────────────────────────────────────┐   │
│  │   SchedulerEngine (核心, ~1489 行)                  │   │
│  │   - tick()：1秒步进                                  │   │
│  │   - 状态：chambers, robots, wafers, gantt, events   │   │
│  │   - 算法：调度、死锁预防、驻留检查、AM、自愈         │   │
│  └────────────┬───────────────────────────────────────┘   │
│  ┌────────────┴───────────────────────────────────────┐   │
│  │   ConfigService                                     │   │
│  │   - 加载 5 个 JSON 配置                              │   │
│  │   - 支持热重载 (/api/config/reload)                 │   │
│  └─────────────────────────────────────────────────────┘  │
├──────────────────────────────────────────────────────────┤
│              Report Generation (Python)                    │
│  generate_report.py  →  simulation_report.html            │
│  generate_ppt.py     →  EPI_Scheduler_Report.pptx         │
└──────────────────────────────────────────────────────────┘
```

### 6.3 文件结构

```
epi/
├── conf/                     # JSON 配置文件
│   ├── context.json           # 当前 activeProfile
│   ├── sige-epi/              # sige-epi 工艺配置集
│   │   ├── device.json, schedule.json, sequence.json
│   │   ├── job.json, am.json
│   └── *.json                 # 默认配置（向后兼容）
├── doc/                       # 项目文档
│   ├── design.md              # 本设计文档
│   └── conf/                  # 各配置文件的详细说明
│       ├── device.md, schedule.md, sequence.md
│       ├── job.md, am.md
├── backend/                  # Spring Boot
│   └── src/main/java/com/epi/scheduler/
│       ├── EpiSchedulerApplication.java
│       ├── config/           # CorsConfig, WebSocketConfig
│       ├── controller/       # SchedulerController (18 endpoints)
│       ├── engine/           # SchedulerEngine (1489 行核心)
│       ├── model/            # Config POJOs + DTOs
│       └── service/          # SimulationService, ConfigService
├── frontend/                 # Vue 3 + Vite
│   └── src/
│       ├── App.vue, main.js
│       ├── api/scheduler.js
│       └── components/
│           ├── ControlPanel.vue, ToolLayout.vue, GanttChart.vue
├── generate_report.py        # HTML 报告生成器 (~1340 行)
├── generate_ppt.py           # PPT 报告生成器 (~447 行)
└── generate_images.py        # PNG 图片生成器 (~246 行)
```

---

## 7. 核心算法

### 7.1 主循环 `tick()` — 每秒执行一次

```
tick():
  1. currentTimeSec++
  2. updateChamberTimers()      // 倒计时 → 完成处理
  3. checkMaxDwellTimes()       // 驻留违例检查
  4. updateRobots()             // 机械手完成 → 触发 onComplete 回调
  5. scheduleATM()              // ATM: FOUP→Aligner→LL
  6. scheduleTM1()              // TM1: PT返回 → PC→PT → LL→PC（优先级递减）
  7. scheduleTM2()              // TM2: EPI→PT → PT→EPI
  8. manageBatchLL()            // BLL batch 完成 → 卸载
  9. prepareBatch()             // 准备新 batch
  10. triggerOnLoadClean()      // 触发 OnLoadClean
  11. triggerIdlePurge()        // 触发 IdlePurge
  12. healWaferLocations()      // 数据自愈
  13. captureReplaySnapshot()   // 每 10s 记录回放快照
  14. IF completedWafers >= wafers.size → COMPLETED
```

**关键原则**：每个 tick 内，所有决策基于同一步的全局状态（同步决策）。

### 7.2 TM1 调度优先级

```
scheduleTM1():
  // 优先级从高到低
  1. tryTM1ReturnFromPT()     // PT ret → LL（清空返回路径）
  2. tryTM1PreCleanToPT()     // PC done → PT fwd（推进流程）
  3. tryTM1LLToPreClean()     // LL → PC（拉入新 wafer）
```

**优先级理由**：PT 返回优先于 PT 前行，防止 PT 槽满导致 EPI→PT 无法完成。

### 7.3 TM2 调度优先级

```
scheduleTM2():
  1. tryTM2EpiToPT()          // EPI done → PT ret（默认优先，避免 EPI dwell 超标）
  2. tryTM2PTToEpi()          // PT fwd → EPI（含 Clean-Transport 重叠优化）
```

#### 7.3.1 Clean-Transport 重叠优化

1X Clean 时间是**确定性的**（从 am.json 读取，无随机波动），因此可以精确预测清洁完成时刻。
当 EPI 腔处于 CLEANING 状态且剩余时间 ≤ PT→EPI 传输时间（73s）时，TM2 提前开始搬运 wafer，
使 wafer 到达 EPI 腔时清洁刚好完成，gap 降至 0s。

```
tryTM2PTToEpi():
  epi = findAvailableEpi()          // 优先选 IDLE 腔
  IF epi == null:
    epi = findEpiAboutToFinishClean()  // 次选即将完成清洁的腔（剩余 ≤73s）
  IF epi == null → return false
  
  // ... 开始搬运，onComplete 时 chamber 已 IDLE

findEpiAboutToFinishClean():
  // 选 CLEANING 且 remainingTime ≤ PT→EPI_transport_time 的腔
  // 优先选 remainingTime 最大的（最接近 73s），最大化重叠
  return chambers.stream()
    .filter(c => c.type=="EPI" && c.state==CLEANING && c.waferId==null && c.remainingTime <= transportTime)
    .max(comparing(c.remainingTime))
```

**效果**：Clean 末尾 73s 与 TM2 传输完全重叠，消除 post-clean idle gap。确定性清洁时间（无随机波动）保证了此优化的安全性。

### 7.4 死锁预防与 Entry Control — `canPullWaferFromLL()`

前向预测（lookahead）的准入控制，决定何时从 LL 拉取新 wafer 进入真空侧。

```
canPullWaferFromLL():
  demand = PT_fwd_count + PreClean_busy_count + 1

  // 1. 快速路径: IDLE + CLEANING 的 EPI 腔 >= demand → 立即允许
  available = EPI_idle + EPI_cleaning
  IF available >= demand → ALLOW (更新 lastWaferStartTime)

  // 2. Per-Chamber 动态时序: 预测下一个 EPI 腔的就绪时间，
  //    反推最优拉 wafer 时刻，使 wafer 到达 PT 时恰好 Clean 还剩 73s。
  nextReadyIn = getNextEpiReadyTime()        // 最快 EPI 腔还需多久完成 Clean
  pipelineTransit = getPipelineTransitTime() // LL→PC→PT 最大耗时
  transportTime = PT→EPI 传输时间 (73s)

  // 理想等待时间: 让 wafer 在 Clean 结束前 73s 到达 PT
  idealWait = nextReadyIn - pipelineTransit - transportTime

  // Clamp: 不能快于 minStagger（防止管线溢出），不能慢于 staggerInterval
  effectiveStagger = clamp(idealWait, minStagger, staggerInterval)

  IF currentTime - lastWaferStartTime < effectiveStagger → BLOCK

  // 3. 后备: 检查 PROCESSING 腔中最早完成的 N 个是否在 PT dwell 窗口内就绪
  ...（同旧逻辑，使用 epiRemaining 排序 + maxWait 判断）
```

**关键改进**：`getNextEpiReadyTime()` 为每个 EPI 腔独立预测就绪时间（基于实际 remainingTime），替代了全局固定 stagger。这消除了因 pipeline 相位偏移导致的 EPI3/EPI4 wafer 到达过晚问题。

```
getNextEpiReadyTime():
  best = INF
  FOR each EPI chamber:
    IF IDLE && waferId==null:           readyIn = 0
    ELSE IF CLEANING && waferId==null:  readyIn = remainingTime
    ELSE IF PROCESSING:                 readyIn = remainingTime + cleanTime
    best = min(best, readyIn)
  RETURN best

getPipelineTransitTime():
  // LL→PC 传输 + PC 工艺(最坏情况) + PC→PT 传输
  RETURN LL_TO_PC_time + (PC_avg + PC_variation) + PC_TO_PT_time
```

**minStagger** = `staggerInterval / 2`（≈313s），防止管线溢出。`staggerInterval` = `(EPI_process + EPI_clean - transportTime) / EPI_count` ≈ 626s。

### 7.5 前向压力控制 — `canMovePCToPT()`

防止 PT fwd 槽满载导致 PreClean→PT 卡死：

```
canMovePCToPT():
  demand = PT_fwd_count + 1
  
  IF EPI_idle + EPI_cleaning >= demand → ALLOW
  IF any EPI_cleaning will complete within PT_maxDwell - safetyMargin → ALLOW
  IF any EPI PROCESSING will complete within limit → ALLOW
  IF PreClean dwell 即将超标 → FORCE ALLOW（安全覆盖）
```

### 7.6 可用 EPI 查找

#### 7.6.1 `findAvailableEpi()` — IDLE 腔

```java
// 排除条件:
//   - state != IDLE
//   - waferId != null
//   - awaitingOnloadClean.contains(id)  // 等待 OnLoadClean 的腔不可用
// 优先选择: lastUsedTime 最小的（最久未使用，round-robin）
```

#### 7.6.2 `findEpiAboutToFinishClean()` — 即将完成清洁的腔

```java
// 条件:
//   - type == EPI
//   - state == CLEANING
//   - waferId == null
//   - remainingTime <= PT→EPI_transport_time (73s)
// 优先选择: remainingTime 最大的（最接近 73s = 最大重叠）
```

```java
// 排除条件:
//   - state != IDLE
//   - waferId != null
//   - awaitingOnloadClean.contains(id)  // 等待 OnLoadClean 的腔不可用
// 优先选择: lastUsedTime 最小的（最久未使用）
```

### 7.7 PT 槽分配策略

- **fwd 方向**（PreClean→EPI）：优先使用 **buffer 槽**（非 cooling 槽）
- **ret 方向**（EPI→LL）：优先使用 **cooling 槽**
- 目的：分离前向和返回流量，减少 PT 争夺

### 7.8 BLL Batch 管理

```
LL 状态循环: IDLE → LOADING → PUMPING(126s) → READY → VENTING(168s) → DONE → UNLOADING(5s) → IDLE

checkBatchLLComplete():
  统计已返回 wafer 数 (flowStep >= 15)
  IF 返回数 >= batchTotal → 触发 VENTING
  IF serial mode → currentPJIndex++（推进到下一个 PJ）
```

### 7.9 数据自愈 — `healWaferLocations()`

```
for each wafer where location starts with "PT":
  IF chamber.waferId != wafer.id:
    IF flowStep >= 15:
      // 返回 wafer 丢失: 放到 BLL
    ELSE IF flowStep == 8 || 13:
      // 前向/返回 wafer 槽位被清空: 恢复
```

---

## 8. 腔室状态机

```
ChamberState 枚举:
  IDLE, LOADING, PUMPING, READY, PROCESSING, DONE, VENTING, UNLOADING, COOLING, CLEANING, PURGING

状态转换:
  IDLE → CLEANING  (OnLoadClean 开始)        → IDLE (完成)
  IDLE → CLEANING  (1X Clean 开始, EPI only) → IDLE (完成)
  IDLE → PURGING   (IdlePurge 开始, PC only) → IDLE (完成)
  IDLE → LOADING   (FOUP→LL 装载)            → PUMPING → READY
  READY → PROCESSING (wafer 进入)            → DONE → IDLE (wafer 取走)
  READY → VENTING  (batch 完成)              → DONE → UNLOADING → IDLE
  READY (PT ret  wafer 进入后，CoolingStation) → COOLING(60s) → READY
```

**PURGING vs CLEANING**：两者在 Java 枚举中是不同的值，确保前端和甘特图可区分。
- CLEANING = OnLoadClean 或 1X Clean（橙色 `#FF5722`）
- PURGING = IdlePurge（紫色 `#9C27B0`）

---

## 9. API 端点

### 9.1 模拟控制

| Method | Path | Body | Description |
|--------|------|------|-------------|
| POST | `/api/simulation/start` | `{cjId?}` | 启动模拟（可选 CJ） |
| POST | `/api/simulation/pause` | — | 暂停 |
| POST | `/api/simulation/reset` | — | 重置 |
| POST | `/api/simulation/step` | — | 单步 |
| POST | `/api/simulation/speed` | `{speed: N}` | 设置速度 (1-100) |

### 9.2 状态查询

| Method | Path | Returns |
|--------|------|---------|
| GET | `/api/simulation/state` | SimulationSnapshot |
| GET | `/api/simulation/gantt` | List\<GanttEntry\> |
| GET | `/api/simulation/events` | List\<String\> |
| GET | `/api/simulation/foups` | Map\<FoupState\> |
| GET | `/api/simulation/robots` | List\<RobotState\> |
| GET | `/api/simulation/replay` | List\<SimulationSnapshot\> (10s 间隔) |

### 9.3 配置与报告

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/config/device` | 设备配置 |
| GET | `/api/config/schedule` | 工艺配置 |
| GET | `/api/config/job` | 任务配置 |
| GET | `/api/config/am` | AM 配置 |
| GET | `/api/config/sequence` | 流程配置 |
| POST | `/api/config/reload` | 热重载 |
| POST | `/api/report/generate` | 生成 HTML 报告 |

### 9.4 WebSocket

- STOMP endpoint: `/ws`
- Topic: `/topic/state` — 推送 SimulationSnapshot

---

## 10. 关键设计决策与理由

| 决策 | 理由 |
|------|------|
| **单臂单指、无 Swap** | 符合实际设备约束 |
| **纯配置驱动、无硬编码** | 最大化灵活性，不同 recipe/设备仅需修改 JSON |
| **每秒 tick（1s 粒度）** | 平衡精度和模拟速度 |
| **TM1 PT 返回优先于 PT 前行** | 防止 EPI 完成时 PT 槽满载死锁 |
| **PT fwd→buffer, ret→cooling** | 分离前向/返回流量 |
| **OnLoadClean 延迟计算 + wafer 计数器触发** | 首个腔用延迟公式对齐首片到达，后续腔按实际 wafer 流量触发，确保所有腔间隔均匀 |
| **IdlePurge 纯空闲驱动** | 仅依赖 idle 时长，不依赖是否处理过 wafer |
| **PURGING ≠ CLEANING** | 前端和甘特图清晰区分两种 AM 操作 |
| **Clean-Transport 重叠** | 1X Clean 时间确定性（无随机波动），在清洁末尾 73s 启动 PT→EPI 传输，gap 降至 0s |
| **Per-Chamber 动态时序** | 替代全局固定 stagger。每个 EPI 腔独立预测就绪时间，反推最优拉 wafer 时刻，消除相位偏移导致的 gap 不一致 |
| **安全裕度 10s** | EPI 工艺实际波动仅 ~3s，10s = 3x+ 缓冲，足够安全且减少不必要的等待 |
| **HTML 报告嵌入 SVG 回放** | 无需服务器即可在浏览器中查看完整模拟过程 |

---

## 11. AM (Auto Maintenance) 逻辑

### 11.1 OnLoadClean

- **EPI 腔**：首个腔在延迟公式计算的最早启动时间后开始，后续腔由 `wafersEnteredPreClean` 计数器触发
- **PreClean 腔**：按固定 stagger 间隔启动，每 tick 一个腔
- 每次 CJ 每腔仅执行一次

### 11.2 1X Clean (PRE_PROCESS)

- **仅 EPI 腔**：每当 EPI 腔完成工艺（wafer 被 TM2 取走至 PT）后立即启动
- 如果后续没有 wafer 需要 EPI，则跳过（停止条件）
- **Clean-Transport 重叠**：1X Clean 时间为确定值（am.json 配置，`Math.ceil(cleanTimeSec)`），无随机波动。调度器在清洁剩余 ≤73s（PT→EPI 传输时间）时提前启动 TM2 搬运，使 wafer 到达时清洁刚好完成，gap = 0s
- 此优化对 OnLoadClean 同样生效（同为确定性时长）

### 11.3 IdlePurge

- **仅 PreClean 腔**：当腔 IDLE 时间 ≥ 180s 时启动（123s）
- 纯空闲时间驱动，与是否处理过 wafer 无关
- purge 完成后重置 idle 计时器，继续空闲 180s 则再次触发

---

## 12. 报告生成

### 12.1 HTML 报告 (generate_report.py)

通过 `/api/*` 拉取所有数据，生成自包含 HTML：

| 章节 | 内容 |
|------|------|
| 核心指标 | 完成 wafer 数、WPH、总时间、平均周期 |
| 工艺参数 | 所有 recipe 时间、LL/PT/Cooling 时序、机械手操作时间 |
| 腔室使用 | 总时间、次数、平均（EPI 使用 active window 计算） |
| 约束违反 | 从真实甘特图 dwell 时间计算的违例表格 |
| Wafer×Station 矩阵 | P(处理)/D(驻留) 分解，带颜色高亮 |
| 甘特图 | 完整时序图，带腔室利用率百分比 |
| Wafer History | 下拉选择 + 分步时间线表格 |
| SVG 回放 | 和运行界面一致的 tool layout 动画 |

### 12.2 PPT 报告 (generate_ppt.py)

12 页深色主题幻灯片：项目概述、UI 描述、算法、约束、配置、测试结果、甘特图、腔室利用图、架构、自愈、总结。

### 12.3 自动生成

前端检测 `status === 'COMPLETED'` 后自动调用 `/api/report/generate`。

---

## 13. 回放系统

### 13.1 数据采集

`SchedulerEngine.captureReplaySnapshot()` — 每 10 模拟秒采集一个轻量快照：

```java
SimulationSnapshot {
    currentTimeSec, status, completedWafers, totalWafers,
    chambers: {id: {type, state, waferId, remainingTimeSec, totalTimeSec, waferCount}},
    wafers: [{id, foupIndex, slotIndex, location, state, flowStep}],
    robots: {id: {tmId, state, arm1WaferId, currentAction, remainingTimeSec}}
}
```

### 13.2 回放导出

- API: `GET /api/simulation/replay` → JSON 数组
- Python: `fetch("/simulation/replay")` → 嵌入 HTML 作为 `const REPLAY = [...]`

### 13.3 SVG 回放播放器

- 使用和 `ToolLayout.vue` 完全相同的 SVG 坐标 (`viewBox="0 0 1140 520"`)
- 机械臂角度根据 `sourceChamber`/`targetChamber` 计算，CSS `transition: 0.3s` 平滑过渡
- 控制: 播放/暂停、步进 (±1 快照)、调速 (0.25x~100x)、进度条拖拽

---

## 14. Bug 修复历史

| Bug | 根因 | 修复 |
|-----|------|------|
| `lastUsedTime` +dur 重复计算 | lambda 捕获 `currentTimeSec` 后又 +dur | 所有 6 处 lambda 中移除 `+dur` |
| gantt +dur 同理 | `addGanttEntry`/`closeGanttEntry` 同样 +dur | 统一使用 `currentTimeSec` |
| LL2 过早 vent | `checkBatchLLComplete` 用全局 wafer 计数 | 改回 per-LL 计数 |
| OnLoadClean 共享 `lastOnloadCleanStart` | EPI 和 PreClean 共用同一个计时器，互相干扰 | 拆分为 `lastOnloadCleanStartPerType` Map |
| EPI2/3/4 未做 OnLoadClean | stagger 被 PreClean 打断，始终不触发 EPI2+ | per-type stagger + 首腔延迟计算 |
| PreClean "1X Clean complete" 误导 | `handleChamberTimerDone` 硬编码事件文字 | 添加 `chamberCleanType` Map 动态区分 |
| IdlePurge 无限循环（虚假） | EPI OnLoadClean 缺失导致全局死锁，purge 为表象 | 修复 OnLoadClean 后恢复正常 |
| EPI3/4 OnLoadClean-Wafer 间隔过大 | 固定时钟 stagger 不匹配实际管道节奏 | EPI2+ 改用 `wafersEnteredPreClean` 计数器触发 |
| 机械手不可见 | 采样间隔 100s 太长(机械手动作 11-17s)，布局不匹配 | 降为 10s + 对齐运行界面 SVG 布局 |
| Clean→Process gap 大且不一致 (73-135s) | (1) 固定 stagger 不区分腔室相位偏移 (2) 等待清洁完成后才启动传输 | (1) Per-Chamber 动态时序 (2) Clean-Transport 重叠优化 |
| dwellSafetyMarginSec=40 过于保守 | EPI 实际波动仅 3s，40s 裕度过大导致不必要的等待 | 降至 10s，增大了 canPullWaferFromLL/canMovePCToPT 的准入窗口 |

---

## 15. 构建与运行

### 15.1 构建

```bash
# 后端
cd backend && mvn package -DskipTests

# 前端（前端 dist 已包含在 JAR 中，修改前需构建）
cd frontend && npm install && npm run build
```

### 15.2 运行

```bash
# 启动（必须在 backend/ 目录，配置文件通过 ../conf/ 相对路径读取）
cd backend
java -jar target/epi-scheduler-1.0.0.jar &

# 启动 CJ1 模拟
curl -X POST http://localhost:8080/api/simulation/start \
  -H "Content-Type: application/json" -d '{"cjId":"CJ1"}'

# 设置速度
curl -X POST http://localhost:8080/api/simulation/speed \
  -H "Content-Type: application/json" -d '{"speed":100}'

# 生成报告
curl -X POST http://localhost:8080/api/report/generate
```

### 15.3 开发

- 前端开发服务器: `cd frontend && npm run dev`
- 后端热重载配置: `POST /api/config/reload`
- 注意: 后端 JAR 运行时 cwd 必须是 `backend/`（配置路径 = `../conf/`）
