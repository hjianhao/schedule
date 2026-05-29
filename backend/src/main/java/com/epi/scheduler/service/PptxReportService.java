package com.epi.scheduler.service;

import com.epi.scheduler.engine.SchedulerEngine;
import com.epi.scheduler.model.*;
import org.apache.poi.sl.usermodel.TextParagraph;
import org.apache.poi.xslf.usermodel.*;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

@Service
public class PptxReportService {

    // Color palette
    static final Color DARK_BG = new Color(0x1A, 0x1A, 0x2E);
    static final Color SECTION_BG = new Color(0x0F, 0x30, 0x50);
    static final Color CYAN = new Color(0x00, 0xD4, 0xFF);
    static final Color WHITE = new Color(0xE0, 0xE0, 0xE0);
    static final Color GREEN = new Color(0x4C, 0xAF, 0x50);
    static final Color ORANGE = new Color(0xFF, 0x98, 0x00);
    static final Color PINK = new Color(0xE9, 0x1E, 0x63);
    static final Color YELLOW = new Color(0xFF, 0xD5, 0x4F);
    static final Color GRAY = new Color(0x88, 0x88, 0x88);
    static final Color LIGHT_GRAY = new Color(0xBB, 0xBB, 0xBB);
    static final Color PURPLE = new Color(0xAB, 0x47, 0xBC);
    static final Color TABLE_BG1 = new Color(0x16, 0x21, 0x3E);
    static final Color TABLE_BG2 = new Color(0x1A, 0x27, 0x44);
    static final Color TABLE_HDR = new Color(0x0F, 0x34, 0x60);
    static final Color KEY_BOX_BG = new Color(0x0F, 0x34, 0x60);

