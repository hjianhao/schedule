# am.json — Auto Maintenance 自动维护配置

定义腔室的自动维护（AM）任务：清洁、吹扫等。调度引擎根据任务类型在适当的时机触发。

---

## AM 任务类型

| 类型 | 触发时机 | 适用腔室 | 频率 |
|------|---------|---------|------|
| `ON_LOAD_CLEAN` | CJ 启动时，每腔执行一次 | 任意（通常 EPI、PRECLEAN） | 每 CJ 1 次/腔 |
| `PRE_PROCESS` | 每片 wafer 工艺前 | EPI | 每片前 1 次 |
| `IDLE_PURGE` | 腔室空闲超过阈值 | PRECLEAN | 空闲阈值驱动，循环 |

---

## 顶层字段

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `name` | string | 否 | 维护计划名称，仅文档用途 |
| `tasks` | array | 是 | AM 任务列表 |

---

## tasks[] — 任务定义

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `id` | string | 是 | 任务唯一标识，用于日志和事件 |
| `name` | string | 否 | 任务名称 |
| `description` | string | 否 | 任务描述 |
| `type` | string | 是 | 任务类型：`ON_LOAD_CLEAN`、`PRE_PROCESS`、`IDLE_PURGE` |
| `cleanTimeSec` | number | 是 | 清洁/吹扫执行时间（秒） |
| `gapTimeSec` | number | 否 | **仅 `PRE_PROCESS`**：清洁完成到 wafer 放入的目标间隔（秒）。`0` = 零间隔（清洁完成立即放入）。`>0` = 报告中将间隔超出此值标记为违反 |
| `idleThresholdSec` | number | 否 | **仅 `IDLE_PURGE`**：腔室空闲多久后触发吹扫（秒） |
| `appliesTo` | array | 是 | 适用的腔室类型列表 |

### appliesTo[] — 适用腔室

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `chamberType` | string | 是 | 腔室类型，必须与 device.json 中 `chambers[].type` 一致（如 `EPI`、`PRECLEAN`） |

调度器启动时从 `appliesTo` 中动态收集腔室类型，**不再在代码中硬编码 `EPI`/`PRECLEAN` 列表**。

---

## ON_LOAD_CLEAN — CJ 级清洁

每个 CJ 开始时，每个适用腔室执行一次。用于腔室初始清洁。

### 触发逻辑

**EPI 腔（首个腔）**：
```
minStartTime = arrivalEstimate - onloadTime + offset
arrivalEstimate = PC_OnLoad + IdlePurge_Threshold + IdlePurge_Duration + PC_Process + Transport
```
- 延迟启动，使清洁完成时间对齐首片 wafer 到达
- offset 默认为 120s（`ONLOAD_CLEAN_EPI1_OFFSET_SEC` 常量）

**EPI 腔（后续腔）**：
- 由 `wafersEnteredPreClean` 计数器触发
- 第 N 个 EPI 腔在第 N 片 wafer 进入 PreClean 时启动 OnLoadClean

**非 EPI 腔（如 PRECLEAN）**：
- 使用 stagger 间隔依次启动：`staggerInterval = (processTime + onloadTime) / chamberCount`

### 示例

```json
{
  "id": "ON_LOAD_CLEAN_EPI",
  "name": "EPI OnLoad Clean",
  "description": "Pre-CJ EPI chamber clean - executed once before first wafer per CJ",
  "type": "ON_LOAD_CLEAN",
  "cleanTimeSec": 457,
  "appliesTo": [
    { "chamberType": "EPI" }
  ]
}
```

---

## PRE_PROCESS — 片级清洁（1X Clean）

每片 wafer 进入 EPI 腔之前执行一次。当 EPI 完成上一片 wafer 并被 TM2 取走后，立即启动清洁。

### 停止条件

如果调度器检测到没有更多 wafer 需要 EPI 处理，则跳过清洁，腔室直接回到 IDLE。

### 示例

```json
{
  "id": "1X_CLEAN_EPI",
  "name": "EPI 1X Clean",
  "description": "Pre-wafer EPI chamber clean - executed before each wafer",
  "type": "PRE_PROCESS",
  "cleanTimeSec": 457,
  "gapTimeSec": 1,
  "appliesTo": [
    { "chamberType": "EPI" }
  ]
}
```

`gapTimeSec: 1` 表示目标间隔 ≤ 1s（零间隔）。报告中将 gap > 1s 的情况列为违反。

---

## IDLE_PURGE — 空闲吹扫

仅由空闲时间触发，与是否处理过 wafer 无关。当腔室状态为 IDLE 且空闲时长 ≥ `idleThresholdSec` 时启动。

### 循环行为

吹扫完成后重置 idle 计时器→再空闲 `idleThresholdSec` 秒→再次触发吹扫→循环。

### 示例

```json
{
  "id": "IDLE_PURGE_PC",
  "name": "PreClean IdlePurge",
  "description": "Idle purge when PreClean chamber idle longer than threshold",
  "type": "IDLE_PURGE",
  "cleanTimeSec": 123,
  "idleThresholdSec": 180,
  "appliesTo": [
    { "chamberType": "PRECLEAN" }
  ]
}
```

PreClean 腔空闲 180s → 执行 123s 吹扫 → 空闲 → 再 180s → 再吹扫。

---

## 注意事项

1. **腔室类型必须在 device.json 中存在**：`appliesTo[].chamberType` 的值必须与 `device.json` 中 `chambers[].type` 一致
2. **ON_LOAD_CLEAN + PRE_PROCESS 可同时配置**：EPI 腔通常同时有 OnLoadClean（CJ 级）和 1X Clean（片级）
3. **IDLE_PURGE 仅对 PRECLEAN 有意义**：EPI 腔持续有 wafer 进入，不会长时间 IDLE
4. **修改任务类型或新增腔室类型时**：调度器会自动从 `appliesTo` 中读取，无需修改 Java 代码

---

## 完整示例

```json
{
  "name": "EPI Maintenance Tasks",
  "tasks": [
    {
      "id": "ON_LOAD_CLEAN_EPI",
      "name": "EPI OnLoad Clean",
      "description": "Pre-CJ EPI chamber clean - executed once before first wafer per CJ",
      "type": "ON_LOAD_CLEAN",
      "cleanTimeSec": 457,
      "gapTimeSec": 1,
      "appliesTo": [
        { "chamberType": "EPI" }
      ]
    },
    {
      "id": "1X_CLEAN_EPI",
      "name": "EPI 1X Clean",
      "description": "Pre-wafer EPI chamber clean - executed before each wafer",
      "type": "PRE_PROCESS",
      "cleanTimeSec": 457,
      "gapTimeSec": 1,
      "appliesTo": [
        { "chamberType": "EPI" }
      ]
    },
    {
      "id": "ON_LOAD_CLEAN_PC",
      "name": "PreClean OnLoad Clean",
      "description": "Pre-CJ PreClean chamber clean - executed once before first wafer per CJ",
      "type": "ON_LOAD_CLEAN",
      "cleanTimeSec": 537,
      "gapTimeSec": 1,
      "appliesTo": [
        { "chamberType": "PRECLEAN" }
      ]
    },
    {
      "id": "IDLE_PURGE_PC",
      "name": "PreClean IdlePurge",
      "description": "Idle purge when PreClean chamber idle longer than threshold",
      "type": "IDLE_PURGE",
      "cleanTimeSec": 123,
      "idleThresholdSec": 180,
      "appliesTo": [
        { "chamberType": "PRECLEAN" }
      ]
    }
  ]
}
```
