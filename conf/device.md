# 半导体设备配置 (Semiconductor Equipment Configuration)

## 设备总览

| 参数 | 值 | 说明 |
|------|------|------|
| equipmentId | EPI-001 | 设备编号 |
| equipmentName | EPI Cluster Tool | 设备名称 |

## EFEM 配置 (大气环境)

| 参数 | 值 | 说明 |
|------|------|------|
| efemId | EFEM1 | EFEM编号 |
| loadPortCount | 3 | LoadPort数量 |
| slotsPerLoadPort | 25 | 每个LoadPort(FOUP)的槽位数 |
| atmRobotId | ATM1 | 大气机械手编号 |
| atmArms | 1 | 大气机械手臂数 |
| atmPickTime | 6 | 取片时间(秒) |
| atmPlaceTime | 6 | 放片时间(秒) |
| atmRotateTime | 3 | 旋转时间(秒) |

EFEM是大气环境，ATM Robot 负责将 wafer 从 FOUP (LoadPort上) 取到 LoadLock (BLL)。

## LoadLock 配置 (BLL - Batch LoadLock)

| 参数 | 值 | 说明 |
|------|------|------|
| loadlockCount | 2 | LoadLock数量 |
| slotsPerLoadlock | 1 | 每个LoadLock的wafer容量 |
| pumpTime | 126 | 抽真空时间(秒) |
| ventTime | 168 | 充气时间(秒) |

## Transfer Module 配置

### TM1

| 参数 | 值 | 说明 |
|------|------|------|
| id | TM1 | 编号 |
| robotArms | 2 | 机械手臂数 |
| fingersPerArm | 1 | 每个手臂的手指数 |
| pickTime | 6 | 取片时间(秒) |
| placeTime | 6 | 放片时间(秒) |
| rotateTime | 3 | 旋转时间(秒) |

**TM1 连接的腔室:**

| 腔室 | 类型 | 说明 |
|------|------|------|
| LL1 | LOADLOCK | LoadLock 1 |
| LL2 | LOADLOCK | LoadLock 2 |
| PreClean1 | PRECLEAN | 预清洁腔室 1 |
| PreClean2 | PRECLEAN | 预清洁腔室 2 |
| PT1 | PASSTHROUGH | 直通腔室 1 (TM1侧) |
| PT2 | PASSTHROUGH | 直通腔室 2 (TM1侧) |

### TM2

| 参数 | 值 | 说明 |
|------|------|------|
| id | TM2 | 编号 |
| robotArms | 2 | 机械手臂数 |
| fingersPerArm | 1 | 每个手臂的手指数 |
| pickTime | 6 | 取片时间(秒) |
| placeTime | 6 | 放片时间(秒) |
| rotateTime | 3 | 旋转时间(秒) |

**TM2 连接的腔室:**

| 腔室 | 类型 | 说明 |
|------|------|------|
| PT1 | PASSTHROUGH | 直通腔室 1 (TM2侧) |
| PT2 | PASSTHROUGH | 直通腔室 2 (TM2侧) |
| EPI1 | EPI | EPI工艺腔 1 |
| EPI2 | EPI | EPI工艺腔 2 |
| EPI3 | EPI | EPI工艺腔 3 |
| EPI4 | EPI | EPI工艺腔 4 |

## PassThrough 配置

| 参数 | 值 | 说明 |
|------|------|------|
| passthroughCount | 2 | PassThrough数量 |
| slotsPerPT | 2 | 每个PT的槽位数（均可缓存wafer） |
| coolingStation | PT1 Slot 0, PT2 Slot 1 | PT1的槽位0和PT2的槽位1具有CoolingStation功能，EPI加工完的wafer在此冷却 |

## 腔室类型定义

| 类型 | 说明 | 可并行处理 |
|------|------|------|
| LOADLOCK | 装载锁定室 | 否 |
| PRECLEAN | 预清洁腔室 | 否 |
| EPI | 外延生长工艺腔 | 否 |
| PASSTHROUGH | 传递腔室 | 否 |
