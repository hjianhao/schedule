# job.json — 生产任务配置

定义生产任务（Job）：哪些 wafer 从哪些 LoadPort 以何种顺序处理。

---

## 概念层级

```
Job
└── ControlJob (CJ)       — 控制作业，管理 wafer 和资源
    └── ProcessJob (PJ)   — 工艺作业，指定 wafer 集合 + 工艺流程
        └── WaferSubset   — wafer 子集，绑定到特定 LoadPort
```

---

## 顶层字段

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `name` | string | 是 | Job 名称，用于报告和日志 |
| `controlJobs` | array | 是 | Control Job 列表 |

---

## ControlJob (CJ)

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `id` | string | 是 | CJ 唯一标识，前端通过此 ID 选择启动 |
| `name` | string | 否 | CJ 名称 |
| `mode` | string | 是 | 执行模式：`serial`（顺序执行 PJ）或 `parallel`（非当前版本不支持） |
| `processJobs` | array | 是 | Process Job 列表 |

### CJ 执行模式

**`serial` 模式**：
- PJ 按数组顺序逐一执行
- 第一个 PJ 的 wafer 全部完成后，才开始第二个 PJ 的 wafer
- 适用于不同 LP 的批次连续生产

当前仅支持 `serial` 模式。

---

## ProcessJob (PJ)

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `id` | string | 是 | PJ 唯一标识 |
| `sequenceName` | string | 是 | 引用的流程名称，对应 sequence.json 的 `name` 字段 |
| `wafers` | object | 是 | Wafer 集合定义 |

### PJ.wafers — Wafer 集合

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `subsets` | array | 是 | Wafer 子集列表，每个子集绑定到一个 LoadPort |

### PJ.wafers.subsets[] — Wafer 子集

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `lp` | string | 是 | LoadPort 标识，如 `LP1`、`LP2`。必须与 device.json 中 `loadPorts[].id` 匹配 |
| `wafers` | array | 是 | Wafer 编号列表，每项为单编号或范围字符串 |

### Wafer 编号格式

| 格式 | 示例 | 解析结果 |
|------|------|---------|
| 单编号 | `"5"` | wafer 5 |
| 范围 | `"1-25"` | wafer 1 到 25（含两端） |
| 混合 | `"1-10"`, `"15"`, `"20-25"` | 1-10, 15, 20-25 |

**Wafer 命名规则**：`W{LP_INDEX}.{SLOT_NUMBER}`，如 LP1 的 slot 5 → `W1.5`。

---

## 示例

### 单 PJ — 一个 LP 全量

```json
{
  "name": "EPI_Production_Run",
  "controlJobs": [
    {
      "id": "CJ1",
      "name": "LP1 Full Run",
      "mode": "serial",
      "processJobs": [
        {
          "id": "PJ1",
          "sequenceName": "DefaultSequence",
          "wafers": {
            "subsets": [
              { "lp": "LP1", "wafers": ["1-25"] }
            ]
          }
        }
      ]
    }
  ]
}
```

LP1 的 25 片 wafer 全部处理。

### 多 PJ — 多 LP 串行

```json
{
  "name": "Multi_LP_Production",
  "controlJobs": [
    {
      "id": "CJ1",
      "name": "LP1 Full + LP2 Partial",
      "mode": "serial",
      "processJobs": [
        {
          "id": "PJ1",
          "sequenceName": "DefaultSequence",
          "wafers": {
            "subsets": [
              { "lp": "LP1", "wafers": ["1-25"] }
            ]
          }
        },
        {
          "id": "PJ2",
          "sequenceName": "DefaultSequence",
          "wafers": {
            "subsets": [
              { "lp": "LP2", "wafers": ["3-10"] }
            ]
          }
        }
      ]
    }
  ]
}
```

先处理 LP1 的 25 片（PJ1），完成后再处理 LP2 的 8 片（slot 3-10）（PJ2）。总计 33 片。

### 单 PJ — 多范围

```json
{
  "name": "Selective_Production",
  "controlJobs": [
    {
      "id": "CJ1",
      "name": "Selected Wafers",
      "mode": "serial",
      "processJobs": [
        {
          "id": "PJ1",
          "sequenceName": "DefaultSequence",
          "wafers": {
            "subsets": [
              { "lp": "LP1", "wafers": ["1-5", "10-15", "20"] }
            ]
          }
        }
      ]
    }
  ]
}
```

LP1 的 slot 1-5, 10-15, 20 共 12 片。

---

## 完整示例

```json
{
  "name": "EPI_Production_Run",
  "controlJobs": [
    {
      "id": "CJ1",
      "name": "LP1 Full + LP2 Partial",
      "mode": "serial",
      "processJobs": [
        {
          "id": "PJ1",
          "sequenceName": "DefaultSequence",
          "wafers": {
            "subsets": [
              { "lp": "LP1", "wafers": ["1-25"] }
            ]
          }
        },
        {
          "id": "PJ2",
          "sequenceName": "DefaultSequence",
          "wafers": {
            "subsets": [
              { "lp": "LP2", "wafers": ["3-10"] }
            ]
          }
        }
      ]
    }
  ]
}
```
