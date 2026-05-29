# sequence.json — Wafer 流转流程定义

定义单片 wafer 从 FOUP 出发，经过各工站加工，最终返回 FOUP 的完整步骤序列。

---

## 顶层字段

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `name` | string | 是 | 序列名称，job.json 中 PJ 的 `sequenceName` 字段引用此值 |
| `description` | string | 否 | 序列描述，仅文档用途 |
| `flow` | array | 是 | 流程步骤数组，按 `step` 升序排列 |
| `robotOperations` | object | 否 | 机械手操作映射。**当前调度引擎未直接使用此字段，操作由 engine 按 TM 角色硬编码** |

---

## flow[] — 流程步骤

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `step` | int | 是 | 步骤序号（从 1 开始），调度引擎内部以 `flowStep` 跟踪 wafer 进度 |
| `station` | string | 是 | 工站标识，如 `LP`、`ALIGNER`、`BLL`、`PRECLEAN`、`PT_FWD`、`EPI`、`PT_RET`、`BLL_RET` |
| `action` | string | 是 | 动作类型：`PICK`（取片）、`PLACE`（放片）、`PROCESS`（处理）、`TRANSIT`（传递）、`TRANSIT_COOLING`（传递+冷却）、`RETURN`（返回）、`DONE`（完成） |
| `robot` | string | 否 | 执行该步骤的机械手（`ATM`、`Robot1`、`Robot2`） |
| `next` | string | 否 | 下一步的目标 station |
| `from` | string | 否 | 来源 station（PLACE 动作） |
| `recipeKey` | string | 否 | 引用的 recipe 键名（PROCESS 动作），对应 schedule.json 中 `recipes` 的键 |
| `timeKey` | string | 否 | 引用 device.json 中的时间字段（如 `alignTimeSec`） |
| `robotIn` | string | 否 | 传入机械手（TRANSIT 动作） |
| `robotOut` | string | 否 | 传出机械手（TRANSIT 动作） |

### 当前 sige-epi 场景的 9 步流程

| step | station | action | 调度器内部 flowStep | 说明 |
|------|---------|--------|---------------------|------|
| 1 | LP | PICK | — | ATM 从 FOUP 取片 |
| 2 | ALIGNER | PROCESS | — | 晶圆对准（4.4s） |
| 3 | BLL | PLACE | 1 | ATM 放入 LoadLock |
| 4 | PRECLEAN | PROCESS | 5 | TM1 取出 → PreClean 处理（~280s） |
| 5 | PT_FWD | TRANSIT | 8 | TM1 放入 → TM2 取出，机械手交接 |
| 6 | EPI | PROCESS | 10 | TM2 取出 → EPI 处理（~2120s） |
| 7 | PT_RET | TRANSIT_COOLING | 13 | TM2 放入冷却槽 → 冷却 60s → TM1 取出 |
| 8 | BLL_RET | RETURN | 15 | TM1 放回 LoadLock，等待 batch vent |
| 9 | LP | DONE | 17 | wafer 回到 FOUP，标记完成 |

**关键拐点**：
- Step 5 (PT_FWD)：TM1→TM2 交接，是死锁预防的关键控制点
- Step 7 (PT_RET)：带 cooling 逻辑，冷却槽优先

---

## robotOperations — 机械手操作映射

定义调度器内部使用的操作键与 device.json 中操作键的对应关系。

```json
{
  "ATM": {
    "LP_TO_ALIGNER": { "opKey": "foupToAligner" },
    "ALIGNER_TO_BLL": { "opKey": "alignerToLL" }
  },
  "Robot1": {
    "BLL_TO_PRECLEAN": { "opKey": "LL_TO_PRECLEAN" },
    "PRECLEAN_TO_PT": { "opKey": "PRECLEAN_TO_PT" },
    "PT_TO_BLL": { "opKey": "PT_TO_LL" }
  },
  "Robot2": {
    "PT_TO_EPI": { "opKey": "PT_TO_EPI" },
    "EPI_TO_PT": { "opKey": "EPI_TO_PT" }
  }
}
```

**注意**：此字段目前为文档用途。调度引擎在 `SchedulerEngine.java` 中通过 `OP_*` 常量直接使用 device.json 的操作键。如需修改操作键名，需要：
1. 在 device.json 中修改 `operations` 的键
2. 在 `SchedulerEngine.java` 中修改对应的 `OP_*` 常量

---

## 完整示例

```json
{
  "name": "DefaultSequence",
  "description": "Standard EPI process flow: FOUP → Aligner → BLL → PreClean → PT → EPI → Cooling → FOUP",
  "flow": [
    { "step": 1, "station": "LP", "action": "PICK", "robot": "ATM", "next": "ALIGNER" },
    { "step": 2, "station": "ALIGNER", "action": "PROCESS", "timeKey": "alignTimeSec" },
    { "step": 3, "station": "BLL", "action": "PLACE", "robot": "ATM", "from": "ALIGNER", "next": "PRECLEAN" },
    { "step": 4, "station": "PRECLEAN", "action": "PROCESS", "recipeKey": "PRECLEAN", "next": "PT_FWD" },
    { "step": 5, "station": "PT_FWD", "action": "TRANSIT", "robotIn": "Robot1", "robotOut": "Robot2", "next": "EPI" },
    { "step": 6, "station": "EPI", "action": "PROCESS", "recipeKey": "EPI", "next": "PT_RET" },
    { "step": 7, "station": "PT_RET", "action": "TRANSIT_COOLING", "robotIn": "Robot2", "robotOut": "Robot1", "next": "BLL_RET" },
    { "step": 8, "station": "BLL_RET", "action": "RETURN", "next": "LP" },
    { "step": 9, "station": "LP", "action": "DONE" }
  ],
  "robotOperations": {
    "ATM": {
      "LP_TO_ALIGNER": { "opKey": "foupToAligner" },
      "ALIGNER_TO_BLL": { "opKey": "alignerToLL" }
    },
    "Robot1": {
      "BLL_TO_PRECLEAN": { "opKey": "LL_TO_PRECLEAN" },
      "PRECLEAN_TO_PT": { "opKey": "PRECLEAN_TO_PT" },
      "PT_TO_BLL": { "opKey": "PT_TO_LL" }
    },
    "Robot2": {
      "PT_TO_EPI": { "opKey": "PT_TO_EPI" },
      "EPI_TO_PT": { "opKey": "EPI_TO_PT" }
    }
  }
}
```
