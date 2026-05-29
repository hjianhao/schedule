# 对话记录 — Cluster Tool 调度模拟器

> 从项目初始化到最终交付的完整对话记录。  
> 记录用户（您）提出的需求和我（AI）执行的任务，便于后续回顾和接力开发。

---

## 第一阶段：基础框架搭建（早期会话）

### 需求
构建一个半导体 Cluster Tool 晶圆调度模拟器，满足：
- 单臂单指机器人（TM1, TM2, ATM），无原子 Swap
- 刚性最大驻留约束：PreClean 120s, EPI 100s, PT 300s
- CoolingStation 60s 冷却逻辑
- Job 配置：CJ → PJ → wafer 子集，支持 serial/parallel 模式
- AM 集成：OnLoadClean（每腔/CJ）、1X Clean（每片前）、IdlePurge（空闲触发）
- 纯 JSON 配置驱动，无硬编码

### 任务与产出

| 任务 | 产出 |
|------|------|
| 搭建 Spring Boot + Vue 3 前后端项目 | 项目骨架 |
| 实现 5 个 JSON 配置文件解析 | `ConfigService.java` + 配置 POJO 模型 |
| 实现离散事件模拟引擎 | `SchedulerEngine.java`（核心调度，~1400 行） |
| 实现在线贪心调度 | TM1/TM2 优先级、死锁预防 `canPullWaferFromLL`、stagger |
| 实现 Vue 3 前端 | `ToolLayout.vue`（SVG 布局）、`GanttChart.vue`、`ControlPanel.vue` |
| 实现 WebSocket 实时推送 | STOMP `/topic/state` |
| 实现 AM 逻辑 | OnLoadClean、1X Clean、IdlePurge |
| 实现 BLL Batch 管理 | Pump→Ready→Vent→Done→Unload 循环 |
| 实现 HTML 报告生成 | `generate_report.py`（统计、矩阵、甘特图、Wafer History） |
| 实现 PPT 报告生成 | `generate_ppt.py`（12 页） |
| 实现数据自愈 | `healWaferLocations()` |

### 早期 Bug 修复（本会话之前已解决）

| Bug | 根因 | 修复 |
|-----|------|------|
| `lastUsedTime` +dur 重复计算 | lambda 捕获 `currentTimeSec` 后又 +dur | 所有 6 处 lambda 中移除 `+dur` |
| gantt `addGanttEntry`/`closeGanttEntry` +dur 重复 | 同上 | 统一使用 `currentTimeSec` |
| LL2 过早 vent | `checkBatchLLComplete` 用全局 wafer 计数 | 改回 per-LL 计数 |
| `triggerScheduledCleans` 未调用 | 代码写了但 tick() 从未调用 | 改为 EPI 释放时立即启动 1X Clean |

---

## 第二阶段：AM 逻辑修正 + 状态区分（本会话）

### 第 1-3 轮：确认 PreClean AM 逻辑

**您**："PreClean腔clean应该只执行一次啊，就是job开始的Onloadclean。clean和purge是两个操作。"

**我**：确认当前代码逻辑——PreClean 只设了 OnLoadClean（537s，每 CJ 一次）和 IdlePurge（123s，idle >180s 触发）。当前逻辑正确。

**您**："运行还是发现PreClean腔还在不停的显示CLEANING"

**我**：分析事件日志发现是 IdlePurge 在正常循环（purge 123s + idle 180s = 303s 周期），**这是正确行为**——IdlePurge 应仅由 idle 时间触发。

---

### 第 4 轮：新增 PURGING 状态区分 CLEAN vs PURGE

**您**："怎么界面显示CLEANING，应该用PURGING区分"

**我**：
- 新增 `PURGING` 枚举值到 `ChamberState`
- `triggerIdlePurge()` 设置 `ChamberState.PURGING` 而非 `CLEANING`
- `handleChamberTimerDone()` 处理 `PURGING` 状态（同 CLEANING，转 IDLE）
- `checkMaxDwellTimes()` 跳过 `PURGING` 状态
- 前端 `ToolLayout.vue`：CLEANING=#FF5722(橙色)/清洗, PURGING=#9C27B0(紫色)/吹扫

---

### 第 5 轮：甘特图区分 CLEAN vs PURGE

**您**："甘特图CLEAN和PURGE没有区分"

**我**：
- `triggerIdlePurge()` 甘特条目 type 从 "CLEAN" 改为 "PURGE"，颜色 `#9C27B0`
- 前端 `GanttChart.vue` colorMap 添加 `'PURGE': '#AB47BC'`

**您**："idle purge只和idle的时间有关，和有没有处理wafer无关"

**我**：移除之前错误添加的 `lastPurgeCompleteTime` 二级守卫和 `purgedChambers` 守卫，IdlePurge 改为**纯 idle 时间驱动**——仅检查 `c.state == IDLE && idleTime >= threshold`。

