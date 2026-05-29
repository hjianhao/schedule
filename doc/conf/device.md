# device.json — 硬件拓扑配置

定义 Cluster Tool 的完整硬件布局：腔室类型与数量、机械手参数、LoadPort 容量、Passthrough 槽位分配。

---

## 顶层字段

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `equipmentId` | string | 是 | 设备唯一编号，用于报告和日志标识 |
| `equipmentName` | string | 是 | 设备名称，用于报告标题 |
| `efem` | object | 是 | EFEM (Equipment Front End Module) 大气环境配置 |
| `loadlocks` | array | 是 | LoadLock 腔室列表，每个 LL 为独立对象 |
| `transferModules` | array | 是 | 传输模块列表 (TM1, TM2)，定义机械手和其覆盖的腔室 |
| `chambers` | array | 是 | 工艺腔室列表 (PreClean, EPI 等) |
| `passthroughs` | array | 是 | Passthrough 传递腔室列表，定义槽位数和冷却槽 |

---

## efem — 大气环境

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `id` | string | 否 | EFEM 编号，默认 `EFEM1` |
| `loadPorts` | array | 是 | LoadPort (FOUP) 列表 |
| `aligner` | object | 是 | Aligner 对准器配置 |
| `atmRobot` | object | 是 | ATM 大气机械手配置 |

### efem.loadPorts[]

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `id` | string | 是 | LoadPort 编号，如 `LP1`、`LP2` |
| `foupIndex` | int | 是 | FOUP 逻辑索引（从 0 开始），代码中用于 wafer 命名 `W{idx+1}.{slot}` |
| `slots` | int | 是 | 该 FOUP 的 wafer 容量，通常为 25 |

示例：
```json
{ "id": "LP1", "foupIndex": 0, "slots": 25 }
```

### efem.aligner

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `alignTimeSec` | number | 是 | 晶圆对准时间（秒），支持小数 |

示例：
```json
{ "alignTimeSec": 4.4 }
```

### efem.atmRobot

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `id` | string | 是 | 大气机械手编号，通常 `ATM1` |
| `arms` | int | 是 | 手臂数量，当前仅支持 `1` |
| `fingersPerArm` | int | 是 | 每臂手指数，当前仅支持 `1` |
| `pickTimeSec` | int | 是 | 通用取片时间（秒），在无具体操作配置时作为默认值 |
| `placeTimeSec` | int | 是 | 通用放片时间（秒） |
| `rotateTimeSec` | int | 是 | 通用旋转时间（秒） |
| `foupToAligner` | object | 否 | FOUP→Aligner 的传输时间，覆盖默认值 |
| `alignerToLL` | object | 否 | Aligner→LoadLock 的传输时间，覆盖默认值 |

**foupToAligner / alignerToLL** — 操作时间子对象：

| 字段 | 类型 | 说明 |
|------|------|------|
| `pickTimeSec` | number | 取片时间（秒），支持小数 |
| `rotateTimeSec` | number | 旋转时间（秒） |
| `placeTimeSec` | number | 放片时间（秒） |

**总传输时间 = pick + rotate + place**，调度器以此计算机械手占用。

示例：
```json
{
  "id": "ATM1",
  "arms": 1,
  "fingersPerArm": 1,
  "pickTimeSec": 6,
  "placeTimeSec": 6,
  "rotateTimeSec": 3,
  "foupToAligner": { "pickTimeSec": 2.3, "rotateTimeSec": 3.2, "placeTimeSec": 2.4 },
  "alignerToLL": { "pickTimeSec": 2.8, "rotateTimeSec": 2.3, "placeTimeSec": 6.6 }
}
```

---

## loadlocks[] — LoadLock 腔室

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `id` | string | 是 | LoadLock 编号，如 `LL1`、`LL2` |
| `type` | string | 是 | 固定值 `BATCH`（批次装载模式） |
| `capacity` | int | 是 | 单批次最大 wafer 数 |
| `pumpTimeSec` | int | 是 | 抽真空时间（秒） |
| `ventTimeSec` | int | 是 | 充气回压时间（秒） |