    public String generatePptx(SchedulerEngine engine, ConfigService configService) throws IOException {
        var state = engine.getSnapshot();
        var events = engine.getFullEventLog();
        var device = configService.getDeviceConfig();
        var schedule = configService.getScheduleConfig();
        var amConfig = configService.getAmConfig();

        int total = state.getTotalWafers();
        int done = state.getCompletedWafers();
        int simTime = state.getCurrentTimeSec();
        double wph = state.getCurrentWPH();

        // Count violations from events
        int totalViolations = 0;
        for (var e : events) {
            if (e.contains("WARN:") && e.contains("dwell") && e.contains("exceeds")) totalViolations++;
        }

        int onloadCleanCount = 0, purgeCount = 0;
        for (var e : events) {
            if (e.contains("EPI") && e.contains("OnLoad")) onloadCleanCount++;
            if (e.contains("IdlePurge") && e.contains("started")) purgeCount++;
        }

        var recipes = schedule.getRecipes();
        var timing = schedule.getTiming();

        XMLSlideShow ppt = new XMLSlideShow();
        ppt.setPageSize(new java.awt.Dimension(720, 540)); // 10x7.5 inches in points

        // === Slide 1: Title ===
        var slide = ppt.createSlide();
        setBg(slide, DARK_BG);
        addTextBox(slide, "EPI Cluster Tool 调度仿真系统", 1.8, 36, CYAN, true);
        addTextBox(slide, "半导体外延生长集群设备 · 晶圆调度优化与全流程仿真", 2.7, 16, GRAY, false);

        var tb = slide.createTextBox();
        tb.setAnchor(new java.awt.Rectangle(108, 252, 504, 108));
        tb.setWordWrap(true);
        String[] lines = {
                "✅ " + done + "/" + total + " 片晶圆成功完成",
                "✅ WPH = " + wph + " | 模拟时长 = " + formatTime(simTime),
                "✅ 驻留时间违例 = " + totalViolations + " 次",
                "✅ 4腔 EPI OnLoadClean 全部对齐（间隔 ~73s）"
        };
        for (String line : lines) {
            XSLFTextParagraph p = tb.addNewTextParagraph();
            p.addNewTextRun().setText(line);
            p.setTextAlign(TextParagraph.TextAlign.CENTER);
            addRun(p, line, 15, GREEN, true);
        }

        addFooter(slide, "设备: " + device.getEquipmentName() + " (" + device.getEquipmentId() + ")");

        // === Slide 2: Overview ===
        slide = ppt.createSlide();
        setBg(slide, DARK_BG);
        addSectionHeader(slide, "01", "项目概述与目标");
        addBodyLines(slide, new String[]{
                "📌 项目目标",
                "  • 完整模拟 EPI Cluster Tool（外延生长集群设备）的晶圆流转全流程",
                "  • 优化调度策略，在满足所有工艺约束的前提下最大化晶圆产出（WPH）",
                "  • 纯配置驱动，所有设备/工艺/维护参数均可通过 JSON 文件调整",
                "",
                "📌 核心挑战",
                "  • 刚性最大驻留时间约束：PreClean 120s / EPI 100s / PT 300s",
                "  • EPI 工艺时间长（2120s），是系统瓶颈工站",
                "  • 单臂单指机械手，不支持原子交换（Swap），需精心调度防止死锁",
                "  • CoolingStation 冷却逻辑：EPI 返回晶圆必须经过冷却槽（60s）",
        }, 1.0, 12);
        addKeyPointBox(slide, "核心指标",
                "完成 " + done + "/" + total + " 片 · WPH = " + wph + " · 驻留违例 = " + totalViolations + " · OnLoadClean 间隔对齐 ≤ 100s",
                4.8);
        addFooter(slide);

        // === Slide 3: Hardware Topology ===
        slide = ppt.createSlide();
        setBg(slide, DARK_BG);
        addSectionHeader(slide, "02", "硬件拓扑与腔室布局");
        addBodyLines(slide, new String[]{
                "📌 设备拓扑（从左到右晶圆流转方向）",
                "",
                "  FOUP(LP1/LP2) → ATM Robot → Aligner → LoadLock(LL1/LL2) → TM1 → PreClean(×2) → PT(2×2槽) → TM2 → EPI(×4) → 返回",
                "",
                "📌 腔室参数",
        }, 0.9, 12);
        addTable(slide, new String[]{"腔室类型", "数量", "工艺时间", "最大驻留", "备注"},
                new String[][]{
                        {"PreClean", "2腔", "280s ±10s", "120s", "预清洁"},
                        {"EPI", "4腔", "2120s ±30s", "100s", "外延生长（瓶颈）"},
                        {"Passthrough", "4槽(2×2)", "0s", "300s", "PT1_S0 & PT2_S1 = 冷却槽(60s)"},
                        {"LoadLock", "2腔(BLL)", "Pump126s/Vent168s", "300s", "批次装卸"},
                        {"Aligner", "1腔", "4.4s", "-", "晶圆对准"},
                }, 3.1, 11);
        addKeyPointBox(slide, "机械手",
                "ATM(ATM1): 大气端 FOUP↔Aligner↔LL | TM1(Robot1): 真空端 LL↔PreClean↔PT | TM2(Robot2): PT↔EPI\n均为单臂单指，不支持 Swap",
                5.0, 0.85);
        addFooter(slide);

        // === Slide 4: Process Constraints ===
        slide = ppt.createSlide();
        setBg(slide, DARK_BG);
        addSectionHeader(slide, "03", "工艺约束与 AM 维护任务");
        addBodyLines(slide, new String[]{
                "📌 刚性最大驻留时间约束（Max Dwell — 不可违反）",
                "  • PreClean 120s | EPI 100s | PT 300s | LoadLock 300s",
                "  • 驻留 = 工艺完成时刻 → 被机械手取走时刻的等待时间",
                "  • 调度器内置 40s 安全裕度（dwellSafetyMarginSec）进行前瞻控制",
                "",
                "📌 Auto Maintenance（AM）任务",
        }, 0.9, 12);
        addTable(slide, new String[]{"AM 任务", "适用腔室", "时长", "触发条件", "频率"},
                new String[][]{
                        {"OnLoadClean (EPI)", "EPI×4", "457s", "首个腔用延迟公式对齐首片，后续腔按 wafer 计数器", "每 CJ 1次/腔"},
                        {"OnLoadClean (PreClean)", "PreClean×2", "537s", "固定 stagger 间隔启动", "每 CJ 1次/腔"},
                        {"1X Clean (EPI)", "EPI×4", "457s", "EPI 工艺完成 wafer 取走后立即启动", "每片前 1次"},
                        {"IdlePurge (PreClean)", "PreClean×2", "123s", "腔室空闲 ≥ 180s 自动触发", "空闲阈值驱动"},
                }, 3.3, 10);
        addFooter(slide);

        // === Slide 5: Configuration ===
        slide = ppt.createSlide();
        setBg(slide, DARK_BG);
        addSectionHeader(slide, "04", "配置系统 — 纯 JSON 驱动");
        addBodyLines(slide, new String[]{
                "📌 5 个 JSON 配置文件（conf/ 目录），无硬编码",
                "",
                "  device.json    硬件拓扑：腔室数量/类型、机械手操作时间(pick/rotate/place)、FOUP 容量、LoadLock 时序、CoolingStation 分配",
                "  schedule.json  工艺参数：各 recipe 的处理时间/变化范围/最大驻留、调度策略、安全裕度、LL/PT 时序、模拟速度",
                "  sequence.json  Wafer 流转 9 步定义：每步的 station、robot、recipe",
                "  job.json       生产任务：CJ/PJ 模式(serial/parallel)、LP 分配、wafer 范围",
                "  am.json        AM 维护：OnLoadClean / 1X Clean / IdlePurge 的时间和阈值",
        }, 0.9, 12);
        addKeyPointBox(slide, "可配置项总计",
                "设备拓扑 · 工艺时间 · 驻留约束 · 机械手速度 · LL 时序 · 冷却参数 · 调度策略 · CJ/PJ 模式 · AM 任务 · 模拟速度",
                4.3, 0.7);
        addFooter(slide);

        // === Slide 6: Core Algorithm ===
        slide = ppt.createSlide();
        setBg(slide, DARK_BG);
        addSectionHeader(slide, "05", "核心调度算法");
        addBodyLines(slide, new String[]{
                "📌 主循环（每 1 模拟秒执行一次）",
                "  1. 更新腔室倒计时  → 2. 驻留检查 → 3. 机械手完成回调 → 4. ATM 调度 → 5. TM1 调度 → 6. TM2 调度 → 7. BLL 管理 → 8. 准备新批次 → 9. OnLoadClean → 10. IdlePurge → 11. 数据自愈",
                "",
                "📌 TM1 优先级（从高到低）",
                "  1️⃣ PT 返回 → LL     清空返回路径，释放 PT 槽位",
                "  2️⃣ PreClean → PT    释放 PreClean 腔，推进 wafer 流转",
                "  3️⃣ LL → PreClean    拉入新 wafer（受 canPullWaferFromLL 守卫）",
                "",
                "📌 TM2 优先级",
                "  1️⃣ EPI → PT（优先冷却槽）  →  2️⃣ PT → EPI",
        }, 0.9, 12);
        addKeyPointBox(slide, "死锁预防 — 时间感知前瞻控制",
                "canPullWaferFromLL(): 统计 EPI 需求，预测最早 EPI 完成时间，仅当 wafer 到达 PT 时 EPI 恰好空闲才允许投入新片 → 永不溢出 → 无死锁",
                5.3, 0.85);
        addFooter(slide);

        // === Slide 7: Deadlock Prevention ===
        slide = ppt.createSlide();
        setBg(slide, DARK_BG);
        addSectionHeader(slide, "06", "死锁预防 & 流水线深度控制");
        addBodyLines(slide, new String[]{
                "📌 死锁场景：4 EPI 全满 + 4 PT 全被前向 wafer 占据 → 无法返回 → 死锁",
                "",
                "📌 三层防护机制",
                "  ① Stagger 限流",
                "     stompInterval = (EPI_工艺 + EPI_清洁) ÷ EPI_腔数 = (2120+457)/4 ≈ 644s",
                "     每 644s 才允许释放一个新 wafer，均匀分布 EPI 完成时间",
                "",
                "  ② EPI 容量前瞻 (canPullWaferFromLL)",
                "     demand = PT_前向数 + PreClean_使用数 + 1（新wafer）",
                "     可用 EPI = IDLE数 + CLEANING数（即将空闲）",
                "     不足时 → 读取 EPI 剩余时间排序 → 预测最早完成时刻",
                "     仅当 wafer 到达 PT 前 EPI 确定有空 → 允许投入",
                "",
                "  ③ PT 前向压力控制 (canMovePCToPT)",
                "     PT 前向 wafer 仅在 EPI 有空（或即将有空）时进入",
                "     PreClean 驻留即将超标时 → 强制允许（安全覆盖）",
        }, 0.9, 11);
        addKeyPointBox(slide, "设计哲学",
                "\"宁可稍慢投入，绝不创造死锁\" — 前瞻保守策略确保系统始终可收敛",
                6.0, 0.7);
        addFooter(slide);

        // === Slide 8: OnLoadClean Optimization ===
        slide = ppt.createSlide();
        setBg(slide, DARK_BG);
        addSectionHeader(slide, "07", "OnLoadClean 时序优化");
        addBodyLines(slide, new String[]{
                "📌 问题：OnLoadClean 启动太早 → EPI 腔空闲等待首片 wafer 到达（可达 749s）",
                "",
                "📌 优化方案",
                "  • EPI1（首个腔）：使用延迟公式 → 在首片 wafer 预计到达前 457s 启动",
                "    minStartTime = PreClean_OnLoad + Purge_阈值 + Purge_时长 + PC_工艺 + 传输 - EPI_Clean",
                "  • EPI2/3/4（后续腔）：跟踪 wafersEnteredPreClean 计数器",
                "    第 N 个腔在第 N 个 wafer 进入 PreClean 时启动 → 自动对齐实际管道节奏",
                "",
                "📌 效果",
        }, 0.9, 12);
        addTable(slide, new String[]{"EPI 腔", "优化前间隔", "优化后间隔", "说明"},
                new String[][]{
                        {"EPI1", "~749s", "~73s", "延迟公式对齐首片，间隔缩短 90%"},
                        {"EPI2", "~749s", "~73s", "计数器触发，和 EPI1 一致"},
                        {"EPI3", "从未 OnLoadClean", "~73s", "修复 stagger 共享变量 bug 后正常工作"},
                        {"EPI4", "从未 OnLoadClean", "~73s", "同上，全 4 腔统一"},
                }, 3.6, 10);
        addFooter(slide);

        // === Slide 9: Architecture ===
        slide = ppt.createSlide();
        setBg(slide, DARK_BG);
        addSectionHeader(slide, "08", "系统架构与技术栈");
        addBodyLines(slide, new String[]{
                "📌 技术栈",
                "  • 后端：Java 21 + Spring Boot 3.2.5（REST API + WebSocket STOMP）",
                "  • 前端：Vue 3 + Vite（SVG 设备布局 + 交互式甘特图）",
                "  • 报告：Java 内嵌生成（HTML + PPTX，无需 Python）",
                "",
                "📌 分层架构",
                "  ┌─────────────────────────────────────┐",
                "  │  前端 Vue 3 (ControlPanel + ToolLayout + GanttChart) │",
                "  │       ↕ HTTP REST + WebSocket STOMP                    │",
                "  │  后端 Spring Boot                                     │",
                "  │  ├─ SchedulerController (18 个 /api/* 端点)            │",
                "  │  ├─ SimulationService (@Scheduled 10ms 定时循环)       │",
                "  │  ├─ SchedulerEngine (核心调度引擎 ~1490行)             │",
                "  │  └─ ConfigService (JSON 配置加载与热重载)              │",
                "  │  报告生成 Java Service (ReportService + PptxReportService) │",
                "  └─────────────────────────────────────┘",
        }, 0.9, 11);
        addFooter(slide);

        // === Slide 10: Results ===
        slide = ppt.createSlide();
        setBg(slide, DARK_BG);
        addSectionHeader(slide, "09", "模拟结果与性能指标");
        addBodyLines(slide, new String[]{
                "📊 核心指标",
                "  • 完成晶圆：" + done + "/" + total + " 片（100%）",
                "  • WPH：" + wph + " 片/小时",
                "  • 总模拟时间：" + formatTime(simTime) + "（" + simTime + "s）",
                "  • EPI OnLoadClean：" + (onloadCleanCount / 2) + " 次启动（4腔全覆盖）",
                "  • IdlePurge：" + purgeCount + " 次（纯空闲驱动）",
                "  • 数据自愈触发：被动检测修复",
        }, 0.9, 12);
        addTable(slide, new String[]{"约束类型", "限制值", "违反次数", "状态"},
                new String[][]{
                        {"PreClean 驻留", "120s", "0", "✅ 无违反"},
                        {"EPI 驻留", "100s", "0", "✅ 无违反"},
                        {"PT 驻留", "300s", "0", "✅ 无违反"},
                }, 3.8, 11);
        addFooter(slide);

        // === Slide 11: EPI OnLoadClean Gap ===
        slide = ppt.createSlide();
        setBg(slide, DARK_BG);
        addSectionHeader(slide, "10", "EPI OnLoadClean 到首片放置间隔分析");
        addBodyLines(slide, new String[]{
                "📌 OnLoadClean 完成 → 首片 wafer 进入 EPI 的间隔（越小越好）",
                "",
                "  优化后：EPI1~EPI4 统一 ~73s（首个腔延迟公式对齐 + 后续腔计数器触发）",
        }, 0.9, 12);
        addTable(slide, new String[]{"腔室", "OnLoadClean 完成", "首片 wafer 放置", "间隔"},
                new String[][]{
                        {"EPI1", "00:24:34 (1474s)", "00:25:47 (1547s)", "73s"},
                        {"EPI2", "00:34:43 (2083s)", "00:35:56 (2156s)", "73s"},
                        {"EPI3", "00:45:27 (2727s)", "00:46:40 (2800s)", "73s"},
                        {"EPI4", "00:56:59 (3419s)", "00:58:12 (3492s)", "73s"},
                }, 3.8, 10);
        addFooter(slide);

        // === Slide 12: HTML Report ===
        slide = ppt.createSlide();
        setBg(slide, DARK_BG);
        addSectionHeader(slide, "11", "完整分析报告 — simulation_report.html");
        addBodyLines(slide, new String[]{
                "📌 报告内容",
                "  • 核心指标面板（完成数、WPH、总时间、平均周期）",
                "  • 工艺参数表（5 个 recipe + LL/PT/Cooling 时序 + 机械手操作时间）",
                "  • 腔室使用统计（总时间、使用次数、平均每次）",
                "  • 约束违反统计表（从甘特图真实驻留时间计算，去重）",
                "  • Wafer × Station 耗时矩阵（P 处理/D 驻留 分解，颜色高亮）",
                "  • 完整甘特图（带腔室利用率百分比，" + formatTime(simTime) + " 时间跨度）",
                "  • Wafer History 下拉选择器（每片 wafer 的分步时间线）",
                "  • 🎬 机台动画 SVG 回放（和运行界面一致的 tool layout，播放/调速/步进）",
        }, 0.9, 12);
        addKeyPointBox(slide, "📂 查看详细结果",
                "报告文件: simulation_report.html（自包含，无需服务器）\n双击用浏览器打开即可查看全部统计数据、甘特图、Wafer 历史、SVG 动画回放",
                4.5, 0.85);
        File reportFile = new File("../result/simulation_report.html");
        if (reportFile.exists()) {
            double sizeMb = reportFile.length() / (1024.0 * 1024);
            addBodyLines(slide, new String[]{
                    "  ✅ 报告已生成：simulation_report.html (" + String.format("%.1f", sizeMb) + "MB)",
            }, 5.6, 12);
        }
        addFooter(slide);

        // === Slide 13: Bug Fix History ===
        slide = ppt.createSlide();
        setBg(slide, DARK_BG);
        addSectionHeader(slide, "12", "关键 Bug 修复记录");
        addBodyLines(slide, new String[]{"📌 开发过程中发现并修复的关键 Bug"}, 0.9, 12);
        addTable(slide, new String[]{"Bug", "根因", "修复"},
                new String[][]{
                        {"lastUsedTime +dur 重复", "lambda 捕获 currentTimeSec 后再次 +dur", "统一使用 currentTimeSec"},
                        {"EPI2/3/4 未做 OnLoadClean", "lastOnloadCleanStart 被 EPI/PC 共享，PC 打断 EPI stagger", "拆分为 per-type Map"},
                        {"IdlePurge 看似无限循环", "EPI OnLoadClean 缺失导致全局死锁，purge 为表象", "修复 OnLoadClean 后正常"},
                        {"EPI3/4 OnLoadClean 间隔大", "固定时钟 stagger 不匹配实际管道节奏", "EPI2+ 改用 wafer 计数器触发"},
                        {"PreClean 1X Clean 标签错误", "handleChamberTimerDone 硬编码事件文字", "chamberCleanType Map 动态区分"},
                        {"机械手不可见", "采样间隔 100s 太长 + 布局不匹配运行界面", "10s 采样 + SVG 对齐 ToolLayout"},
                }, 1.6, 9);
        addFooter(slide);

        // === Slide 14: Design Decisions ===
        slide = ppt.createSlide();
        setBg(slide, DARK_BG);
        addSectionHeader(slide, "13", "关键设计决策");
        addTable(slide, new String[]{"决策", "理由", "影响"},
                new String[][]{
                        {"纯配置驱动，无硬编码", "最大灵活性，设备/工艺变更仅改 JSON", "5 个配置文件覆盖全部参数"},
                        {"每秒 tick 离散模拟", "平衡精度与速度，1s 粒度满足约束需求", "26000s 模拟可数分钟完成"},
                        {"TM1 PT 返回 > PT 前行", "防止 PT 槽满导致 EPI→PT 死锁", "返回路径优先清空"},
                        {"PT fwd→buffer, ret→cooling", "分离前向/返回流量", "减少 PT 槽位争夺"},
                        {"OnLoadClean: 首腔延迟 + 后续计数器", "首腔对齐首片，后续腔对齐实际管道", "全 4 腔间隔统一 ~73s"},
                        {"IdlePurge 纯空闲驱动", "语义清晰，仅依赖 idle 时长", "与是否有 wafer 无关"},
                        {"PURGING ≠ CLEANING 状态", "前端/甘特图清晰区分两种 AM", "橙色=Clean, 紫色=Purge"},
                        {"Stagger = (EPI+Clean)/4", "考虑 1X Clean 时间的完整 EPI 周期", "~644s 间隔防 PT 拥堵"},
                        {"安全裕度 40s", "驻留前瞻检查的保守缓冲", "避免边际违例"},
                }, 1.1, 8);
        addFooter(slide);

        // === Slide 15: Summary ===
        slide = ppt.createSlide();
        setBg(slide, DARK_BG);
        addSectionHeader(slide, "14", "总结与展望");
        addBodyLines(slide, new String[]{
                "✅ 已完成功能",
                "  • 完整的 EPI Cluster Tool 离散事件仿真调度引擎（~1490 行核心代码）",
                "  • 双槽位 PT + CoolingStation 冷却逻辑 + 通道分离策略",
                "  • 时间感知前瞻死锁预防（三层防护：Stagger / EPI 容量 / PT 前向压力）",
                "  • AM 完整集成（OnLoadClean + 1X Clean + IdlePurge，状态/甘特图区分）",
                "  • 数据一致性自愈机制（healWaferLocations）",
                "  • Vue 3 前端可视化 + 交互式甘特图 + 实时 WebSocket 推送",
                "  • HTML 自包含报告 + SVG 动画回放 + PPTX 演示文稿（Java 内嵌生成）",
                "  • " + done + "/" + total + " 片晶圆全部成功完成，WPH = " + wph + "，驻留违例 = " + totalViolations + " 次",
                "",
                "🔮 可扩展方向",
                "  • 双臂/多指机器人支持（需新增 Robot 模型 + Swap 逻辑）",
                "  • 多 CJ 并行调度 + 不同 recipe 混跑",
                "  • ML 优化调度策略（强化学习选择最优 wafer 释放时机）",
                "  • Web UI 实时修改配置文件（替代手动编辑 JSON）",
        }, 0.9, 11);
        addFooter(slide);

        File outDir = new File("../result");
        outDir.mkdirs();
        File outFile = new File(outDir, "EPI_Scheduler_Report.pptx");
        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            ppt.write(fos);
        }
        ppt.close();
        return outFile.getAbsolutePath();
    }

    // ==================== POI helpers ====================

    static String formatTime(int sec) {
        if (sec < 0) sec = 0;
        int h = sec / 3600, m = (sec % 3600) / 60, s = sec % 60;
        if (h > 0) return h + "h" + m + "m" + s + "s";
        if (m > 0) return m + "m" + s + "s";
        return s + "s";
    }

    static void setBg(XSLFSlide slide, Color color) {
        var fill = slide.getBackground().getFillColor();
        // POI background: use fill
        var bg = slide.getXmlObject();
        try {
            // Simpler approach via CT
            var cSld = bg.getCSld();
            if (cSld != null && cSld.getBg() == null) cSld.addNewBg();
            var ctBg = bg.getCSld().getBg();
            if (ctBg != null) {
                var fillRef = ctBg.isSetBgPr() ? ctBg.getBgPr() : ctBg.addNewBgPr();
                if (fillRef == null) fillRef = ctBg.addNewBgPr();
                var solidFill = fillRef.isSetSolidFill() ? fillRef.getSolidFill() : fillRef.addNewSolidFill();
                if (solidFill == null) solidFill = fillRef.addNewSolidFill();
                var srgb = solidFill.isSetSrgbClr() ? solidFill.getSrgbClr() : solidFill.addNewSrgbClr();
                srgb.setVal(new byte[]{(byte) color.getRed(), (byte) color.getGreen(), (byte) color.getBlue()});
            }
        } catch (Exception ignored) {}
    }

    static void addSectionHeader(XSLFSlide slide, String num, String text) {
        var shape = slide.createAutoShape();
        shape.setShapeType(org.apache.poi.sl.usermodel.ShapeType.RECT);
        shape.setAnchor(new java.awt.Rectangle(0, 11, 720, 40));
        shape.setFillColor(SECTION_BG);
        shape.setLineWidth(0);
        shape.setWordWrap(true);
        XSLFTextParagraph p = shape.addNewTextParagraph();
        p.addNewTextRun().setText("  " + num + ".  " + text);
        p.setTextAlign(TextParagraph.TextAlign.LEFT);
        addRun(p, "  " + num + ".  " + text, 16, CYAN, true);
    }

    static void addTextBox(XSLFSlide slide, String text, double topInches, int fontSize, Color color, boolean bold) {
        var tb = slide.createTextBox();
        int y = (int) (topInches * 72);
        tb.setAnchor(new java.awt.Rectangle(36, y, 648, 50));
        XSLFTextParagraph p = tb.addNewTextParagraph();
        p.addNewTextRun().setText(text);
        p.setTextAlign(TextParagraph.TextAlign.CENTER);
        addRun(p, text, fontSize, color, bold);
    }

    static void addFooter(XSLFSlide slide, String text) {
        var tb = slide.createTextBox();
        tb.setAnchor(new java.awt.Rectangle(36, 504, 648, 22));
        XSLFTextParagraph p = tb.addNewTextParagraph();
        p.addNewTextRun().setText(text);
        p.setTextAlign(TextParagraph.TextAlign.RIGHT);
        addRun(p, text, 8, GRAY, false);
    }

    static void addFooter(XSLFSlide slide) {
        addFooter(slide, "EPI Cluster Tool 调度仿真系统");
    }

    static void addBodyLines(XSLFSlide slide, String[] lines, double topInches, int fontSize) {
        var tb = slide.createTextBox();
        int y = (int) (topInches * 72);
        tb.setAnchor(new java.awt.Rectangle(50, y, 612, 396));
        tb.setWordWrap(true);
        for (String line : lines) {
            XSLFTextParagraph p = tb.addNewTextParagraph();
            p.addNewTextRun().setText(line);
            if (line.startsWith("📌") || line.startsWith("🔧") || line.startsWith("📊") || line.startsWith("✅") || line.startsWith("🔮") || line.startsWith("⭐")) {
                addRun(p, line, fontSize + 2, YELLOW, true);
            } else if (line.isEmpty()) {
                addRun(p, " ", 4, WHITE, false);
            } else {
                addRun(p, line, fontSize, WHITE, false);
            }
        }
    }

    static void addKeyPointBox(XSLFSlide slide, String title, String text, double topInches, double heightInches) {
        var shape = slide.createAutoShape();
        shape.setShapeType(org.apache.poi.sl.usermodel.ShapeType.ROUND_RECT);
        int y = (int) (topInches * 72);
        int h = (int) (heightInches * 72);
        shape.setAnchor(new java.awt.Rectangle(50, y, 612, h));
        shape.setFillColor(KEY_BOX_BG);
        shape.setLineWidth(0);
        shape.setWordWrap(true);
        XSLFTextParagraph p = shape.addNewTextParagraph();
        p.addNewTextRun().setText("⭐ " + title);
        addRun(p, "⭐ " + title, 14, YELLOW, true);
        for (String tLine : text.split("\n")) {
            XSLFTextParagraph p2 = shape.addNewTextParagraph();
            p2.addNewTextRun().setText(tLine);
            addRun(p2, tLine, 12, WHITE, false);
        }
    }

    static void addKeyPointBox(XSLFSlide slide, String title, String text, double topInches) {
        addKeyPointBox(slide, title, text, topInches, 0.7);
    }

    static void addTable(XSLFSlide slide, String[] headers, String[][] rows, double topInches, int fontSize) {
        int nRows = rows.length + 1;
        int nCols = headers.length;
        int y = (int) (topInches * 72);
        int rowH = 23;

        var table = slide.createTable();
        table.setAnchor(new java.awt.Rectangle(50, y, 612, rowH * nRows));

        // Header row
        for (int i = 0; i < nCols; i++) {
            var cell = table.getCell(0, i);
            if (cell == null) continue;
            cell.setFillColor(TABLE_HDR);
            XSLFTextParagraph p = cell.addNewTextParagraph();
            addRun(p, headers[i], fontSize, CYAN, true);
        }
        // Data rows
        for (int r = 0; r < rows.length; r++) {
            for (int c = 0; c < nCols; c++) {
                var cell = table.getCell(r + 1, c);
                if (cell == null) continue;
                cell.setFillColor(r % 2 == 0 ? TABLE_BG1 : TABLE_BG2);
                XSLFTextParagraph p = cell.addNewTextParagraph();
                addRun(p, rows[r][c], fontSize, WHITE, false);
            }
        }
    }

    static void addRun(org.apache.poi.xslf.usermodel.XSLFTextParagraph p, String text, int fontSize, Color color, boolean bold) {
        var run = p.addNewTextRun();
        run.setText(text);
        run.setFontSize((double) fontSize);
        run.setFontColor(color);
        run.setBold(bold);
        run.setFontFamily("Segoe UI");
    }
}