---

## 第三阶段：OnLoadClean 时序优化（本会话）

### 第 6 轮：EPI OnLoadClean Gap 太大

**您**："每个EPI腔的OnLoadClean做得太早了，和工艺衔接不上，导致gap太大"

**我**：发现**根因**——`lastOnloadCleanStart` 是单个变量被 EPI 和 PreClean 共享。PreClean 的 OnLoadClean 启动时会覆盖计时器，导致 EPI2/3/4 的 stagger 永远不触发，**这 3 个腔从未做 OnLoadClean**。

修复：拆分为 `lastOnloadCleanStartPerType` Map。

---

### 第 7 轮：EPI2/3/4 的 Gap 依然大

**您**："EPI1的OnLoadClean和Place执行的衔接的间隔变短了，但是EPI2,EPI3,EPI4没有"

**我**：分析发现 EPI stagger 公式 `(process+clean)/count = 644s` 去 `-100` 后 wait=544s，但 wafer stagger 是 530s。差 100s 随腔累积导致间隙越来越大。但即使修正，EPI3/4 依然差——因为 wafer 到达延迟（管道拥堵），时钟 stagger 不匹配实际节奏。

**您**："直觉来看，只需要把EPI3和EPI4的OnLoadClean推迟一些即可。gap的约束是强约束，Clean执行的早晚不是约束。如果做不到，说具体原因。"

**我**：能。改为 **wafer 计数器触发**方案。

---

### 第 8 轮：计数器触发方案

**您**："可以"

**我**：
- 新增 `wafersEnteredPreClean` 计数器，在 `tryTM1LLToPreClean` 的 `onComplete` 中递增
- EPI1：用延迟公式 `minStartTime = arrivalEstimate - onloadTime + 120`
- EPI2：`wafersEnteredPreClean >= 2` 时启动
- EPI3：`wafersEnteredPreClean >= 3` 时启动
- EPI4：`wafersEnteredPreClean >= 4` 时启动

**效果**：全 4 腔 OnLoadClean→首片 wafer 间隔统一 ~73s，dwell violation 从 7→**0**。

---

## 第四阶段：报告回放系统（本会话）

### 第 9 轮：机台动画回放

**您**："能否在结果报告的html把运行界面的那个机台动画放进去，通过一个播放键可以回放整个过程"

**我**：
- 后端：`captureReplaySnapshot()` 每 100s 采集轻量快照
- API：`GET /api/simulation/replay`
- Python：拉取 replay 数据嵌入 `const REPLAY = [...]`
- HTML：Canvas 播放器（播放/暂停/步进/调速/进度条）

---

### 第 10 轮：布局对不齐运行界面

**您**："看上去和运行界面不一样啊，能做得和运行界面一样吗？播放速度太小了，最大100x。另外点击播放键并没有一直回放"

**我**：重写为 HTML/CSS div 布局（后改为 SVG），修复：
- 布局对齐 `ToolLayout.vue`
- 播放使用 `requestAnimationFrame`（之前静态）
- 速度上限 10x → **100x**
- 步进改为 ±1 快照（之前 ±10 模拟秒）

---

### 第 11-12 轮：机械手不可见

**您**："怎么移植不完全？机械手的动作没有回放" → "刷新了，还是没看见机械手"

**我**：多次迭代修复：
- 采样间隔 100s→**10s**（机械手动作 11-17s，100s 全漏）
- ATM 机器人 ID 匹配修复（引擎 `ATM1` → HTML `rp-rob-ATM1`）
- CSS 添加默认背景色 `background: #37474F`
- 机器人元素改用嵌套子元素 + `querySelector`（而非 separate by-ID）
- **最终方案**：SVG viewBox="0 0 1140 520" 完全对齐 ToolLayout.vue，含旋转机械臂动画

---

## 第五阶段：文档（本会话）

### 第 13 轮：设计文档

**您**："帮忙把这个项目做到现在所有的设计写到一个设计文档中，包括需求列表，架构，技术栈，哪些可配置项，约束，关键算法等。按照AI的理解优化，以便后续使用不同AI agent和AI模型能更好维护这个项目"

**我**：生成 `doc/design.md`（15 章，面向 AI agent 优化）：
- 需求列表（表格化 F/NFR）
- ASCII 硬件拓扑图
- 配置 JSON schema
- 算法伪代码（tick、死锁预防、stagger、AM）
- 腔室状态机
- API 端点表
- 设计决策 + 理由表
- Bug 修复历史表

---

### 第 14 轮：PPTX 报告

**您**："同样的内容再生成一个pptx，主要面向人阅读，符合人思维的分章节，前后组织顺序，每页胶片要有总结要点突出，当前结果的html文件嵌入一页胶片中"

**我**：重写 `generate_ppt.py`（15 页，深色主题）：
- 章节标题条 + ⭐ 高亮要点框
- 第 12 页专页介绍 HTML 报告内容和打开方式
- 数据表格、对比表、ASCII 架构图