**Batch LoadLock 工作模式**：一批 wafer 装载后 → 抽真空 → 逐个取出处理 → 全部返回后 → 充气 → 卸载回 FOUP。

示例：
```json
{ "id": "LL1", "type": "BATCH", "capacity": 25, "pumpTimeSec": 126, "ventTimeSec": 168 }
```

---

## transferModules[] — 传输模块

每个 transfer module 代表一个真空传输腔（TM），内含一个或多个机械手。

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `id` | string | 是 | TM 编号，如 `TM1`、`TM2` |
| `robots` | array | 是 | 该 TM 内的机械手列表，当前每 TM 仅支持 1 个 |
| `connectedChambers` | array | 是 | 该 TM 机械手可达的腔室 ID 列表（仅文档作用，调度逻辑中未使用） |

### transferModules[].robots[]

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `id` | string | 是 | 机械手编号，如 `Robot1`。**调度引擎通过 tmId 定位该 Robot，不依赖此 ID** |
| `arms` | int | 是 | 手臂数量，当前仅支持 `1` |
| `fingersPerArm` | int | 是 | 每臂手指数，当前仅支持 `1` |
| `pickTimeSec` | int | 是 | 通用取片时间（秒），作为未匹配操作时的默认值 |
| `placeTimeSec` | int | 是 | 通用放片时间（秒） |
| `rotateTimeSec` | int | 是 | 通用旋转时间（秒） |
| `operations` | object | 是 | **各传输操作的具体时间**，键名为操作名，值为操作时间对象 |

### operations 键名约定

每个操作键描述一次传输的起止腔室类型。调度器内部使用常量匹配这些键：

| 操作键 | 所属 TM | 含义 |
|--------|---------|------|
| `LL_TO_PRECLEAN` | TM1 | LL → PreClean，抽出新 wafer |
| `PRECLEAN_TO_PT` | TM1 | PreClean → PT fwd，推进前向流程 |
| `PT_TO_LL` | TM1 | PT ret → LL，返回已完成 wafer |
| `PT_TO_EPI` | TM2 | PT fwd → EPI，送入 EPI 腔 |
| `EPI_TO_PT` | TM2 | EPI → PT ret，取出完成后 wafer |

**如果 device.json 中修改了操作键名，需要同步修改 `SchedulerEngine.java` 中的 `OP_*` 常量。**

示例：
```json
{
  "id": "TM1",
  "robots": [
    {
      "id": "Robot1",
      "arms": 1,
      "fingersPerArm": 1,
      "pickTimeSec": 6,
      "placeTimeSec": 6,
      "rotateTimeSec": 3,
      "operations": {
        "LL_TO_PRECLEAN": { "pickTimeSec": 16, "rotateTimeSec": 6, "placeTimeSec": 52 },
        "PRECLEAN_TO_PT": { "pickTimeSec": 46, "rotateTimeSec": 6, "placeTimeSec": 25 },
        "PT_TO_LL": { "pickTimeSec": 23, "rotateTimeSec": 6, "placeTimeSec": 8 }
      }
    }
  ],
  "connectedChambers": ["LL1", "LL2", "PreClean1", "PreClean2", "PT1", "PT2"]
}
```

---

## chambers[] — 工艺腔室

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `id` | string | 是 | 腔室编号，如 `PreClean1`、`EPI3` |
| `type` | string | 是 | 腔室类型，支持 `PRECLEAN`、`EPI` |

**类型决定调度行为**：
- `PRECLEAN`：调度器为其分配 IdlePurge、OnLoadClean
- `EPI`：调度器为其分配 1X Clean、OnLoadClean、stagger 控制

示例：
```json
{ "id": "PreClean1", "type": "PRECLEAN" },
{ "id": "EPI1", "type": "EPI" }
```

---

## passthroughs[] — Passthrough 传递腔室

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `id` | string | 是 | PT 编号，如 `PT1`、`PT2` |
| `slots` | int | 是 | 槽位数量，通常为 2 |
| `coolingStationSlot` | int | 否 | 冷却槽位索引（从 0 开始）。未设置则该 PT 无冷却功能 |

