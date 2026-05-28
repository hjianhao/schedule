# 调度参数配置 (Scheduling Parameters Configuration)

## 工艺Recipe配置

### PreClean Recipe

| 参数 | 值 | 说明 |
|------|------|------|
| chamberType | PRECLEAN | 腔室类型 |
| avgProcessTime | 280 | 平均工艺时间(秒) = 4分40秒 |
| processTimeVariation | 10 | 工艺时间波动(秒) |
| maxDwellTime | 120 | 最大驻留时间(秒) = 2分钟 |

### EPI Recipe

| 参数 | 值 | 说明 |
|------|------|------|
| chamberType | EPI | 腔室类型 |
| avgProcessTime | 2120 | 平均工艺时间(秒) = 35分20秒 |
| processTimeVariation | 30 | 工艺时间波动(秒) |
| maxDwellTime | 100 | 最大驻留时间(秒) = 1分40秒 |

### PassThrough Recipe

| 参数 | 值 | 说明 |
|------|------|------|
| chamberType | PASSTHROUGH | 传递腔室 |
| maxDwellTime | 300 | 最大驻留时间(秒) = 5分钟 |

## Wafer流转路径 (Recipe Flow)

```
FOUP(LoadPort) → [ATM] → LoadLock(BLL) → [TM1] → PreClean → [TM1] → PassThrough → [TM2] → EPI → [TM2] → PassThrough(CoolingStation ❄) → [TM1] → LoadLock → [ATM] → FOUP
```

## 调度策略参数

| 参数 | 值 | 说明 |
|------|------|------|
| schedulingPolicy | PRIORITY | 调度策略(PRIORITY/FIFO/ROUND_ROBIN) |
| targetWPH | 10 | 目标WPH(wafers per hour) |
| maxWafersInSystem | 12 | 系统内最大wafer数量 |
| waferStartInterval | 0 | Wafer启动间隔(秒), 0=根据EPI腔数自动计算(1800s/4=450s/片) |

## 机械手调度策略

| 参数 | 值 | 说明 |
|------|------|------|
| robotStrategy | SWAP | 机械手策略(SWAP/SEQUENTIAL) |
| swapEnabled | true | 是否启用交换取放片 |
| priorityReturnWafer | true | 优先回收已完成wafer |

## 时间参数

| 参数 | 值 | 说明 |
|------|------|------|
| loadlockPumpTime | 126 | LoadLock抽真空时间(秒) |
| loadlockVentTime | 168 | LoadLock充气时间(秒) |
| loadlockLoadTime | 5 | LoadLock装载wafer时间(秒) |
| loadlockUnloadTime | 5 | LoadLock卸载wafer时间(秒) |
| passthroughTransferTime | 3 | PassThrough传递时间(秒) |
| coolingStationCoolTime | 60 | CoolingStation冷却时间(秒)，EPI加工完wafer在PT2冷却槽位冷却 |

## 模拟参数

| 参数 | 值 | 说明 |
|------|------|------|
| simulationSpeed | 10 | 模拟速度倍率 |
| totalWafers | 25 | 总共需要处理的wafer数 |
| timeStepMs | 1000 | 每个时间步的毫秒数 |
