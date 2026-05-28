# Wafer 流转序列 (Sequence Configuration)

## 默认序列

### DefaultSequence

**描述**: 标准 EPI 工艺流程，wafer 从 FOUP 取出经 Aligner 对准后进入真空环境，依次经过预清洁、外延生长、冷却后返回 FOUP。

**流程顺序**:

| 步骤 | 工站 | 类型 | 说明 |
|------|------|------|------|
| 1 | LP (LoadPort) | 起始 | 从 FOUP 取出 wafer |
| 2 | Aligner | 对准 | 大气环境晶圆对准 (4.4s) |
| 3 | BLL (LoadLock) | 批量装载 | 大气→真空接口，批量抽真空 |
| 4 | PreClean | 预清洁 | 去除表面氧化层 (280s) |
| 5 | PT (PassThrough) | 传递 | TM1→TM2 wafer 中转 |
| 6 | EPI | 外延生长 | 核心工艺腔 (2120s) |
| 7 | PT (CoolingStation) | 冷却 | EPI 后强制冷却 60s |
| 8 | BLL (LoadLock) | 批量卸载 | 真空→大气接口，充气 |
| 9 | LP (LoadPort) | 结束 | wafer 回到 FOUP |

**流程路径**:
```
LP → [ATM] → Aligner(4.4s) → [ATM] → BLL(Pump 126s) → [TM1] → PreClean(280s) → [TM1] → PT(fwd) → [TM2] → EPI(2120s) → [TM2] → PT(Cooling 60s) → [TM1] → BLL(Vent 168s) → [ATM] → LP
```

**约束**:
- PreClean 最大驻留: 120s（工艺结束到 TM1 开始 Pick）
- EPI 最大驻留: 100s（工艺结束到 TM2 开始 Pick）
- PT 最大驻留: 300s（wafer 在 PT 内等待时间）
- CoolingStation: 60s 冷却为工艺时间，总 PT 驻留 ≤ 300s
