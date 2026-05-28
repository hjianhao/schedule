# 自动维护配置 (Auto Maintenance Configuration)

## 概念

- **AM (Auto Maintenance)**: 腔室自动维护任务，在 wafer 加工前或批次间执行
- **1X Clean**: 每片 wafer 加工前执行一次清洁，保证腔室洁净度

## 任务类型

| 类型 | 说明 | 时机 |
|------|------|------|
| PRE_PROCESS | 工艺前维护 | 每片 wafer 放入腔室前执行 |

## 配置参数

### 任务定义

| 参数 | 类型 | 说明 |
|------|------|------|
| id | string | 任务唯一标识 |
| name | string | 任务名称 |
| type | string | 执行类型 (PRE_PROCESS) |
| cleanTimeSec | number | 清洁执行时间(秒) |
| gapTimeSec | number | 清洁完成后到放片的最大间隔(秒)，0=必须立即衔接 |
| appliesTo | array | 适用的腔室类型列表 |

### 适用腔室

| 参数 | 类型 | 说明 |
|------|------|------|
| chamberType | string | 腔室类型 (如 "EPI") |

## 调度约束

1X Clean 的 gapTimeSec 为 0 时：
- 调度器必须在 wafer 到达前规划清洁启动时间
- 清洁完成时刻必须与 TM 放片时刻对齐
- 腔室利用率计算需包含清洁时间

## 示例

### EPI 1X Clean

```json
{
  "id": "1X_CLEAN",
  "name": "1X Clean",
  "type": "PRE_PROCESS",
  "cleanTimeSec": 457,
  "gapTimeSec": 0,
  "appliesTo": [{ "chamberType": "EPI" }]
}
```

EPI 腔在每片 wafer 放入前执行 457s 清洁，清洁完成后立即（0s 间隔）放入 wafer。