---

### 第 15 轮：对话记录

**您**："帮我和你协商的主要信息做个记录（我提出的要求和你的任务）输出到doc/talk.md"
→ "这个已经是项目后期的了，没有动态开始的记录吗？"

**我**：合并早期会话（state-progress 中记录的搭建阶段）和本会话的完整记录，生成本文档。

---

## 关键需求与用户偏好（汇总）

### 调度逻辑
1. PreClean 腔仅在 Job 开始时做一次 OnLoadClean，后续空闲时做 IdlePurge（两个不同操作）
2. IdlePurge **纯空闲时间驱动**，与是否处理过 wafer 无关
3. EPI OnLoadClean 要紧贴首片 wafer 到达，gap ≤ 100s
4. 所有 EPI 腔的 OnLoadClean 间隔应**均匀**，不能只优化 EPI1

### 界面与报告
5. 前端/甘特图 **区分 CLEANING 和 PURGING** 状态（不同颜色+文字）
6. HTML 报告嵌入和运行界面一致的 **SVG 机台动画回放**
7. 回放器：播放/暂停/调速(≤100x)/步进/进度条拖拽
8. 设计文档面向 AI agent，PPTX 面向人类

---

## 最终成果

| 产出 | 文件 | 说明 |
|------|------|------|
| 核心引擎 | `backend/.../SchedulerEngine.java` (~1490 行) | 完整调度引擎 |
| 前端界面 | `frontend/src/components/` | Vue 3 SVG 布局 + 甘特图 + 控制面板 |
| 配置文件 | `conf/` (5 JSON + 5 MD) | 纯配置驱动 |
| HTML 报告 | `simulation_report.html` (~16MB) | 自包含，含 SVG 回放 (2617 快照) |
| PPTX 报告 | `EPI_Scheduler_Report.pptx` | 15 页深色主题 |
| 设计文档 | `doc/design.md` | 15 章，面向 AI agent |
| 对话记录 | `doc/talk.md` | 本文档 |

### 最终性能指标
- ✅ 33/33 wafer 完成，0 dwell violation
- ✅ 4 EPI 腔 OnLoadClean 间隔统一 ~73s
- ✅ WPH = f"{done}/{total} 片"

---

## 重要技术决策（协商达成）

| 决策 | 用户意见 | 最终方案 |
|------|---------|---------|
| IdlePurge 触发条件 | "只和idle时间有关，和有没有处理wafer无关" | 纯 idle 阈值驱动 |
| CLEANING vs PURGING | "应该用不同状态区分" | 新增 `PURGING` 枚举值 |
| Gantt CLEAN vs PURGE | "甘特图没有区分" | 不同 type + 不同颜色 |
| EPI OnLoadClean 触发 | "EPI3/4推迟一些" | EPI1 延迟公式 + EPI2/3/4 计数器触发 |
| 回放布局 | "和运行界面一样" | SVG viewBox 完全对齐 |
| 文档目标读者 | "面向AI" vs "面向人" | design.md→AI, PPTX→人 |
| OnLoadClean stagger | "staggerInterval - 100 会造成累积偏差" | 去掉 -100，对齐 wafer stagger |

---

## 全部 Bug 修复（两阶段合并）

| # | Bug | 根因 | 修复阶段 |
|---|-----|------|---------|
| 1 | `lastUsedTime` +dur 重复 | lambda 中 currentTimeSec 后又 +dur | 早期 |
| 2 | gantt +dur 重复 | `addGanttEntry`/`closeGanttEntry` 同样 +dur | 早期 |
| 3 | LL2 过早 vent | `checkBatchLLComplete` 用全局 wafer 计数 | 早期 |
| 4 | `triggerScheduledCleans` 未调用 | tick() 中从未调用 | 早期 |
| 5 | EPI2/3/4 未做 OnLoadClean | `lastOnloadCleanStart` 被 EPI/PC 共享 | 本会话 |
| 6 | PreClean 事件标签 "1X Clean" 误导 | `handleChamberTimerDone` 硬编码 | 本会话 |
| 7 | EPI3/4 OnLoadClean 间隔过大 | 固定时钟 stagger 不匹配管道节奏 | 本会话 |
| 8 | 回放机械手不可见 | 采样 100s 太长 + ID 不匹配 + CSS 缺陷 | 本会话 |
| 9 | 回放播放键不工作 | requestAnimationFrame 未正确初始化 | 本会话 |

---

## 遗留/可扩展项

1. 双臂/多指机器人支持（当前单臂单指）
2. 多 CJ 并行调度 + 不同 recipe 混跑
3. ML 优化调度策略（强化学习选择最优 wafer 释放时机）
4. Web UI 实时修改配置文件
5. 前端回放器与运行界面合并为统一组件
