# schedule.json — 工艺参数与调度配置

定义各腔室的工艺 recipe、调度策略参数、时序参数、模拟参数。

---

# schedule.json — 工艺参数与调度配置

定义各腔室的工艺 recipe、调度策略参数、时序参数、模拟参数。

---

## recipes — 工艺 Recipe

每个 recipe 以腔室类型为键名。调度器通过 `type` 字段匹配腔室。

### 公共字段

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `avgProcessTimeSec` | int | 是 | 平均工艺时间（秒）。PT/LL 类型设为 `0` |
| `processTimeVariationSec` | int | 是 | 工艺时间随机波动范围（±值），实际时间 = avg ± random(-var, +var) |
| `maxDwellTimeSec` | int | 是 | **最大驻留时间（秒）— 硬约束**。wafer 工艺完成后在被机械手取走前的最大允许等待时间 |

### PRECLEAN

```json
{
  "avgProcessTimeSec": 280,
  "processTimeVariationSec": 10,
  "maxDwellTimeSec": 120
}
```

- 实际工艺时间：280 ± 10s（即 270s ~ 290s）
- 最大驻留 120s：PreClean 完成 → TM1 取走必须在 120s 内

### EPI

```json
{
  "avgProcessTimeSec": 2120,
  "processTimeVariationSec": 30,
  "maxDwellTimeSec": 100
}
```

- 实际工艺时间：2120 ± 30s（即 2090s ~ 2150s）
- 最大驻留 100s：EPI 完成 → TM2 取走必须在 100s 内
- **EPI 是系统瓶颈**，stagger 间隔 = `(EPI_process + EPI_1X_Clean_time) / EPI_chamber_count`

### PASSTHROUGH

```json
{
  "avgProcessTimeSec": 0,
  "processTimeVariationSec": 0,
  "maxDwellTimeSec": 300
}
```

- PT 无工艺时间，wafer 在此等待下游取走
- 最大驻留 300s：wafer 在 PT 内（fwd 或 ret 方向）的等待上限
- CoolingStation 冷却（60s）不计入住留检查

### LOADLOCK

```json
{
  "avgProcessTimeSec": 0,
  "processTimeVariationSec": 0,
  "maxDwellTimeSec": 300
}
```

- LL 无工艺时间
- 最大驻留 300s：wafer 返回 LL 后等待 batch vent 的上限

---

## scheduling — 调度策略

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `policy` | string | 是 | `PRIORITY` | 调度策略。当前仅 `PRIORITY` 生效（TM1: PT返回 > PC→PT > LL→PC；TM2: EPI→PT > PT→EPI） |
| `targetWPH` | int | 否 | — | 目标 WPH，仅用于显示参考 |
| `maxWafersInSystem` | int | 否 | — | 系统内同时存在的最大 wafer 数。**当前调度逻辑中未强制使用** |
| `waferStartIntervalSec` | int | 是 | — | wafer 启动间隔（秒）。`0` 表示自动计算：`EPI_avgProcessTime / EPI腔数` |
| `dwellSafetyMarginSec` | int | 是 | — | **驻留安全裕度（秒）**。在死锁预防的前瞻计算中使用，确保即使有传输延迟也不会超标 |

**waferStartIntervalSec 自动计算公式**：

当 `waferStartIntervalSec <= 0` 时：
```
interval = EPI_avgProcessTimeSec / EPI_chamber_count
```
当前 sige-epi 场景：(2120 + 457) / 4 ≈ 644s（考虑 1X Clean 后的完整 EPI 周期）。

示例：
```json
{
  "policy": "PRIORITY",
  "targetWPH": 10,
  "maxWafersInSystem": 12,
  "waferStartIntervalSec": 0,
  "dwellSafetyMarginSec": 10
}
```

---

## timing — 时序参数

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `loadlockPumpTimeSec` | int | 是 | LoadLock 抽真空时间（秒） |
| `loadlockVentTimeSec` | int | 是 | LoadLock 充气回压时间（秒） |
| `loadlockLoadTimeSec` | int | 是 | LoadLock 单次装载时间（秒） |
| `loadlockUnloadTimeSec` | int | 是 | LoadLock 单次卸载时间（秒） |
| `passthroughTransferTimeSec` | int | 是 | PT 传递时间（秒），当前调度逻辑中未单独使用 |
| `coolingStationCoolTimeSec` | int | 是 | **CoolingStation 冷却时间（秒）**。EPI 返回 wafer 在冷却槽的强制冷却时长 |