**调度策略**：
- fwd 方向（PreClean→EPI）：优先使用非冷却槽（buffer 槽）
- ret 方向（EPI→LL）：优先使用冷却槽（带 60s CoolingStation 冷却）
- 冷却槽在 `passthroughs[].coolingStationSlot` 中指定

示例：
```json
{ "id": "PT1", "slots": 2, "coolingStationSlot": 0 },
{ "id": "PT2", "slots": 2, "coolingStationSlot": 1 }
```
PT1_S0 和 PT2_S1 为冷却槽，其余为 buffer 槽。

---

## 完整示例

```json
{
  "equipmentId": "EPI-001",
  "equipmentName": "Cluster Tool",
  "efem": {
    "id": "EFEM1",
    "loadPorts": [
      { "id": "LP1", "foupIndex": 0, "slots": 25 },
      { "id": "LP2", "foupIndex": 1, "slots": 25 },
      { "id": "LP3", "foupIndex": 2, "slots": 25 }
    ],
    "aligner": { "alignTimeSec": 4.4 },
    "atmRobot": {
      "id": "ATM1", "arms": 1, "fingersPerArm": 1,
      "pickTimeSec": 6, "placeTimeSec": 6, "rotateTimeSec": 3,
      "foupToAligner": { "pickTimeSec": 2.3, "rotateTimeSec": 3.2, "placeTimeSec": 2.4 },
      "alignerToLL": { "pickTimeSec": 2.8, "rotateTimeSec": 2.3, "placeTimeSec": 6.6 }
    }
  },
  "loadlocks": [
    { "id": "LL1", "type": "BATCH", "capacity": 25, "pumpTimeSec": 126, "ventTimeSec": 168 },
    { "id": "LL2", "type": "BATCH", "capacity": 25, "pumpTimeSec": 126, "ventTimeSec": 168 }
  ],
  "transferModules": [
    {
      "id": "TM1",
      "robots": [{
        "id": "Robot1", "arms": 1, "fingersPerArm": 1,
        "pickTimeSec": 6, "placeTimeSec": 6, "rotateTimeSec": 3,
        "operations": {
          "LL_TO_PRECLEAN": { "pickTimeSec": 16, "rotateTimeSec": 6, "placeTimeSec": 52 },
          "PRECLEAN_TO_PT": { "pickTimeSec": 46, "rotateTimeSec": 6, "placeTimeSec": 25 },
          "PT_TO_LL": { "pickTimeSec": 23, "rotateTimeSec": 6, "placeTimeSec": 8 }
        }
      }],
      "connectedChambers": ["LL1", "LL2", "PreClean1", "PreClean2", "PT1", "PT2"]
    },
    {
      "id": "TM2",
      "robots": [{
        "id": "Robot2", "arms": 1, "fingersPerArm": 1,
        "pickTimeSec": 6, "placeTimeSec": 6, "rotateTimeSec": 3,
        "operations": {
          "PT_TO_EPI": { "pickTimeSec": 22, "rotateTimeSec": 7, "placeTimeSec": 44 },
          "EPI_TO_PT": { "pickTimeSec": 80, "rotateTimeSec": 7, "placeTimeSec": 25 }
        }
      }],
      "connectedChambers": ["PT1", "PT2", "EPI1", "EPI2", "EPI3", "EPI4"]
    }
  ],
  "chambers": [
    { "id": "PreClean1", "type": "PRECLEAN" },
    { "id": "PreClean2", "type": "PRECLEAN" },
    { "id": "EPI1", "type": "EPI" },
    { "id": "EPI2", "type": "EPI" },
    { "id": "EPI3", "type": "EPI" },
    { "id": "EPI4", "type": "EPI" }
  ],
  "passthroughs": [
    { "id": "PT1", "slots": 2, "coolingStationSlot": 0 },
    { "id": "PT2", "slots": 2, "coolingStationSlot": 1 }
  ]
}
```
