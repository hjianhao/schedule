# EPI Cluster Tool 调度模拟器 — 深度分析

> 分析日期：2026-05-22
> 分析范围：全项目（后端、前端、配置、报告、文档）

---

## 目录

1. [项目概要](#1-项目概要)
2. [目录结构](#2-目录结构)
3. [核心引擎分析](#3-核心引擎分析)
4. [后端架构分析](#4-后端架构分析)
5. [前端架构分析](#5-前端架构分析)
6. [配置系统分析](#6-配置系统分析)
7. [报告系统分析](#7-报告系统分析)
8. [发现的问题](#8-发现的问题)
9. [架构评估](#9-架构评估)
10. [关键数据流](#10-关键数据流)
11. [可扩展性分析](#11-可扩展性分析)

---

## 1. 项目概要

**EPI Cluster Tool Scheduler** 是半导体外延生长（EPI）集群设备的离散事件模拟调度器。

- **核心目标**：在满足硬性最大驻留时间（Max Dwell Time）约束的前提下，最大化晶圆吞吐量（WPH）
- **特点**：纯 JSON 配置驱动，所有设备参数、工艺时间、AM 任务、生产任务均从配置文件读取
- **最终指标**：33/33 wafer 完成，0 dwell violation，4 腔 OnLoadClean 间隔统一 ~73s

---

## 2. 目录结构

```
epi/
├── conf/                          # 5 个 JSON 配置 + 5 个说明文档
│   ├── device.json                # 硬件拓扑（腔室、机械手、LL 参数）
│   ├── schedule.json              # 工艺 recipe + 调度 + 模拟参数
│   ├── sequence.json              # Wafer 9 步流程定义
│   ├── job.json                   # ControlJob/ProcessJob/wafer 子集
│   ├── am.json                    # Auto Maintenance 任务
│   └── *.md                       # 各配置文件说明
├── backend/                       # Spring Boot 3.2.5
│   └── src/main/java/com/epi/scheduler/
│       ├── EpiSchedulerApplication.java
│       ├── config/
│       │   ├── CorsConfig.java
│       │   └── WebSocketConfig.java
│       ├── controller/
│       │   └── SchedulerController.java   # 18 个 REST 端点
│       ├── engine/
│       │   └── SchedulerEngine.java       # 核心引擎, 1490 行
│       ├── model/
│       │   ├── DeviceConfig.java          # 设备配置 POJO
│       │   ├── ScheduleConfig.java        # 工艺/调度配置 POJO
│       │   ├── SequenceConfig.java        # 流程配置 POJO
│       │   ├── JobConfig.java             # 任务配置 POJO
│       │   ├── AmConfig.java              # AM 配置 POJO
│       │   ├── GanttEntry.java            # 甘特图条目
│       │   └── SimulationSnapshot.java    # 状态快照 DTO
│       └── service/
│           ├── ConfigService.java         # 配置加载与热重载
│           └── SimulationService.java     # 模拟循环 + WebSocket 推送
├── frontend/                      # Vue 3 + Vite
│   └── src/
│       ├── main.js
│       ├── App.vue                       # 根组件（布局 + 数据轮询 + WebSocket）
│       └── components/
│           ├── ControlPanel.vue          # 控制面板（启停/调速/CJ 选择）
│           ├── ToolLayout.vue            # SVG 机台拓扑布局
│           └── GanttChart.vue            # 甘特图（Wafer/腔室双视图）
├── doc/
│   ├── design.md                # 设计文档（面向 AI agent）
│   └── talk.md                  # 对话记录
├── generate_report.py           # HTML 报告生成器, ~1340 行
├── generate_ppt.py              # PPTX 报告生成器, ~447 行
└── generate_images.py           # PNG 图片生成器, ~246 行
```

---

## 3. 核心引擎分析

### 3.1 架构模式

单体离散事件模拟引擎，每秒一个 tick，同步全局状态决策。核心在 `SchedulerEngine.java`（1490 行）。

### 3.2 主循环 `tick()` — 14 个步骤

```
tick():
  1. currentTimeSec++
  2. updateChamberTimers()       — 所有腔室倒计时，到期触发状态转换
  3. checkMaxDwellTimes()        — 驻留违例检查（带去重）
  4. updateRobots()              — 机械手到期 → 执行 onComplete 回调
  5. scheduleATM()               — ATM: FOUP→Aligner→LL 两段传输
  6. scheduleTM1()               — TM1: PT返回 > PC→PT > LL→PC (优先级递减)
  7. scheduleTM2()               — TM2: EPI→PT > PT→EPI
  8. manageBatchLL()             — BLL vent 完成 → 卸载 wafer
  9. prepareBatch()              — 准备新 batch，填充 pendingBatch
  10. triggerOnLoadClean()       — EPI(计数器触发) / PreClean(stagger)
  11. triggerIdlePurge()         — PreClean idle ≥ 180s 触发
  12. healWaferLocations()       — 数据自愈：修复 wafer 位置与腔室状态不一致
  13. captureReplaySnapshot()    — 每 10s 记录轻量回放快照
  14. 完成检测                    — completedWafers >= total → COMPLETED
```

### 3.3 调度优先级

**TM1（LL ↔ PreClean ↔ PT）**：
1. PT 返回 → LL（清空返回路径，防止 PT 槽满卡死 EPI→PT）
2. PreClean done → PT fwd（推进前向流程）
3. LL → PreClean（拉入新 wafer，有准入控制）

**TM2（PT ↔ EPI）**：
1. EPI done → PT ret（EPI 是瓶颈，优先释放）
2. PT fwd → EPI

### 3.4 死锁预防 — `canPullWaferFromLL()`

前向预测的准入控制，防止新 wafer 进入系统后在下游卡死：

```
1. Stagger check: (EPI工艺 + clean) / EPI腔数 ≈ 644s 间隔限制启动频率
2. 简单情况: IDLE + CLEANING 的 EPI 腔 >= demand → 放行
3. 未来预测: 按 remainingTime 排序 PROCESSING EPI 腔，找第 N 个最早完成的
4. 等待窗口: maxWait = LL→PC_robot + PC_process + PC→PT_robot + PT_maxDwell - 40s_safetyMargin
5. 若 epiReadyIn <= maxWait → 放行，否则阻止
```

### 3.5 PT 槽分配策略

- **fwd 方向**（PreClean→EPI）：优先 **buffer 槽**（非 cooling 槽），如 PT1_S1, PT2_S0
- **ret 方向**（EPI→LL）：优先 **cooling 槽**（PT1_S0, PT2_S1）
- **目的**：分离前向和返回流量，减少 PT 争夺

### 3.6 BLL Batch 状态机

```
IDLE → LOADING → PUMPING(126s) → READY → (wafer 逐个进出)
  → 所有 wafer 返回后 → VENTING(168s) → DONE → UNLOADING(5s) → IDLE
```

BLL 是 batch 模式：一批 wafer 泵入后，逐个被 TM1 取出处理，全部返回后才 vent。

### 3.7 腔室状态机

```
ChamberState: IDLE, LOADING, PUMPING, READY, PROCESSING, DONE, VENTING, UNLOADING, COOLING, CLEANING, PURGING

关键转换：
  IDLE → CLEANING  (OnLoadClean / 1X Clean) → IDLE
  IDLE → PURGING   (IdlePurge, PC only)      → IDLE
  IDLE → LOADING   (FOUP→LL)                 → PUMPING → READY
  READY → PROCESSING (wafer 进入)            → DONE → IDLE (wafer 取走)
  READY → VENTING  (batch 完成)              → DONE → UNLOADING → IDLE
  READY → COOLING  (EPI→PT ret, cooling槽)   → COOLING(60s) → READY
```

CLEANING（橙色 #FF5722）与 PURGING（紫色 #9C27B0）在枚举中是不同的值，前端和甘特图可区分。

### 3.8 AM（Auto Maintenance）三种类型

| 类型 | 触发条件 | 目标腔 | 频率 |
|---|---|---|---|
| OnLoadClean(457s EPI / 537s PC) | EPI1: 延迟公式; EPI2+: wafersEnteredPreClean 计数器; PC: stagger | EPI, PreClean | 每 CJ 每腔 1 次 |
| 1X Clean(457s) | EPI 释放后立即触发（有后续 wafer 需求时） | EPI | 每片前 |
| IdlePurge(123s) | IDLE 时间 ≥ 180s，纯空闲驱动 | PreClean | 循环 |

### 3.9 数据自愈 — `healWaferLocations()`

```
for each wafer where location starts with "PT":
  if chamber.waferId != wafer.id:
    if flowStep >= 15:  // 返回 wafer 丢失 → 放入 BLL
    else if flowStep in [8, 13]:  // 前向/返回 wafer 槽位被清空 → 恢复
```

---

## 4. 后端架构分析

### 4.1 技术栈

| 组件 | 选型 | 评价 |
|---|---|---|
| 框架 | Spring Boot 3.2.5 | 稳定，但对于纯模拟场景有过度封装 |
| WebSocket | STOMP over SockJS | 适合实时推送 |
| JSON | Jackson ObjectMapper | 标准选择 |
| 并发 | synchronized 方法 | 简单但无法利用多核 |

### 4.2 文件清单（14 个 Java 文件）

```
EpiSchedulerApplication.java    — 启动类
config/CorsConfig.java           — CORS 配置（允许前端跨域）
config/WebSocketConfig.java      — STOMP /ws 端点配置
controller/SchedulerController.java — 18 个 REST 端点
engine/SchedulerEngine.java     — 核心引擎, ~1490 行
model/DeviceConfig.java         — 设备配置 POJO（含 8 个内部类）
model/ScheduleConfig.java       — 工艺/调度/模拟配置 POJO
model/SequenceConfig.java       — 流程配置 POJO
model/JobConfig.java            — 任务配置 POJO + wafer range 解析
model/AmConfig.java             — AM 配置 POJO
model/GanttEntry.java           — 甘特图条目
model/SimulationSnapshot.java   — 状态快照 DTO
service/ConfigService.java      — 配置加载（5 个 JSON）+ 热重载
service/SimulationService.java  — 模拟循环 + WebSocket 广播
```

### 4.3 模拟循环机制

```java
@Scheduled(fixedRate = 10)  // 每 10ms 执行一次
public void simulationLoop() {
    tickAccumulator += simulationSpeed;
    int ticksToRun = tickAccumulator / 100;
    tickAccumulator %= 100;

    for (int i = 0; i < ticksToRun; i++) {
        if (!engine.tick()) break;
    }
    broadcastState();  // WebSocket 推送
}
```

速度控制逻辑：speed / 100 = 每 10ms 执行的 tick 数。speed=1 时每 1s 执行 1 tick（1x 实时），speed=100 时每 10ms 执行 10 tick（100x 加速）。

### 4.4 API 端点（18 个）

**模拟控制** (5)：`POST /api/simulation/{start,pause,reset,step,speed}`

**状态查询** (5)：`GET /api/simulation/{state,gantt,events,foups,robots}`

**配置** (6)：`GET /api/config/{device,schedule,job,am,sequence}` + `POST /api/config/reload`

**报告与回放** (2)：`POST /api/report/generate` + `GET /api/simulation/replay`

---

## 5. 前端架构分析

### 5.1 技术栈

| 组件 | 选型 |
|---|---|
| 框架 | Vue 3.4.21 (Composition API + `<script setup>`) |
| 构建 | Vite 5.2 |
| 图形 | 原生 SVG（无第三方图表库） |
| 通信 | SockJS + STOMP-client |

### 5.2 组件划分（3 个核心组件）

**ControlPanel.vue** (~125 行)
- 重置/启动/暂停/单步/甘特图按钮
- CJ 选择下拉框
- 速度滑块 (1x–100x)

**ToolLayout.vue** (~268 行)
- SVG viewBox="0 0 1140 520" 完整机台拓扑
- 按真实设备位置排列：FOUP → Aligner → ATM Robot → LL → TM1 → PreClean → PT → TM2 → EPI
- 机械臂动画：根据 sourceChamber/targetChamber 计算旋转角度
- FOUP slot 网格（5×5），3 种颜色状态（FILLED/EMPTY/DONE）
- 腔室颜色映射：IDLE=灰, PROCESSING=绿, DONE=橙, CLEANING=橙红, PURGING=紫, COOLING hidden
- 进度条：在腔室底部显示 remainingTime/totalTime 比例

**GanttChart.vue** (~268 行)
- 双视图：Wafer 视图（每行一个 wafer）和腔室视图（每行一个腔室）
- 腔室视图带利用率百分比 badge
- 缩放 0.1x–5x
- 颜色映射：LOADLOCK=蓝, PRECLEAN=橙, PASSTHROUGH=黄, EPI=绿, CLEAN=橙红, PURGE=紫
- 自动跟随当前时间滚动
- 传输时间从条形长度中扣除（`getXfer()` 函数）

### 5.3 数据流

```
App.vue:
  - 每 500ms 轮询 GET /api/simulation/state → 更新 state, foups, robots
  - WebSocket /topic/state 推送 → 实时更新
  - 检测 status === 'COMPLETED' → 自动调用 /api/report/generate
```

---

## 6. 配置系统分析

### 6.1 配置文件一览

| 文件 | 大小 | 关键内容 |
|---|---|---|
| device.json | 88 行 | 3 LP, 2 LL, 2 PreClean, 4 EPI, 2 PT(各2槽), 3 Robots |
| schedule.json | 50 行 | 4 个 recipe, stagger, 安全裕度, LL 时序, cooling, 模拟速度 |
| sequence.json | 30 行 | 9 步流程, 机械手操作映射 |
| job.json | 30 行 | CJ1: serial mode, PJ1(LP1 1-25) + PJ2(LP2 3-10) |
| am.json | 49 行 | 3 个 clean 任务(457s/537s) + 1 个 purge(123s, idle>180s) |

### 6.2 可配置项总览

| 类别 | 配置项 | 文件 |
|---|---|---|
| 设备拓扑 | 腔室数量/类型、机械手操作时间 | device.json |
| 工艺 | 处理时间、随机变化范围、最大驻留 | schedule.json |
| 调度 | 策略、最大同时 wafer、启动间隔、安全裕度 | schedule.json |
| 模拟 | 速度、总 wafer 数、时间步长 | schedule.json |
| 流程 | wafer 流转步骤、每步的 recipe 和 robot | sequence.json |
| 任务 | CJ/PJ 模式、LP 分配、wafer 范围 | job.json |
| AM | OnLoadClean、1X Clean、IdlePurge 的时间和阈值 | am.json |
| LL 时序 | Pump/Vent/Load/Unload 时间 | schedule.json |
| Cooling | 冷却时间、冷却槽分配 | schedule.json / device.json |

### 6.3 配置加载机制

`ConfigService` 通过 `@Value` 注解注入配置文件路径（默认 `../conf/*.json`），`@PostConstruct` 时用 Jackson 反序列化。支持 `POST /api/config/reload` 热重载（但会丢失当前模拟状态）。

**注意**：运行时 cwd 必须是 `backend/`，因为路径是 `../conf/` 相对路径。

---

## 7. 报告系统分析

### 7.1 HTML 报告 (generate_report.py, ~1340 行)

通过 HTTP API 拉取数据，生成自包含 HTML。章节包括：
- 核心指标（完成数、WPH、总时间、平均周期）
- 工艺参数（所有 recipe、LL/PT/Cooling 时序、机械手操作时间）
- 腔室使用统计（EPI 使用 active window 计算利用率）
- 约束违反（从甘特图 dwell 时间计算）
- Wafer×Station 矩阵（P=处理/D=驻留，带颜色高亮）
- 甘特图（完整时序，带腔室利用率百分比）
- Wafer History（下拉选择 + 分步时间线表格）
- SVG 回放器（与 ToolLayout.vue 布局一致的动画，支持播放/暂停/步进/调速/进度条）

### 7.2 PPTX 报告 (generate_ppt.py, ~447 行)

面向人类阅读的 15 页深色主题幻灯片，每页有章节标题条和要点框。

### 7.3 调用方式

前端检测 `status === 'COMPLETED'` → `POST /api/report/generate` → Java 通过 `ProcessBuilder("python3", "../generate_report.py")` 调用 Python 脚本 → 脚本通过 HTTP 拉取 API 数据 → 生成 HTML 文件。

---

## 8. 发现的问题

### 8.1 [Bug] `checkMaxDwellTimes()` 中 PreClean 的 dwell 使用了 EPI 的 MaxDwell

**文件**：`SchedulerEngine.java:1342-1343`

```java
case "PRECLEAN":
case "EPI":
    ...
    recipeKey = c.type;       // "PRECLEAN"
    recipeKey = "EPI";       // BUG: 死赋值覆盖，PreClean 实际用了 EPI 的 MaxDwell(100s)
```

**影响**：PreClean 的 MaxDwell 应该是 120s，但实际比较用的是 100s。由于 PreClean 工艺仅 280s（EPI 2120s），dwell 通常不会达到阈值，**实际影响较小但逻辑不正确**。

### 8.2 报告生成耦合方式脆弱

`SchedulerController.java:158` 通过 `ProcessBuilder("python3", "../generate_report.py")` 调用 Python。要求：
- 系统安装 python3 且在 PATH 中
- 安装 `python-pptx` 等第三方库
- cwd 必须是 `backend/`
- 后端运行期间 Python 脚本必须可访问网络（HTTP 拉取 API）

### 8.3 无测试

整个项目没有任何单元测试或集成测试。核心引擎 1490 行纯业务逻辑完全依赖手动验证。

### 8.4 无版本控制

项目根目录无 `.git`，未纳入 Git 管理。

### 8.5 配置冗余

`device.json` 定义了 LP3（25 slots）但 `job.json` 中只使用了 LP1 和 LP2。

### 8.6 并发模型简单

`SchedulerEngine` 全部 public 方法使用 `synchronized`，引擎自身不支持并发模拟。

### 8.7 无持久化

模拟状态仅存在于内存，重启丢失所有数据。报告生成依赖模拟完成时后端仍在运行。

---

## 9. 架构评估

### 9.1 优点

| 方面 | 评价 |
|---|---|
| **配置驱动** | 所有参数从 JSON 读取，换 recipe/设备仅需修改配置，无硬编码 |
| **引擎独立性** | SchedulerEngine 纯 Java，无 Spring 依赖，可独立测试 |
| **前后端分离** | REST + WebSocket，前端独立开发服务器 |
| **回放设计** | 10s 轻量快照嵌入 HTML，纯前端 SVG 动画，无需服务器 |
| **AM 区分** | CLEANING vs PURGING 状态/颜色分离，语义清晰 |
| **死锁预防** | 前向预测 + 安全裕度，0 dwell violation 验证有效 |
| **文档** | design.md 面向 AI agent 结构化，talk.md 记录协商过程 |

### 9.2 待改进

| 方面 | 说明 |
|---|---|
| **无测试** | 引擎纯逻辑最需要测试覆盖 |
| **无版本控制** | 未纳入 Git |
| **硬编码覆盖** | `recipeKey = "EPI"` 死赋值 bug |
| **耦合** | Python 报告生成依赖 HTTP 回访后端 |
| **并发** | 全量 synchronized，无并行能力 |
| **持久化** | 无状态保存/恢复 |
| **输入校验** | JSON 格式错误直接抛异常，无友好提示 |

---

## 10. 关键数据流

### 10.1 模拟启动与运行

```
用户点击"启动" → POST /api/simulation/start {cjId: "CJ1"}
  → SimulationService.startJob("CJ1")
    → new SchedulerEngine(deviceConfig, scheduleConfig, cj)
      → initialize(cj):
        - chambers: LL1, LL2, PreClean1-2, PT1_S0-1, PT2_S0-1, EPI1-4, ALIGNER
        - robots: ATM1, Robot1, Robot2
        - wafers: CJ1.PJ1 LP1[1-25] + CJ1.PJ2 LP2[3-10] = 33 片
        - staggerInterval: (2120+457)/4 ≈ 644s (auto-calc)
    → engine.start()
      → @Scheduled(fixedRate=10ms) simulationLoop()
        → 每 10ms: tickAccumulator += speed
        → 执行 N = tickAccumulator/100 个 tick()
        → broadcastState() → WebSocket /topic/state
```

### 10.2 报告生成

```
模拟完成 (status=COMPLETED)
  → 前端自动 POST /api/report/generate
    → ProcessBuilder("python3", "../generate_report.py")
      → Python 脚本通过 HTTP 拉取:
        - GET /api/simulation/state
        - GET /api/simulation/gantt
        - GET /api/simulation/replay
        - GET /api/config/device, schedule, am
      → 生成 simulation_report.html (~16MB, 含 2600+ 回放快照)
```

### 10.3 前端实时更新

```
App.vue:
  轮询 (500ms): GET /api/simulation/state → state, foups, robots
               GET /api/simulation/robots → 机械手位置
  WebSocket:    /topic/state → SimulationSnapshot (实时推送)

ToolLayout.vue: 接收 state, foups, robots → 渲染 SVG 布局 + 机械臂动画
GanttChart.vue: 接收 gantt data + currentTime → 渲染甘特条 + 自动滚动
```

---

## 11. 可扩展性分析

### 11.1 当前限制

| 限制 | 位置 | 说明 |
|---|---|---|
| 单臂硬编码 | `Robot.armWaferId` 是单个 String | 双臂需改为 List |
| 单 CJ | `activeCJ` 是单个变量 | 多 CJ 并行需重构 LL 分配 |
| 无 swap | 机械手 onComplete 只处理一片 | 多指/swap 需重新设计传输逻辑 |
| EpiStagger 仅 4 腔 | `(EPI+clean)/4` 硬编码 | 应动态读取 EPI 数量 |
| LP3 未使用 | device.json | 仅 LP1/LP2 用于 CJ1 |

### 11.2 已支持的扩展点

- `POST /api/config/reload` — 热重载配置
- `schedule.json` 中的 `policy` 字段 — 调度策略可切换
- `job.json` 支持多 CJ/PJ/serial-parallel — 任务配置灵活
- `am.json` 中的 `appliesTo` 数组 — 可扩展到更多腔室类型

### 11.3 文档中提到的遗留项

1. 双臂/多指机器人支持
2. 多 CJ 并行调度 + 不同 recipe 混跑
3. ML 优化调度策略（强化学习选择最优 wafer 释放时机）
4. Web UI 实时修改配置文件
5. 前端回放器与运行界面合并为统一组件