示例：
```json
{
  "loadlockPumpTimeSec": 126,
  "loadlockVentTimeSec": 168,
  "loadlockLoadTimeSec": 5,
  "loadlockUnloadTimeSec": 5,
  "passthroughTransferTimeSec": 3,
  "coolingStationCoolTimeSec": 60
}
```

---

## simulation — 模拟参数

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `speed` | int | 是 | `15` | 模拟速度倍率（1x ~ 100x）。speed=15 表示每 10ms 执行 0.15 tick |
| `totalWafers` | int | 是 | `25` | 总共需处理的 wafer 数。仅当未使用 job.json 时生效 |
| `timeStepMs` | int | 是 | `1000` | 每次 tick 对应的毫秒数。`1000` = 1 模拟秒 / tick |

示例：
```json
{
  "speed": 15,
  "totalWafers": 25,
  "timeStepMs": 1000
}
```

---

## 额外字段（遗留/保留）

以下字段存在于配置文件中，当前调度引擎未直接使用，保留以兼容未来扩展：

### waferFlow

Wafer 流经工站的顺序列表，文档用途。引擎实际使用 `sequence.json` 中的 `flow` 步骤定义 wafer 流转。

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `waferFlow` | array | 否 | Wafer 流经工站名称数组，按处理顺序排列 |

示例：
```json
"waferFlow": ["FOUP", "LOADLOCK", "PRECLEAN", "PASSTHROUGH_FWD", "EPI", "PASSTHROUGH_RET", "LOADLOCK_RET", "FOUP_RET"]
```

### robot — 机械手策略配置

机械手调度策略配置，当前引擎未使用。

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `robot` | object | 否 | 机械手策略配置 |
| `robot.strategy` | string | 否 | 策略名称，预留 |
| `robot.swapEnabled` | boolean | 否 | 是否启用 swap 交换操作，当前仅支持 `false` |
| `robot.priorityReturnWafer` | boolean | 否 | 是否优先返回已完成 wafer |

示例：
```json
"robot": {
  "strategy": "SEQUENTIAL",
  "swapEnabled": false,
  "priorityReturnWafer": true
}
```

---

## 完整示例

```json
{
  "recipes": {
    "PRECLEAN": {
      "avgProcessTimeSec": 280,
      "processTimeVariationSec": 10,
      "maxDwellTimeSec": 120
    },
    "EPI": {
      "avgProcessTimeSec": 2120,
      "processTimeVariationSec": 30,
      "maxDwellTimeSec": 100
    },
    "PASSTHROUGH": {
      "avgProcessTimeSec": 0,
      "processTimeVariationSec": 0,
      "maxDwellTimeSec": 300
    },
    "LOADLOCK": {
      "avgProcessTimeSec": 0,
      "processTimeVariationSec": 0,
      "maxDwellTimeSec": 300
    }
  },
  "scheduling": {
    "policy": "PRIORITY",
    "targetWPH": 10,
    "maxWafersInSystem": 12,
    "waferStartIntervalSec": 0,
    "dwellSafetyMarginSec": 10
  },
  "timing": {
    "loadlockPumpTimeSec": 126,
    "loadlockVentTimeSec": 168,
    "loadlockLoadTimeSec": 5,
    "loadlockUnloadTimeSec": 5,
    "passthroughTransferTimeSec": 3,
    "coolingStationCoolTimeSec": 60
  },
  "waferFlow": ["FOUP", "LOADLOCK", "PRECLEAN", "PASSTHROUGH_FWD", "EPI", "PASSTHROUGH_RET", "LOADLOCK_RET", "FOUP_RET"],
  "robot": {
    "strategy": "SEQUENTIAL",
    "swapEnabled": false,
    "priorityReturnWafer": true
  },
  "simulation": {
    "speed": 15,
    "totalWafers": 25,
    "timeStepMs": 1000
  }
}
```
