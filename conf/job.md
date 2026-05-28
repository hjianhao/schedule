# Job 配置 (Job Configuration)

## 概念

- **Job**: 一次完整的生产任务，包含若干 Control Job
- **CJ (Control Job)**: 控制作业，管理一组 Process Job 的执行顺序和资源
- **PJ (Process Job)**: 工艺作业，定义需要处理的 Wafer 集合和使用的 Sequence

## 配置结构

### Job

| 参数 | 类型 | 说明 |
|------|------|------|
| name | string | Job 名称 |
| controlJobs | array[CJ] | Control Job 列表 |

### CJ (Control Job)

| 参数 | 类型 | 说明 |
|------|------|------|
| id | string | CJ 唯一标识 |
| name | string | CJ 名称 |
| processJobs | array[PJ] | Process Job 列表 |

### PJ (Process Job)

| 参数 | 类型 | 说明 |
|------|------|------|
| id | string | PJ 唯一标识 |
| sequenceName | string | 引用的 Sequence 名称（如 "DefaultSequence"） |
| wafers | WaferCollection | 待处理 Wafer 集合 |

### Wafer 集合 (WaferCollection)

| 参数 | 类型 | 说明 |
|------|------|------|
| subsets | array[WaferSubset] | Wafer 子集列表，可包含 1 至 N 个子集 |

### Wafer 子集 (WaferSubset)

| 参数 | 类型 | 说明 |
|------|------|------|
| lp | string | LoadPort 标识（如 "LP1", "LP2", "LP3"） |
| wafers | array[string] | Wafer 编号描述，支持单片和连续范围 |

**Wafer 编号格式**:
- `"5"` — 单片 wafer（W5）
- `"1-5"` — 连续范围（W1, W2, W3, W4, W5）
- `"1-5,10-15"` — 多个范围（可拆分到多个子集）

## 示例

### 单 PJ 全量生产

```json
{
  "name": "EPI_Production_Run",
  "controlJobs": [
    {
      "id": "CJ1",
      "name": "Standard EPI Process",
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

**含义**: CJ1 包含 1 个 PJ，PJ1 使用 DefaultSequence 处理 LP1 上编号 1 到 25 的 wafer。

### 多 PJ 多 LP 示例

```json
{
  "name": "Multi_Lot_Production",
  "controlJobs": [
    {
      "id": "CJ1",
      "name": "Lot A + Lot B",
      "processJobs": [
        {
          "id": "PJ1",
          "sequenceName": "DefaultSequence",
          "wafers": {
            "subsets": [
              { "lp": "LP1", "wafers": ["1-10"] }
            ]
          }
        },
        {
          "id": "PJ2",
          "sequenceName": "DefaultSequence",
          "wafers": {
            "subsets": [
              { "lp": "LP2", "wafers": ["1-15"] }
            ]
          }
        }
      ]
    }
  ]
}
```

**含义**: CJ1 包含 2 个 PJ，分别处理 LP1 的 10 片和 LP2 的 15 片 wafer。

## 关联关系

```
Job
└── CJ (Control Job)
    └── PJ (Process Job)
        ├── Sequence (引用 sequence.json 中的 sequenceName)
        └── WaferCollection
            └── WaferSubset (关联 LP)
                └── Wafer (编号或范围)
```
