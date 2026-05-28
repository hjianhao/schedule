package com.epi.scheduler.service;

import com.epi.scheduler.engine.SchedulerEngine;
import com.epi.scheduler.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ReportService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    static String formatTime(int sec) {
        if (sec < 0) sec = 0;
        int h = sec / 3600, m = (sec % 3600) / 60, s = sec % 60;
        if (h > 0) return h + "h" + m + "m" + s + "s";
        if (m > 0) return m + "m" + s + "s";
        return s + "s";
    }

    static String formatShort(int sec) {
        if (sec < 0) return "-";
        if (sec == 0) return "0s";
        int m = sec / 60, s = sec % 60;
        if (m > 0) return m + "m" + s + "s";
        return s + "s";
    }

    static int[] waferSortKey(String wid) {
        if (wid.contains(".")) {
            String[] parts = wid.substring(1).split("\\.");
            return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
        }
        return new int[]{0, Integer.parseInt(wid.substring(1))};
    }

    static Comparator<String> waferComparator() {
        return (a, b) -> {
            int[] ka = waferSortKey(a), kb = waferSortKey(b);
            int c = Integer.compare(ka[0], kb[0]);
            return c != 0 ? c : Integer.compare(ka[1], kb[1]);
        };
    }

    // ==================== Robot transfer time helpers ====================

    static class Rxfer {
        final Map<String, int[]> ops = new LinkedHashMap<>(); // opKey -> [rotate+place, full]
        final int defXfer;

        Rxfer(DeviceConfig.RobotConfig rob) {
            int def = rob.getRotateTimeSec() + rob.getPlaceTimeSec();
            this.defXfer = def > 0 ? def : 9;
            if (rob.getOperations() != null) {
                for (var e : rob.getOperations().entrySet()) {
                    var op = e.getValue();
                    int pt = (int) Math.ceil(op.getPickTimeSec());
                    int rt = (int) Math.ceil(op.getRotateTimeSec());
                    int plt = (int) Math.ceil(op.getPlaceTimeSec());
                    ops.put(e.getKey(), new int[]{rt + plt, pt + rt + plt});
                }
            }
        }

        int xfer(String opKey) {
            int[] v = ops.get(opKey);
            return v != null ? v[0] : defXfer;
        }

        int total(String opKey) {
            int[] v = ops.get(opKey);
            return v != null ? v[1] : defXfer;
        }
    }

    static void buildRxferMap(Map<String, Rxfer> out, DeviceConfig device) {
        for (var tm : device.getTransferModules())
            for (var rob : tm.getRobots())
                out.put(rob.getId(), new Rxfer(rob));
    }

    static int getEntryXfer(Map<String, Rxfer> rx, String etype) {
        String[][] m = {{"PRECLEAN","Robot1","PRECLEAN_TO_PT","xfer"},
                        {"PASSTHROUGH","Robot2","PT_TO_EPI","total"},
                        {"PT_RETURN","Robot1","PT_TO_LL","xfer"},
                        {"EPI","Robot2","EPI_TO_PT","xfer"}};
        for (String[] r : m) {
            if (r[0].equals(etype)) {
                Rxfer x = rx.get(r[1]);
                if (x == null) return 9;
                return "total".equals(r[3]) ? x.total(r[2]) : x.xfer(r[2]);
            }
        }
        return 9;
    }

    static int getTotalXfer(Map<String, Rxfer> rx, String etype) {
        String[][] m = {{"PRECLEAN","Robot1","PRECLEAN_TO_PT"},
                        {"PASSTHROUGH","Robot2","PT_TO_EPI"},
                        {"PT_RETURN","Robot1","PT_TO_LL"},
                        {"EPI","Robot2","EPI_TO_PT"}};
        for (String[] r : m) {
            if (r[0].equals(etype)) {
                Rxfer x = rx.get(r[1]);
                return x != null ? x.total(r[2]) : 9;
            }
        }
        return 9;
    }

    static String ganttColor(GanttEntry e, Map<String, String[]> slotColors) {
        String loc = e.getLocation();
        if (slotColors.containsKey(loc))
            return "PT_RETURN".equals(e.getType()) ? slotColors.get(loc)[1] : slotColors.get(loc)[0];
        return switch (loc) {
            case "LL1", "LL2" -> "#2196F3";
            case "PreClean1", "PreClean2" -> "#FF9800";
            case "EPI1" -> "#4CAF50";
            case "EPI2" -> "#66BB6A";
            case "EPI3" -> "#43A047";
            case "EPI4" -> "#388E3C";
            default -> "LOADLOCK_RET".equals(e.getType()) ? "#9C27B0" :
                      "LOADLOCK".equals(e.getType()) ? "#2196F3" : "#666";
        };
    }

    // ==================== Main ====================

    public String generateHtml(SchedulerEngine engine, ConfigService configService) throws IOException {
        var state = engine.getSnapshot();
        var gantt = engine.getGanttData();
        var events = engine.getFullEventLog();
        var replay = engine.getReplaySnapshots();
        var device = configService.getDeviceConfig();
        var schedule = configService.getScheduleConfig();
        var amConfig = configService.getAmConfig();

        int simTime = state.getCurrentTimeSec();
        int completed = state.getCompletedWafers();
        int total = state.getTotalWafers();
        double wph = state.getCurrentWPH();

        // Filter entries
        var waferEntries = new ArrayList<>(gantt.stream()
                .filter(e -> !"BATCH".equals(e.getWaferId()) && !e.getWaferId().startsWith("BATCH")
                        && !e.getWaferId().startsWith("CLEAN_")).toList());
        var batchEntries = gantt.stream().filter(e -> "BATCH".equals(e.getWaferId()) || e.getWaferId().startsWith("BATCH")).toList();
        var cleanEntries = gantt.stream().filter(e -> "CLEAN".equals(e.getType()) || "PURGE".equals(e.getType())).toList();

        // Robot transfer map
        Map<String, Rxfer> rx = new LinkedHashMap<>();
        buildRxferMap(rx, device);

        // PT slot colors
        Set<String> coolingSlots = new LinkedHashSet<>();
        Map<String, String[]> slotColors = new LinkedHashMap<>();
        for (var pt : device.getPassthroughs()) {
            for (int s = 0; s < pt.getSlots(); s++) {
                String id = pt.getId() + "_S" + s;
                if (pt.getCoolingStationSlot() != null && s == pt.getCoolingStationSlot()) {
                    coolingSlots.add(id);
                    slotColors.put(id, new String[]{"#42A5F5", "#1565C0"});
                } else {
                    slotColors.put(id, new String[]{"#FFEB3B", "#F9A825"});
                }
            }
        }

        // ====== Build HTML ======
        StringBuilder sb = new StringBuilder();
        sb.append("""
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1.0">
<title>EPI Cluster Tool 模拟报告</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:Segoe UI,Microsoft YaHei,sans-serif;background:#1a1a2e;color:#e0e0e0;padding:20px}
h1{text-align:center;color:#00d4ff;margin-bottom:8px;font-size:26px}
h2{color:#00d4ff;border-bottom:2px solid #333;padding-bottom:6px;margin:22px 0 12px;font-size:18px}
.subtitle{text-align:center;color:#888;margin-bottom:24px;font-size:13px}
.sg{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:14px;margin-bottom:24px}
.sc{background:#16213e;border:1px solid #333;border-radius:10px;padding:18px;text-align:center}
.sc .v{font-size:32px;font-weight:bold;color:#00d4ff}
.sc .l{font-size:12px;color:#999;margin-top:4px}
.sc.g .v{color:#4CAF50}
.sc.o .v{color:#FF9800}
.sc.p .v{color:#E91E63}
table{width:100%;border-collapse:collapse;margin-bottom:18px;background:#16213e;border-radius:8px;overflow:hidden}
th,td{padding:6px 10px;text-align:left;border-bottom:1px solid #2a2a4a;font-size:12px;white-space:nowrap}
th{background:#0f3460;color:#00d4ff;font-weight:600;position:sticky;top:0;z-index:1}
.mx{overflow-x:auto;max-height:500px;overflow-y:auto;background:#16213e;border-radius:10px;border:1px solid #333;margin-bottom:18px}
.mx table{margin-bottom:0}
.mx th:first-child,.mx td:first-child{position:sticky;left:0;background:#16213e;z-index:2;font-weight:bold}
.mx tr:nth-child(even) td{background:#1a2744}
.mx tr:nth-child(even) td:first-child{background:#1a2744}
.gc{overflow-x:auto;background:#16213e;border-radius:10px;padding:14px;border:1px solid #333}
.gchart{position:relative;min-width:1100px}
.gr{display:flex;align-items:center;height:26px;margin:1px 0;background:#0d1117;border-radius:4px}
.gl{width:120px;min-width:120px;font-size:11px;color:#aaa;padding-left:6px;font-family:monospace;display:flex;justify-content:space-between;padding-right:8px}
.gu{color:#00d4ff;font-weight:bold;font-size:10px}
.ga{flex:1;position:relative;height:100%}
.gb{position:absolute;height:20px;top:3px;border-radius:3px;font-size:9px;color:#111;display:flex;align-items:center;justify-content:center;overflow:hidden;white-space:nowrap;min-width:2px}
.gtr{display:flex;font-size:10px;color:#666;margin-left:120px}
.gtr span{flex:1;text-align:left;overflow:hidden}
.legend{display:flex;gap:14px;flex-wrap:wrap;margin:10px 0 16px}
.li{display:flex;align-items:center;gap:5px;font-size:11px}
.lc{width:13px;height:13px;border-radius:3px}
footer{text-align:center;color:#555;margin-top:36px;font-size:11px}
</style>
</head>
<body>
""");

        // Title
        sb.append("<h1>EPI Cluster Tool 模拟报告</h1>\n");
        sb.append("<p class=\"subtitle\">设备: ").append(device.getEquipmentName())
          .append(" (").append(device.getEquipmentId()).append(") | 模拟时长: ")
          .append(formatTime(simTime)).append(" | 状态: ").append(state.getStatus()).append("</p>\n");

        // KPI
        int avgCycle = calcAvgCycle(waferEntries);
        sb.append("<h2>核心指标</h2>\n<div class=\"sg\">\n");
        sb.append("  <div class=\"sc g\"><div class=\"v\">").append(completed).append("/").append(total)
          .append("</div><div class=\"l\">完成 Wafer 数</div></div>\n");
        sb.append("  <div class=\"sc\"><div class=\"v\">").append(String.format("%.1f", wph))
          .append("</div><div class=\"l\">WPH (Wafer Per Hour)</div></div>\n");
        sb.append("  <div class=\"sc o\"><div class=\"v\">").append(formatTime(simTime))
          .append("</div><div class=\"l\">总模拟时间</div></div>\n");
        sb.append("  <div class=\"sc p\"><div class=\"v\">").append(formatTime(avgCycle))
          .append("</div><div class=\"l\">平均单 Wafer 周期</div></div>\n");
        sb.append("</div>\n");

        // Recipe/Config table
        sb.append("<h2>工艺参数</h2>\n<table>\n<tr><th>项目</th><th>工艺时间</th><th>最大驻留时间</th></tr>\n");
        for (var e : schedule.getRecipes().entrySet())
            sb.append("<tr><td>").append(e.getKey()).append("</td><td>").append(e.getValue().getAvgProcessTimeSec())
              .append("s</td><td>").append(e.getValue().getMaxDwellTimeSec()).append("s</td></tr>\n");
        var timing = schedule.getTiming();
        sb.append("<tr><td>LL Pump</td><td>").append(timing.getLoadlockPumpTimeSec()).append("s</td><td>-</td></tr>\n");
        sb.append("<tr><td>LL Vent</td><td>").append(timing.getLoadlockVentTimeSec()).append("s</td><td>-</td></tr>\n");
        sb.append("<tr><td>LL Load/Unload</td><td>").append(timing.getLoadlockLoadTimeSec()).append("s/")
          .append(timing.getLoadlockUnloadTimeSec()).append("s</td><td>-</td></tr>\n");
        sb.append("<tr><td>PT Transfer</td><td>").append(timing.getPassthroughTransferTimeSec()).append("s</td><td>-</td></tr>\n");
        sb.append("<tr><td>CoolingStation</td><td>").append(timing.getCoolingStationCoolTimeSec()).append("s</td><td>-</td></tr>\n");
        appendRobotTimingRows(sb, device);
        sb.append("</table>\n");

        // Chamber usage
        sb.append("<h2>腔室使用时间</h2>\n<table>\n<tr><th>腔室</th><th>总使用时间</th><th>使用次数</th><th>平均每次</th></tr>\n");
        appendUsageRows(sb, waferEntries, rx, coolingSlots, simTime);
        sb.append("</table>\n");

        // Violations
        sb.append("<h2>约束违反统计</h2>\n");
        appendViolationTable(sb, gantt, schedule, amConfig, rx);
        sb.append("\n");

        // Wafer x Station matrix
        sb.append("<h2>Wafer x 工站 耗时矩阵</h2>\n<div class=\"mx\">\n<table>\n");
        appendMatrixHtml(sb, waferEntries, schedule, rx, coolingSlots, simTime);
        sb.append("</table>\n</div>\n");

        // Gantt chart
        sb.append("<h2>完整甘特图</h2>\n");
        appendLegend(sb, slotColors, coolingSlots);
        sb.append("<div class=\"gc\"><div class=\"gchart\">\n<div class=\"gtr\">");
        appendRuler(sb, simTime);
        sb.append("</div>\n");
        appendGanttRows(sb, waferEntries, cleanEntries, batchEntries, rx, slotColors, simTime);
        sb.append("</div></div>\n");

        // Replay
        sb.append("<h2>机台动画回放</h2>\n");
        appendReplaySection(sb, replay, coolingSlots, total);

        // Wafer history
        sb.append("<h2>Wafer History</h2>\n");
        sb.append("<div style=\"display:flex;align-items:center;gap:12px;margin:10px 0\">\n");
        sb.append("  <select id=\"waferSelect\" onchange=\"showWaferHistory()\" style=\"background:#16213e;color:#00d4ff;border:1px solid #333;padding:6px 10px;border-radius:4px;font-size:13px\">\n");
        sb.append("    <option value=\"\">-- 选择 Wafer --</option>\n");
        appendWaferHistory(sb, waferEntries, device, schedule, simTime);
        sb.append("  </select>\n</div>\n");
        sb.append("<div id=\"waferHistoryTable\" style=\"max-height:500px;overflow:auto\">\n");
        sb.append("  <p style=\"color:#888\">选择上方 Wafer 查看完整调度历史</p>\n</div>\n\n");

        sb.append("<footer>EPI Scheduler v1.0 | 报告自动生成 | 总事件: ").append(events.size()).append(" 条</footer>\n");
        sb.append("</body>\n</html>");

        File outFile = new File("../simulation_report.html");
        try (FileWriter fw = new FileWriter(outFile)) {
            fw.write(sb.toString());
        }
        return outFile.getAbsolutePath();
    }

    // ==================== Section builders ====================

    static int calcAvgCycle(List<GanttEntry> waferEntries) {
        Map<String, int[]> cycles = new LinkedHashMap<>();
        for (var e : waferEntries) {
            cycles.computeIfAbsent(e.getWaferId(), k -> new int[]{Integer.MAX_VALUE, 0});
            int[] c = cycles.get(e.getWaferId());
            c[0] = Math.min(c[0], e.getStartTimeSec());
            int end = e.getEndTimeSec() > 0 ? e.getEndTimeSec() : 0;
            c[1] = Math.max(c[1], end);
        }
        int total = 0;
        for (int[] c : cycles.values()) total += c[1] - c[0];
        return total / Math.max(cycles.size(), 1);
    }

    static void appendRobotTimingRows(StringBuilder sb, DeviceConfig device) {
        for (var tm : device.getTransferModules())
            for (var rob : tm.getRobots())
                if (rob.getOperations() != null)
                    for (var e : rob.getOperations().entrySet()) {
                        var op = e.getValue();
                        int p = (int) Math.ceil(op.getPickTimeSec()), r = (int) Math.ceil(op.getRotateTimeSec()), pl = (int) Math.ceil(op.getPlaceTimeSec());
                        sb.append("<tr><td>").append(rob.getId()).append(" ").append(e.getKey())
                          .append("</td><td>pick ").append(p).append("s + rot ").append(r).append("s + place ").append(pl)
                          .append("s = ").append(p + r + pl).append("s</td><td>-</td></tr>\n");
                    }
        var atm = device.getEfem() != null ? device.getEfem().getAtmRobot() : null;
        if (atm != null) {
            for (String key : new String[]{"foupToAligner", "alignerToLL"}) {
                var op = "foupToAligner".equals(key) ? atm.getFoupToAligner() : atm.getAlignerToLL();
                if (op != null) {
                    int p = (int) Math.ceil(op.getPickTimeSec()), r = (int) Math.ceil(op.getRotateTimeSec()), pl = (int) Math.ceil(op.getPlaceTimeSec());
                    sb.append("<tr><td>ATM1 ").append(key).append("</td><td>pick ").append(p).append("s + rot ")
                      .append(r).append("s + place ").append(pl).append("s = ").append(p + r + pl).append("s</td><td>-</td></tr>\n");
                }
            }
        }
    }

    static void appendUsageRows(StringBuilder sb, List<GanttEntry> waferEntries, Map<String, Rxfer> rx,
                                Set<String> coolingSlots, int simTime) {
        for (String ctype : new String[]{"PRECLEAN", "EPI"}) {
            var entries = waferEntries.stream().filter(e -> ctype.equals(e.getType())).toList();
            if (!entries.isEmpty()) {
                int total = 0;
                for (var e : entries) {
                    int end = e.getEndTimeSec() > 0 ? e.getEndTimeSec() : simTime;
                    total += Math.max(0, end - e.getStartTimeSec() - getEntryXfer(rx, e.getType()));
                }
                sb.append("<tr><td>").append(ctype).append("</td><td>").append(formatTime(total))
                  .append("</td><td>").append(entries.size()).append("</td><td>")
                  .append(formatTime(total / Math.max(entries.size(), 1))).append("</td></tr>\n");
            }
        }
        for (String ptId : new String[]{"PT1_S0", "PT1_S1", "PT2_S0", "PT2_S1"}) {
            var entries = waferEntries.stream().filter(e -> ptId.equals(e.getLocation())).toList();
            int total = 0;
            for (var e : entries) {
                int end = e.getEndTimeSec() > 0 ? e.getEndTimeSec() : simTime;
                total += Math.max(0, end - e.getStartTimeSec() - getEntryXfer(rx, e.getType()));
            }
            String cs = coolingSlots.contains(ptId) ? " ❄" : "";
            int avg = entries.isEmpty() ? 0 : total / entries.size();
            sb.append("<tr><td>").append(ptId).append(cs).append("</td><td>").append(formatTime(total))
              .append("</td><td>").append(entries.size()).append("</td><td>").append(formatTime(avg)).append("</td></tr>\n");
        }
    }

    static void appendViolationTable(StringBuilder sb, List<GanttEntry> gantt, ScheduleConfig schedule,
                                     AmConfig amConfig, Map<String, Rxfer> rx) {
        Map<String, int[]> stats = new LinkedHashMap<>();
        Map<String, String> maxWafer = new LinkedHashMap<>(), maxChamber = new LinkedHashMap<>();
        var limits = Map.of("PreClean", 120, "EPI", 100, "PT", 300);
        var recipes = schedule.getRecipes();
        var timing = schedule.getTiming();
        int pcProc = recipes.containsKey("PRECLEAN") ? recipes.get("PRECLEAN").getAvgProcessTimeSec() : 280;
        int epiProc = recipes.containsKey("EPI") ? recipes.get("EPI").getAvgProcessTimeSec() : 2120;
        int coolTime = timing.getCoolingStationCoolTimeSec();

        for (var e : gantt) {
            String wid = e.getWaferId();
            if (!wid.startsWith("W")) continue;
            String etype = e.getType();
            int end = e.getEndTimeSec() > 0 ? e.getEndTimeSec() : 0;
            int dur = Math.max(0, end - e.getStartTimeSec());
            int xfer = getTotalXfer(rx, etype);
            int dwell = 0; String ctype = null;
            switch (etype) {
                case "PRECLEAN": ctype = "PreClean"; dwell = Math.max(0, dur - xfer - pcProc); break;
                case "EPI": ctype = "EPI"; dwell = Math.max(0, dur - xfer - epiProc); break;
                case "PASSTHROUGH": ctype = "PT"; dwell = Math.max(0, dur - xfer); break;
                case "PT_RETURN": ctype = "PT"; dwell = Math.max(0, dur - xfer - coolTime); break;
            }
            if (ctype == null) continue;
            int limit = limits.get(ctype);
            if (dwell > limit) {
                int[] s = stats.computeIfAbsent(ctype, k -> new int[2]);
                s[0]++;
                if (dwell > s[1]) { s[1] = dwell; maxWafer.put(ctype, wid); maxChamber.put(ctype, e.getLocation()); }
            }
        }

        // 1X Clean gap
        int[] cleanGap = new int[2];
        String gapChamber = "", gapWafer = "";
        double gapLimit = amConfig != null && amConfig.findPreProcessTask("EPI") != null
                ? amConfig.findPreProcessTask("EPI").getGapTimeSec() : 0;
        int robotPtToEpi = getTotalXfer(rx, "PASSTHROUGH");
        Map<String, List<GanttEntry>> chamberEntries = new LinkedHashMap<>();
        for (var e : gantt) chamberEntries.computeIfAbsent(e.getLocation(), k -> new ArrayList<>()).add(e);
        for (var ce : chamberEntries.entrySet()) {
            var entries = ce.getValue();
            entries.sort(Comparator.comparingInt(GanttEntry::getStartTimeSec));
            int cleanCount = 0;
            for (int i = 0; i < entries.size() - 1; i++) {
                if ("CLEAN".equals(entries.get(i).getType()) && "EPI".equals(entries.get(i + 1).getType())) {
                    cleanCount++;
                    if (cleanCount == 1) continue;
                    int cleanEnd = entries.get(i).getEndTimeSec() > 0 ? entries.get(i).getEndTimeSec() : entries.get(i).getStartTimeSec();
                    int waferPlace = entries.get(i + 1).getStartTimeSec();
                    int gap = Math.max(0, waferPlace - cleanEnd - robotPtToEpi);
                    if (gap > gapLimit) {
                        cleanGap[0]++;
                        if (gap > cleanGap[1]) { cleanGap[1] = gap; gapChamber = ce.getKey(); gapWafer = entries.get(i + 1).getWaferId(); }
                    }
                }
            }
        }

        sb.append("<table>\n<tr><th>约束类型</th><th>限制值</th><th>违反次数</th><th>最大违反值</th><th>最大违反位置</th></tr>\n");
        for (String ctype : new String[]{"PreClean", "EPI", "PT"}) {
            int limit = limits.get(ctype);
            int[] s = stats.get(ctype);
            if (s != null && s[0] > 0)
                sb.append("<tr><td>").append(ctype).append(" Dwell</td><td>").append(limit)
                  .append("s</td><td style='color:#FF9800'>").append(s[0])
                  .append("</td><td style='color:#E91E63'>").append(formatTime(s[1])).append(" (").append(s[1])
                  .append("s)</td><td>").append(maxWafer.getOrDefault(ctype, "")).append(" @ ")
                  .append(maxChamber.getOrDefault(ctype, "")).append("</td></tr>\n");
            else
                sb.append("<tr><td>").append(ctype).append(" Dwell</td><td>").append(limit)
                  .append("s</td><td style='color:#4CAF50'>0 ✓</td><td>-</td><td>-</td></tr>\n");
        }
        if (cleanGap[0] > 0)
            sb.append("<tr><td>1X Clean Gap</td><td>").append((int) gapLimit)
              .append("s</td><td style='color:#FF9800'>").append(cleanGap[0])
              .append("</td><td style='color:#E91E63'>").append(formatTime(cleanGap[1])).append(" (").append(cleanGap[1])
              .append("s)</td><td>").append(gapWafer).append(" @ ").append(gapChamber).append("</td></tr>\n");
        else
            sb.append("<tr><td>1X Clean Gap</td><td>").append((int) gapLimit)
              .append("s</td><td style='color:#4CAF50'>0 ✓</td><td>-</td><td>-</td></tr>\n");
        sb.append("</table>");
    }

    static void appendMatrixHtml(StringBuilder sb, List<GanttEntry> waferEntries, ScheduleConfig schedule,
                                 Map<String, Rxfer> rx, Set<String> coolingSlots, int simTime) {
        var recipes = schedule.getRecipes();
        var timing = schedule.getTiming();
        int pcRecipe = recipes.containsKey("PRECLEAN") ? recipes.get("PRECLEAN").getAvgProcessTimeSec() : 280;
        int epiRecipe = recipes.containsKey("EPI") ? recipes.get("EPI").getAvgProcessTimeSec() : 2120;
        int coolTime = timing.getCoolingStationCoolTimeSec();

        // wafer -> location -> data
        Map<String, Map<String, int[]>> waferMatrix = new LinkedHashMap<>();
        for (var w : waferEntries) {
            String wid = w.getWaferId(), loc = w.getLocation(), etype = w.getType();
            int end = w.getEndTimeSec() > 0 ? w.getEndTimeSec() : simTime;
            int xfer = getEntryXfer(rx, etype);
            int dur = Math.max(0, end - w.getStartTimeSec() - xfer);
            int proc = 0;
            switch (etype) {
                case "PRECLEAN": proc = pcRecipe; break;
                case "EPI": proc = epiRecipe; break;
                case "PT_RETURN": proc = coolTime; break;
            }
            int dwell = Math.max(0, dur - proc);
            waferMatrix.computeIfAbsent(wid, k -> new LinkedHashMap<>());
            var wd = waferMatrix.get(wid);
            wd.computeIfAbsent(loc, k -> new int[6]);
            int[] d = wd.get(loc);
            if ("PT_RETURN".equals(etype)) { d[3] += dur; d[4] += proc; d[5] += dwell; }
            else { d[0] += dur; d[1] += proc; d[2] += dwell; }
        }

        List<String> sortedWafers = new ArrayList<>(waferMatrix.keySet());
        sortedWafers.sort(waferComparator());
        String[] ptCols = {"PT1_S0", "PT1_S1", "PT2_S0", "PT2_S1"};

        sb.append("<tr><th>Wafer</th><th>PreClean</th>");
        for (String c : ptCols) sb.append("<th>").append(c).append("<br>fwd").append(coolingSlots.contains(c) ? "❄" : "").append("</th>");
        sb.append("<th>EPI</th>");
        for (String c : ptCols) sb.append("<th>").append(c).append("<br>ret").append(coolingSlots.contains(c) ? "❄" : "").append("</th>");
        sb.append("<th>Total</th></tr>\n");

        String[] pcCols = {"PreClean1", "PreClean2"};
        String[] epiCols = {"EPI1", "EPI2", "EPI3", "EPI4"};

        for (String wid : sortedWafers) {
            var wd = waferMatrix.get(wid);
            sb.append("<tr><td>").append(wid).append("</td>");
            int total = 0;
            // PreClean
            int pcDur = 0, pcProc = 0, pcDwell = 0;
            for (String c : pcCols) { int[] d = wd.get(c); if (d != null) { pcDur += d[0]; pcProc += d[1]; pcDwell += d[2]; } }
            total += pcDur;
            sb.append("<td>").append(formatShort(pcDur)).append("<br><small>P:").append(formatShort(pcProc)).append(" D:").append(formatShort(pcDwell)).append("</small></td>");
            // PT fwd
            for (String c : ptCols) {
                int[] d = wd.get(c); int t = d != null ? d[0] : -1;
                total += Math.max(0, t);
                if (t >= 0 && d != null) {
                    String hl = t > 300 ? " style=\"font-weight:bold;color:#FF9800\"" : "";
                    sb.append("<td").append(hl).append(">").append(formatShort(t)).append("<br><small>P:").append(formatShort(d[1])).append(" D:").append(formatShort(d[2])).append("</small></td>");
                } else sb.append("<td>-</td>");
            }
            // EPI
            int epiDur = 0, epiProc = 0, epiDwell = 0;
            for (String c : epiCols) { int[] d = wd.get(c); if (d != null) { epiDur += d[0]; epiProc += d[1]; epiDwell += d[2]; } }
            total += epiDur;
            sb.append("<td>").append(formatShort(epiDur)).append("<br><small>P:").append(formatShort(epiProc)).append(" D:").append(formatShort(epiDwell)).append("</small></td>");
            // PT ret
            for (String c : ptCols) {
                int[] d = wd.get(c); int t = d != null ? d[3] : -1;
                total += Math.max(0, t);
                if (t >= 0 && d != null)
                    sb.append("<td>").append(formatShort(t)).append("<br><small>P:").append(formatShort(d[4])).append(" D:").append(formatShort(d[5])).append("</small></td>");
                else sb.append("<td>-</td>");
            }
            sb.append("<td style=\"font-weight:bold;color:#00d4ff\">").append(formatShort(total)).append("</td></tr>\n");
        }
    }

    static void appendLegend(StringBuilder sb, Map<String, String[]> slotColors, Set<String> coolingSlots) {
        sb.append("<div class=\"legend\">");
        for (var e : slotColors.entrySet()) {
            String cs = coolingSlots.contains(e.getKey()) ? " ❄" : "";
            sb.append("<div class=\"li\"><div class=\"lc\" style=\"background:").append(e.getValue()[0])
              .append("\"></div>").append(e.getKey()).append(" fwd").append(cs).append("</div>");
            sb.append("<div class=\"li\"><div class=\"lc\" style=\"background:").append(e.getValue()[1])
              .append("\"></div>").append(e.getKey()).append(" ret").append(cs).append("</div>");
        }
        sb.append("<div class=\"li\"><div class=\"lc\" style=\"background:#FF9800\"></div>PreClean</div>");
        sb.append("<div class=\"li\"><div class=\"lc\" style=\"background:#4CAF50\"></div>EPI</div>");
        sb.append("<div class=\"li\"><div class=\"lc\" style=\"background:#FF5722\"></div>1X Clean</div>");
        sb.append("<div class=\"li\"><div class=\"lc\" style=\"background:#2196F3\"></div>LL</div>");
        sb.append("<div class=\"li\"><div class=\"lc\" style=\"background:#9C27B0\"></div>LL Ret</div>");
        sb.append("</div>\n");
    }

    static void appendRuler(StringBuilder sb, int simTime) {
        int numTicks = 10, tickInterval = simTime / numTicks;
        for (int i = 0; i <= numTicks; i++)
            sb.append("<span>").append(formatTime((int)(i * tickInterval))).append("</span>");
    }

    static void appendGanttRows(StringBuilder sb, List<GanttEntry> waferEntries, List<GanttEntry> cleanEntries,
                                List<GanttEntry> batchEntries, Map<String, Rxfer> rx,
                                Map<String, String[]> slotColors, int simTime) {
        double scale = 100.0 / Math.max(simTime, 1);
        String[] chamberOrder = {"LL1", "LL2", "ALIGNER", "PreClean1", "PreClean2",
                "PT1_S0", "PT1_S1", "PT2_S0", "PT2_S1", "EPI1", "EPI2", "EPI3", "EPI4"};

        Map<String, List<GanttEntry>> chamberMap = new LinkedHashMap<>();
        for (String ch : chamberOrder) chamberMap.put(ch, new ArrayList<>());
        for (var e : waferEntries) {
            List<GanttEntry> list = chamberMap.get(e.getLocation());
            if (list != null) list.add(e);
        }
        for (var e : cleanEntries) {
            List<GanttEntry> list = chamberMap.get(e.getLocation());
            if (list != null) list.add(e);
        }

        for (String ch : chamberOrder) {
            List<GanttEntry> entries = chamberMap.get(ch);
            for (var be : batchEntries) if (ch.equals(be.getLocation())) entries.add(be);

            int occ = 0;
            for (var e : entries) {
                int end = e.getEndTimeSec() > 0 ? e.getEndTimeSec() : simTime;
                occ += Math.max(0, end - e.getStartTimeSec() - getEntryXfer(rx, e.getType()));
            }
            int util;
            if (ch.startsWith("EPI") && !entries.isEmpty()) {
                int firstIn = entries.stream().mapToInt(GanttEntry::getStartTimeSec).min().orElse(0);
                int lastOut = entries.stream().mapToInt(e -> e.getEndTimeSec() > 0 ? e.getEndTimeSec() : simTime).max().orElse(0);
                int denom = lastOut - firstIn;
                util = denom > 0 ? Math.min(100, (int) Math.round(100.0 * occ / denom)) : 0;
            } else {
                util = Math.min(100, (int) Math.round(100.0 * occ / Math.max(simTime, 1)));
            }

            StringBuilder bars = new StringBuilder();
            for (var e : entries) {
                int start = e.getStartTimeSec();
                int end = e.getEndTimeSec() > 0 ? e.getEndTimeSec() : simTime;
                String etype = e.getType();
                int dur;
                if ("CLEAN".equals(etype) || "PURGE".equals(etype)) dur = Math.max(0, end - start);
                else dur = Math.max(0, end - start - getEntryXfer(rx, etype));
                double leftPct = start * scale, widthPct = Math.max(dur * scale, 0.5);
                String color, label, ttip;
                if ("CLEAN".equals(etype)) {
                    color = "#FF5722"; label = "🧹"; ttip = "CLEAN " + e.getLocation() + " " + formatTime(dur);
                } else if ("PURGE".equals(etype)) {
                    color = "#9C27B0"; label = "💨"; ttip = "PURGE " + e.getLocation() + " " + formatTime(dur);
                } else {
                    color = ganttColor(e, slotColors);
                    label = e.getWaferId().length() > 8 ? e.getWaferId().substring(0, 8) : e.getWaferId();
                    ttip = e.getWaferId() + " " + e.getType() + " dwell " + formatTime(dur);
                }
                bars.append("<div class=\"gb\" style=\"left:").append(String.format("%.2f", leftPct)).append("%;width:")
                    .append(String.format("%.2f", widthPct)).append("%;background:").append(color)
                    .append(";\" title=\"").append(ttip).append("\">").append(label).append("</div>");
            }
            sb.append("<div class=\"gr\"><div class=\"gl\">").append(ch).append(" <span class=\"gu\">").append(util)
              .append("%</span></div><div class=\"ga\">").append(bars).append("</div></div>\n");
        }
    }

    static void appendWaferHistory(StringBuilder sb, List<GanttEntry> waferEntries, DeviceConfig device,
                                   ScheduleConfig schedule, int simTime) {
        Map<String, List<GanttEntry>> wafers = new LinkedHashMap<>();
        for (var e : waferEntries) {
            String wid = e.getWaferId();
            if (!wid.startsWith("W")) continue;
            wafers.computeIfAbsent(wid, k -> new ArrayList<>()).add(e);
        }
        wafers.values().forEach(l -> l.sort(Comparator.comparingInt(GanttEntry::getStartTimeSec)));
        List<String> sortedIds = new ArrayList<>(wafers.keySet());
        sortedIds.sort(waferComparator());

        // Build op map
        Map<String, int[]> opMap = new LinkedHashMap<>();
        for (var tm : device.getTransferModules())
            for (var rob : tm.getRobots())
                if (rob.getOperations() != null)
                    for (var e : rob.getOperations().entrySet()) {
                        var op = e.getValue();
                        int p = (int) Math.ceil(op.getPickTimeSec()), r = (int) Math.ceil(op.getRotateTimeSec()), pl = (int) Math.ceil(op.getPlaceTimeSec());
                        opMap.put(e.getKey(), new int[]{p, r, pl, p + r + pl});
                    }
        var atm = device.getEfem() != null ? device.getEfem().getAtmRobot() : null;
        if (atm != null) {
            for (String key : new String[]{"foupToAligner", "alignerToLL"}) {
                var op = "foupToAligner".equals(key) ? atm.getFoupToAligner() : atm.getAlignerToLL();
                if (op != null) {
                    int p = (int) Math.ceil(op.getPickTimeSec()), r = (int) Math.ceil(op.getRotateTimeSec()), pl = (int) Math.ceil(op.getPlaceTimeSec());
                    opMap.put(key, new int[]{p, r, pl, p + r + pl});
                }
            }
        }
        int pcTime = schedule.getRecipes().containsKey("PRECLEAN") ? schedule.getRecipes().get("PRECLEAN").getAvgProcessTimeSec() : 280;
        int epiTime = schedule.getRecipes().containsKey("EPI") ? schedule.getRecipes().get("EPI").getAvgProcessTimeSec() : 2120;
        int coolTime = schedule.getTiming().getCoolingStationCoolTimeSec();
        int alignTime = (device.getEfem() != null && device.getEfem().getAligner() != null) ? (int) Math.ceil(device.getEfem().getAligner().getAlignTimeSec()) : 4;

        StringBuilder jsParts = new StringBuilder();

        for (String wid : sortedIds) {
            sb.append("<option value=\"").append(wid).append("\">").append(wid).append("</option>\n");

            var entries = wafers.get(wid);
            if (entries == null || entries.isEmpty()) {
                jsParts.append("\"").append(wid).append("\": \"<p>无数据</p>\",\n");
                continue;
            }
            StringBuilder rows = new StringBuilder();
            rows.append("<tr style=\"background:#0f3460\"><th>时间</th><th>腔室耗时</th><th>腔室</th><th>类型</th><th>处理</th><th>驻留</th><th>传出操作</th><th>Pick+Rot+Place</th><th>传输总</th></tr>");
            int prevEnd = 0;

            int[] fwd = opMap.getOrDefault("foupToAligner", new int[4]);
            rows.append("<tr><td>").append(formatTime(prevEnd)).append("</td><td>").append(formatTime(fwd[3]))
              .append("</td><td>-</td><td>ATM1: FOUP→Aligner</td><td>-</td><td>-</td><td>ATM1</td><td>")
              .append(fwd[0]).append("s+").append(fwd[1]).append("s+").append(fwd[2]).append("s</td><td>").append(fwd[3]).append("s</td></tr>");
            prevEnd += fwd[3];

            rows.append("<tr><td>").append(formatTime(prevEnd)).append("</td><td>").append(formatTime(alignTime))
              .append("</td><td>ALIGNER</td><td>Aligner</td><td>").append(formatTime(alignTime)).append("</td><td>-</td><td>-</td><td>-</td><td>-</td></tr>");
            prevEnd += alignTime;

            int[] a2ll = opMap.getOrDefault("alignerToLL", new int[4]);
            rows.append("<tr><td>").append(formatTime(prevEnd)).append("</td><td>").append(formatTime(a2ll[3]))
              .append("</td><td>-</td><td>ATM1: Aligner→LL</td><td>-</td><td>-</td><td>ATM1</td><td>")
              .append(a2ll[0]).append("s+").append(a2ll[1]).append("s+").append(a2ll[2]).append("s</td><td>").append(a2ll[3]).append("s</td></tr>");
            prevEnd += a2ll[3];

            int[] ll2pc = opMap.getOrDefault("LL_TO_PRECLEAN", new int[4]);
            var pcEntry = entries.stream().filter(e -> "PRECLEAN".equals(e.getType())).findFirst().orElse(null);
            if (pcEntry != null && pcEntry.getStartTimeSec() > prevEnd) {
                rows.append("<tr><td>").append(formatTime(prevEnd)).append("</td><td>").append(formatTime(pcEntry.getStartTimeSec() - prevEnd))
                  .append("</td><td>-</td><td>TM1: LL→PC</td><td>-</td><td>-</td><td>TM1</td><td>")
                  .append(ll2pc[0]).append("s+").append(ll2pc[1]).append("s+").append(ll2pc[2]).append("s</td><td>").append(ll2pc[3]).append("s</td></tr>");
            }
            if (pcEntry != null) prevEnd = Math.max(prevEnd, pcEntry.getStartTimeSec());

            for (var e : entries) {
                int start = e.getStartTimeSec(), end = e.getEndTimeSec() > 0 ? e.getEndTimeSec() : simTime;
                int chamberTotal = Math.max(0, end - start);
                String etype = e.getType();
                int procTime = 0, dwell = 0, pick = 0, rot = 0, place = 0, xferTotal = 0;
                String opTitle = "";

                switch (etype) {
                    case "PRECLEAN": {
                        procTime = pcTime; int[] v = opMap.getOrDefault("PRECLEAN_TO_PT", new int[4]);
                        pick = v[0]; rot = v[1]; place = v[2]; xferTotal = v[3]; opTitle = "TM1: PC→PT"; break;
                    }
                    case "EPI": {
                        procTime = epiTime; int[] v = opMap.getOrDefault("EPI_TO_PT", new int[4]);
                        pick = v[0]; rot = v[1]; place = v[2]; xferTotal = v[3]; opTitle = "TM2: EPI→PT"; break;
                    }
                    case "PASSTHROUGH": {
                        int[] v = opMap.getOrDefault("PT_TO_EPI", new int[4]);
                        pick = v[0]; rot = v[1]; place = v[2]; xferTotal = v[3]; opTitle = "TM2: PT→EPI"; break;
                    }
                    case "PT_RETURN": {
                        procTime = coolTime; int[] v = opMap.getOrDefault("PT_TO_LL", new int[4]);
                        pick = v[0]; rot = v[1]; place = v[2]; xferTotal = v[3]; opTitle = "TM1: PT→LL"; break;
                    }
                }
                dwell = Math.max(0, chamberTotal - procTime - xferTotal);
                rows.append("<tr><td>").append(formatTime(start)).append("</td><td>").append(formatTime(chamberTotal))
                  .append("</td><td>").append(e.getLocation()).append("</td><td>").append(etype)
                  .append("</td><td>").append(formatTime(procTime)).append("</td><td>").append(formatTime(dwell))
                  .append("</td><td>").append(opTitle).append("</td><td>").append(pick).append("s+").append(rot)
                  .append("s+").append(place).append("s</td><td>").append(xferTotal).append("s</td></tr>");
                prevEnd = end;
            }

            String table = "<div class=\"mx\"><table>" + rows + "</table></div>";
            jsParts.append("\"").append(wid).append("\": `").append(table).append("`,\n");
        }

        sb.append("\n<script>\nconst waferData = {\n").append(jsParts).append("};\n");
        sb.append("""
function showWaferHistory() {
  const wid = document.getElementById('waferSelect').value;
  const div = document.getElementById('waferHistoryTable');
  if (!wid || !waferData[wid]) { div.innerHTML = '<p style=color:#888>选择上方 Wafer 查看完整调度历史</p>'; return; }
  div.innerHTML = waferData[wid];
}
</script>
""");
    }

    // ==================== Replay ====================

    static void appendReplaySection(StringBuilder sb, List<SimulationSnapshot> replay,
                                    Set<String> coolingSlots, int total) {
        try {
            ObjectMapper om = new ObjectMapper();
            String replayJson = om.writeValueAsString(replay);
            List<String> csList = new ArrayList<>(coolingSlots);
            String coolingJson = om.writeValueAsString(csList);

            sb.append("""
<style>
#rp-wrap{background:#111827;border-radius:8px;padding:12px;overflow:hidden}
#rp-ctrl{display:flex;gap:8px;align-items:center;margin-bottom:8px;flex-wrap:wrap}
#rp-ctrl button{padding:6px 12px;border:none;border-radius:4px;background:#374151;color:#ddd;cursor:pointer;font-size:12px}
#rp-ctrl button:hover{background:#4B5563}
#rp-ctrl select{padding:4px 8px;border-radius:4px;background:#1F2937;color:#ddd;border:1px solid #4B5563;font-size:12px}
#rp-ctrl .rp-time{font-family:monospace;font-size:14px;color:#00d4ff;min-width:70px}
#rp-ctrl input[type=range]{flex:1;min-width:200px;accent-color:#00BCD4}
#rp-status{display:flex;gap:16px;margin-bottom:6px;font-size:12px;color:#888}
#rp-status b{color:#00d4ff}
.rp-svg-wrap{width:100%;overflow-x:auto}
[id^="arm-"] { transition: transform 0.3s linear; }
[id^="arml-"] { transition: x2 0.3s linear; }
</style>
<div id="rp-wrap">
<div id="rp-ctrl">
  <button id="rp-play" onclick="replayToggle()">▶ 播放</button>
  <button onclick="replayStep(-1)">⏮ -1</button>
  <button onclick="replayStep(1)">⏭ +1</button>
  <span class="rp-time" id="rp-time">00:00:00</span>
  <span style="color:#aaa;font-size:12px">速度</span>
  <select id="rp-speed" onchange="replaySpeed()">
    <option value="0.25">0.25x</option><option value="0.5">0.5x</option>
    <option value="1" selected>1x</option><option value="2">2x</option>
    <option value="5">5x</option><option value="10">10x</option>
    <option value="25">25x</option><option value="50">50x</option>
    <option value="100">100x</option>
  </select>
  <input type="range" id="rp-progress" min="0" max="100" value="0" oninput="replaySeek()">
</div>
</div>
""");
            sb.append("<div id=\"rp-status\">\n");
            sb.append("  <span>完成: <b id=\"rp-done\">0</b>/<b id=\"rp-total\">").append(total).append("</b></span>\n");
            sb.append("  <span>状态: <b id=\"rp-status-text\">-</b></span>\n");
            sb.append("</div>\n");
            sb.append("<div class=\"rp-svg-wrap\">\n");
            sb.append("<svg id=\"rp-svg\" viewBox=\"0 0 1140 520\" style=\"width:100%;max-height:520px;background:#111827;border-radius:0 0 8px 8px\">\n");
            sb.append("""
  <defs>
    <filter id="glow"><feGaussianBlur stdDeviation="2"/><feMerge><feMergeNode in="SourceGraphic"/></feMerge></filter>
  </defs>
  <rect x="5" y="30" width="235" height="460" rx="8" fill="none" stroke="#555" stroke-width="1" stroke-dasharray="6,3"/>
  <text x="120" y="22" text-anchor="middle" fill="#888" font-size="10">EFEM (大气环境)</text>
""");

            // FOUP slot rects
            for (int fi = 0; fi < 3; fi++) {
                String lpName = "LP" + (fi + 1);
                int y = 60 + fi * 145;
                sb.append("  <g transform=\"translate(15, ").append(y).append(")\">\n");
                sb.append("    <rect width=\"100\" height=\"130\" rx=\"4\" fill=\"#1a2a3a\" stroke=\"#2196F3\" stroke-width=\"1.5\"/>\n");
                sb.append("    <text x=\"50\" y=\"14\" text-anchor=\"middle\" fill=\"#64B5F6\" font-size=\"9\">").append(lpName).append(" (FOUP").append(fi + 1).append(")</text>\n");
                for (int si = 0; si < 25; si++) {
                    int col = si % 5, row = si / 5, x = 20 + col * 13, sy = 22 + row * 13;
                    sb.append("    <rect id=\"f-").append(lpName).append("-").append(si)
                      .append("\" x=\"").append(x).append("\" y=\"").append(sy)
                      .append("\" width=\"11\" height=\"11\" rx=\"1\" fill=\"#1a1a2e\" stroke=\"#333\" stroke-width=\"0.5\"/>\n");
                }
                sb.append("    <text x=\"50\" y=\"124\" text-anchor=\"middle\" fill=\"#555\" font-size=\"7\">25 slots</text>\n");
                sb.append("  </g>\n");
            }

            sb.append("""
  <g transform="translate(140, 130)">
    <rect id="rc-ALIGNER" width="55" height="30" rx="4" fill="#2a3a4a" stroke="#FF9800" stroke-width="1.5"/>
    <text x="27" y="12" text-anchor="middle" fill="#FFB74D" font-size="7">Aligner</text>
    <text id="wf-ALIGNER" x="27" y="24" text-anchor="middle" fill="#fff" font-size="7">空</text>
  </g>
  <g transform="translate(155, 230)">
    <circle r="25" fill="#1a2a3a" stroke="#FF9800" stroke-width="2"/>
    <text y="-10" text-anchor="middle" fill="#FF9800" font-size="8" font-weight="bold">ATM Robot</text>
    <text id="rbst-ATM1" y="1" text-anchor="middle" fill="#aaa" font-size="6">空闲</text>
    <g id="arm-ATM1" style="transform:rotate(0deg);transform-origin:0px 0px">
      <line id="arml-ATM1" x1="0" y1="0" x2="20" y2="0" stroke="#FF9800" stroke-width="3" stroke-linecap="round"/>
      <circle id="armc-ATM1" r="5" cx="20" cy="0" fill="#FF9800" stroke="#fff" stroke-width="1" filter="url(#glow)"/>
      <text id="armw-ATM1" x="20" y="12" text-anchor="middle" fill="#FFD54F" font-size="7" style="display:none"></text>
    </g>
  </g>
  <g stroke="#555" stroke-width="1" fill="none" stroke-dasharray="4,3">
    <line x1="180" y1="220" x2="245" y2="195"/>
    <line x1="180" y1="240" x2="245" y2="305"/>
  </g>
  <g transform="translate(250, 160)">
    <rect id="rc-LL1" width="80" height="55" rx="6" fill="#2a3a4a" stroke="#00BCD4" stroke-width="1.5"/>
    <text x="40" y="14" text-anchor="middle" fill="#aaa" font-size="8">LL1 (BLL)</text>
    <text id="st-LL1" x="40" y="28" text-anchor="middle" fill="#fff" font-size="10" font-weight="bold">空闲</text>
    <text id="wc-LL1" x="40" y="44" text-anchor="middle" fill="#FFD54F" font-size="9">0片</text>
    <rect id="pb-LL1" x="5" y="50" width="0" height="3" rx="1" fill="#00BCD4"/>
  </g>
  <g transform="translate(250, 275)">
    <rect id="rc-LL2" width="80" height="55" rx="6" fill="#2a3a4a" stroke="#00BCD4" stroke-width="1.5"/>
    <text x="40" y="14" text-anchor="middle" fill="#aaa" font-size="8">LL2 (BLL)</text>
    <text id="st-LL2" x="40" y="28" text-anchor="middle" fill="#fff" font-size="10" font-weight="bold">空闲</text>
    <text id="wc-LL2" x="40" y="44" text-anchor="middle" fill="#FFD54F" font-size="9">0片</text>
    <rect id="pb-LL2" x="5" y="50" width="0" height="3" rx="1" fill="#00BCD4"/>
  </g>
  <g stroke="#334" stroke-width="1.5" fill="none">
    <line x1="330" y1="185" x2="360" y2="215"/>
    <line x1="330" y1="305" x2="360" y2="245"/>
  </g>
  <rect x="345" y="35" width="10" height="450" rx="2" fill="#0f3460" stroke="#00BCD4" stroke-width="1"/>
  <text x="350" y="25" text-anchor="middle" fill="#00BCD4" font-size="8">真空</text>
  <g transform="translate(335, 50)">
    <rect id="rc-PreClean1" width="90" height="45" rx="6" fill="#2a3a4a" stroke="#FF9800" stroke-width="1.5"/>
    <text x="45" y="14" text-anchor="middle" fill="#FFB74D" font-size="9">PreClean1</text>
    <text id="st-PreClean1" x="45" y="27" text-anchor="middle" fill="#fff" font-size="10">空闲</text>
    <text id="wf-PreClean1" x="45" y="40" text-anchor="middle" fill="#FFD54F" font-size="9" style="display:none"></text>
    <rect id="pb-PreClean1" x="5" y="42" width="0" height="2" rx="1" fill="#FF9800"/>
  </g>
  <g transform="translate(335, 365)">
    <rect id="rc-PreClean2" width="90" height="45" rx="6" fill="#2a3a4a" stroke="#FF9800" stroke-width="1.5"/>
    <text x="45" y="14" text-anchor="middle" fill="#FFB74D" font-size="9">PreClean2</text>
    <text id="st-PreClean2" x="45" y="27" text-anchor="middle" fill="#fff" font-size="10">空闲</text>
    <text id="wf-PreClean2" x="45" y="40" text-anchor="middle" fill="#FFD54F" font-size="9" style="display:none"></text>
    <rect id="pb-PreClean2" x="5" y="42" width="0" height="2" rx="1" fill="#FF9800"/>
  </g>
  <g transform="translate(380, 230)">
    <circle r="35" fill="#1a2a3a" stroke="#00BCD4" stroke-width="2"/>
    <text y="-12" text-anchor="middle" fill="#00BCD4" font-size="9" font-weight="bold">TM1</text>
    <text id="rbst-TM1" y="2" text-anchor="middle" fill="#aaa" font-size="7">空闲</text>
    <g id="arm-TM1" style="transform:rotate(0deg);transform-origin:0px 0px">
      <line id="arml-TM1" x1="0" y1="0" x2="30" y2="0" stroke="#FF5722" stroke-width="3" stroke-linecap="round"/>
      <circle id="armc-TM1" r="6" cx="30" cy="0" fill="#FF5722" stroke="#fff" stroke-width="1" filter="url(#glow)"/>
      <text id="armw-TM1" x="30" y="12" text-anchor="middle" fill="#FFD54F" font-size="7" style="display:none"></text>
    </g>
  </g>
""");

            // PT chambers
            int[][] ptPositions = {{170, 0}, {212, 1}, {245, 2}, {287, 3}};
            String[] ptNames = {"PT1_S0", "PT1_S1", "PT2_S0", "PT2_S1"};
            for (int i = 0; i < 4; i++) {
                boolean isCooling = coolingSlots.contains(ptNames[i]);
                sb.append("  <g transform=\"translate(485, ").append(ptPositions[i][0]).append(")\">\n");
                sb.append("    <rect id=\"rc-").append(ptNames[i]).append("\" width=\"65\" height=\"34\" rx=\"4\" fill=\"#2a3a4a\" stroke=\"")
                  .append(isCooling ? "#00BCD4" : "#FFEB3B").append("\" stroke-width=\"1.5\"/>\n");
                sb.append("    <text id=\"ptname-").append(ptNames[i]).append("\" x=\"32\" y=\"12\" text-anchor=\"middle\" fill=\"")
                  .append(isCooling ? "#80DEEA" : "#FFF176").append("\" font-size=\"8\">").append(ptNames[i]).append("</text>\n");
                sb.append("    <text id=\"wf-").append(ptNames[i]).append("\" x=\"32\" y=\"26\" text-anchor=\"middle\" fill=\"#fff\" font-size=\"9\">空</text>\n");
                sb.append("    <text id=\"cool-").append(ptNames[i]).append("\" x=\"32\" y=\"34\" text-anchor=\"middle\" fill=\"#00BCD4\" font-size=\"7\"")
                  .append(isCooling ? "" : " style=\"display:none\"").append(">❄</text>\n");
                sb.append("  </g>\n");
            }

            sb.append("""
  <g transform="translate(620, 230)">
    <circle r="42" fill="#1a2a3a" stroke="#E91E63" stroke-width="2"/>
    <text y="-16" text-anchor="middle" fill="#E91E63" font-size="10" font-weight="bold">TM2</text>
    <text id="rbst-TM2" y="-2" text-anchor="middle" fill="#aaa" font-size="8">空闲</text>
    <g id="arm-TM2" style="transform:rotate(0deg);transform-origin:0px 0px">
      <line id="arml-TM2" x1="0" y1="0" x2="30" y2="0" stroke="#E91E63" stroke-width="3" stroke-linecap="round"/>
      <circle id="armc-TM2" r="6" cx="30" cy="0" fill="#E91E63" stroke="#fff" stroke-width="1" filter="url(#glow)"/>
      <text id="armw-TM2" x="30" y="12" text-anchor="middle" fill="#FFD54F" font-size="7" style="display:none"></text>
    </g>
  </g>
""");

            // EPI chambers
            for (int i = 0; i < 4; i++) {
                String epiId = "EPI" + (i + 1);
                int y = 40 + i * 95;
                sb.append("  <g transform=\"translate(710, ").append(y).append(")\">\n");
                sb.append("    <rect id=\"rc-").append(epiId).append("\" width=\"95\" height=\"50\" rx=\"6\" fill=\"#2a3a4a\" stroke=\"#4CAF50\" stroke-width=\"1.5\"/>\n");
                sb.append("    <text x=\"47\" y=\"14\" text-anchor=\"middle\" fill=\"#81C784\" font-size=\"9\">").append(epiId).append("</text>\n");
                sb.append("    <text id=\"st-").append(epiId).append("\" x=\"47\" y=\"28\" text-anchor=\"middle\" fill=\"#fff\" font-size=\"10\" font-weight=\"bold\">空闲</text>\n");
                sb.append("    <text id=\"wf-").append(epiId).append("\" x=\"47\" y=\"42\" text-anchor=\"middle\" fill=\"#FFD54F\" font-size=\"9\" style=\"display:none\"></text>\n");
                sb.append("    <rect id=\"pb-").append(epiId).append("\" x=\"5\" y=\"46\" width=\"0\" height=\"3\" rx=\"1\" fill=\"#4CAF50\"/>\n");
                sb.append("  </g>\n");
            }

            sb.append("""
  <g transform="translate(820, 435)">
    <text x="0" y="10" fill="#888" font-size="9">FOUP: </text>
    <rect x="42" y="2" width="10" height="10" rx="1" fill="#555"/>
    <text x="56" y="11" fill="#888" font-size="8">未处理</text>
    <rect x="100" y="2" width="10" height="10" rx="1" fill="#1a1a2e" stroke="#333"/>
    <text x="114" y="11" fill="#888" font-size="8">已取走</text>
    <rect x="158" y="2" width="10" height="10" rx="1" fill="#4CAF50"/>
    <text x="172" y="11" fill="#888" font-size="8">已完成</text>
  </g>
</svg>
</div>
</div>
<script>
const REPLAY = """).append(replayJson).append(";\n");
            sb.append("const COOLING_SLOTS = new Set(").append(coolingJson).append(");\n");
            sb.append("""

const STATE_COLORS = {
  IDLE:'#2a3a4a', PROCESSING:'#1b5e20', DONE:'#e65100',
  PUMPING:'#0d47a1', VENTING:'#4a148c', READY:'#006064',
  LOADING:'#3e2723', UNLOADING:'#3e2723',
  CLEANING:'#FF5722', PURGING:'#9C27B0', COOLING:'#0288D1'
};
const STATE_LABELS = {
  IDLE:'空闲', PROCESSING:'处理中', DONE:'完成', PUMPING:'抽真空',
  VENTING:'充气', READY:'就绪', LOADING:'装载', UNLOADING:'卸载',
  CLEANING:'清洗', PURGING:'吹扫', COOLING:'冷却'
};
const CHAMBER_IDS = ['LL1','LL2','PreClean1','PreClean2','PT1_S0','PT1_S1','PT2_S0','PT2_S1','EPI1','EPI2','EPI3','EPI4'];
const CHAMBER_SET = new Set(CHAMBER_IDS);
const STATE_ID_IDS = new Set(['LL1','LL2','PreClean1','PreClean2','EPI1','EPI2','EPI3','EPI4']);
const PROGRESS_IDS = new Set(['LL1','LL2','PreClean1','PreClean2','EPI1','EPI2','EPI3','EPI4']);
const FOUP_NAMES = ['LP1','LP2','LP3'];

const atmAngles = { LP1:-131, LP2:156, LP3:116, ALIGNER:-82, LL1:-18, LL2:28 };
const tm1Angles = { LL1:-155, LL2:141, PreClean1:-90, PreClean2:90, PT1_S0:-17, PT1_S1:0, PT2_S0:13, PT2_S1:28 };
const tm2Angles = { EPI1:-50, EPI2:-27, EPI3:10, EPI4:41, PT1_S0:-157, PT1_S1:180, PT2_S0:163, PT2_S1:144 };

const ROBOT_MAP = {
  ATM1: { angles:atmAngles, idleLen:20, busyLen:28 },
  Robot1: { angles:tm1Angles, idleLen:30, busyLen:35, cssId:'TM1' },
  Robot2: { angles:tm2Angles, idleLen:30, busyLen:35, cssId:'TM2' }
};

let replayIdx = 0, playing = false, speed = 1, animId = null;
let lastRealTime = 0, currentSimTime = 0;

function fmtTime(s) {
  const h=Math.floor(s/3600),m=Math.floor((s%3600)/60),se=Math.floor(s%60);
  return String(h).padStart(2,'0')+':'+String(m).padStart(2,'0')+':'+String(se).padStart(2,'0');
}

function replayToggle() { if (playing) replayPause(); else replayPlay(); }

function replayPlay() {
  if (!REPLAY.length || playing) return;
  playing = true;
  document.getElementById('rp-play').textContent = '⏸ 暂停';
  lastRealTime = performance.now();
  if (!currentSimTime) currentSimTime = REPLAY[replayIdx].currentTimeSec;
  animId = requestAnimationFrame(replayLoop);
}

function replayPause() {
  playing = false;
  document.getElementById('rp-play').textContent = '▶ 播放';
  if (animId) { cancelAnimationFrame(animId); animId = null; }
}

function replayLoop(now) {
  if (!playing) return;
  const elapsed = (now - lastRealTime) / 1000;
  lastRealTime = now;
  currentSimTime += elapsed * speed;
  while (replayIdx < REPLAY.length - 1 && REPLAY[replayIdx + 1].currentTimeSec <= currentSimTime) {
    replayIdx++;
  }
  if (replayIdx >= REPLAY.length - 1) {
    replayIdx = REPLAY.length - 1;
    currentSimTime = REPLAY[replayIdx].currentTimeSec;
    replayPause();
  }
  renderFrame();
  if (playing) animId = requestAnimationFrame(replayLoop);
}

function replayStep(dir) {
  replayPause();
  if (!REPLAY.length) return;
  replayIdx = Math.max(0, Math.min(REPLAY.length - 1, replayIdx + dir));
  currentSimTime = REPLAY[replayIdx].currentTimeSec;
  renderFrame();
}

function replaySpeed() { speed = parseFloat(document.getElementById('rp-speed').value); }

function replaySeek() {
  replayPause();
  if (!REPLAY.length) return;
  const pct = parseInt(document.getElementById('rp-progress').value) / 100;
  replayIdx = Math.floor(pct * (REPLAY.length - 1));
  currentSimTime = REPLAY[replayIdx].currentTimeSec;
  renderFrame();
}

function renderFrame() {
  if (!REPLAY.length) return;
  const snap = REPLAY[replayIdx];
  document.getElementById('rp-time').textContent = fmtTime(snap.currentTimeSec);
  document.getElementById('rp-progress').value = REPLAY.length > 1 ? (replayIdx / (REPLAY.length - 1) * 100) : 0;
  document.getElementById('rp-done').textContent = snap.completedWafers || 0;
  document.getElementById('rp-total').textContent = snap.totalWafers || 0;
  document.getElementById('rp-status-text').textContent = snap.status || '-';
  updateLayout(snap);
}

function getRobotFromSnap(rbs, robotId) {
  for (const [rid, r] of Object.entries(rbs || {})) {
    if (rid === robotId || r.tmId === robotId) return r;
  }
  return null;
}

function getArmPhase(r) {
  if (!r || r.state !== 'BUSY') return '';
  const total = 15;
  const remaining = r.remainingTimeSec || 0;
  return remaining > total / 2 ? (r.sourceChamber || '') : (r.targetChamber || '');
}

function updateLayout(snap) {
  const ch = snap.chambers || {};
  const rbs = snap.robots || {};
  const wfs = snap.wafers || [];

  const waferMap = {};
  for (const wf of wfs) {
    const slotIdx = (wf.slotIndex || 0);
    waferMap[wf.foupIndex + '_' + slotIdx] = wf;
  }
  for (let fi = 0; fi < 3; fi++) {
    for (let si = 0; si < 25; si++) {
      const el = document.getElementById('f-' + FOUP_NAMES[fi] + '-' + si);
      if (!el) continue;
      const slotIndex = si + 1;
      const wf = waferMap[fi + '_' + slotIndex];
      let color = '#1a1a2e';
      if (wf) {
        if (wf.state === 'COMPLETED') color = '#4CAF50';
        else if (CHAMBER_SET.has(wf.location)) color = '#1a1a2e';
        else color = '#555';
      }
      el.setAttribute('fill', color);
    }
  }

  for (const cid of CHAMBER_IDS) {
    const cd = ch[cid];
    const color = cd ? (STATE_COLORS[cd.state] || '#2a3a4a') : '#2a3a4a';

    const rc = document.getElementById('rc-' + cid);
    if (rc) rc.setAttribute('fill', color);

    if (STATE_ID_IDS.has(cid)) {
      const st = document.getElementById('st-' + cid);
      if (st) {
        let txt = cd ? (STATE_LABELS[cd.state] || cd.state || '空闲') : '空闲';
        if (cd && cd.remainingTimeSec > 0) txt += ' ' + cd.remainingTimeSec + 's';
        st.textContent = txt;
      }
    }

    const wfEl = document.getElementById('wf-' + cid);
    if (wfEl) {
      if (cid === 'ALIGNER' || cid.startsWith('PT')) {
        wfEl.textContent = cd && cd.waferId ? cd.waferId : '空';
        wfEl.style.display = '';
      } else {
        wfEl.textContent = cd && cd.waferId ? cd.waferId : '';
        wfEl.style.display = cd && cd.waferId ? '' : 'none';
      }
    }

    if (cid === 'LL1' || cid === 'LL2') {
      const wc = document.getElementById('wc-' + cid);
      if (wc) wc.textContent = (cd ? cd.waferCount || 0 : 0) + '片';
    }

    if (PROGRESS_IDS.has(cid)) {
      const pb = document.getElementById('pb-' + cid);
      if (pb && cd && cd.totalTimeSec > 0 && cd.remainingTimeSec > 0) {
        const maxW = cid.startsWith('EPI') ? 85 : 80;
        const pct = Math.max(0, Math.min(1, 1 - cd.remainingTimeSec / cd.totalTimeSec));
        pb.setAttribute('width', pct * maxW);
      } else if (pb) {
        pb.setAttribute('width', 0);
      }
    }

    if (cid.startsWith('PT')) {
      const isCooling = COOLING_SLOTS.has(cid);
      if (rc) rc.setAttribute('stroke', isCooling ? '#00BCD4' : '#FFEB3B');
      const pn = document.getElementById('ptname-' + cid);
      if (pn) pn.setAttribute('fill', isCooling ? '#80DEEA' : '#FFF176');
      const coolEl = document.getElementById('cool-' + cid);
      if (coolEl) coolEl.style.display = isCooling ? '' : 'none';
    }
  }

  for (const [robotId, cfg] of Object.entries(ROBOT_MAP)) {
    const rd = getRobotFromSnap(rbs, robotId);
    const cssId = cfg.cssId || robotId;
    const busy = rd && rd.state === 'BUSY';

    const rbst = document.getElementById('rbst-' + cssId);
    if (rbst) {
      if (rd) rbst.textContent = busy ? (rd.currentAction || '搬运中') : '空闲';
      else rbst.textContent = '离线';
    }

    const phase = getArmPhase(rd);
    const angle = busy && phase ? (cfg.angles[phase] || 0) : 0;
    const len = busy ? cfg.busyLen : cfg.idleLen;

    const armG = document.getElementById('arm-' + cssId);
    if (armG) {
      armG.style.transform = 'rotate(' + angle + 'deg)';
      armG.style.display = rd ? '' : 'none';
    }

    const armL = document.getElementById('arml-' + cssId);
    if (armL) armL.setAttribute('x2', len);

    const armC = document.getElementById('armc-' + cssId);
    if (armC) armC.setAttribute('cx', len);

    const armW = document.getElementById('armw-' + cssId);
    if (armW) {
      const waferId = rd && rd.arm1WaferId;
      armW.textContent = waferId || '';
      armW.style.display = waferId ? '' : 'none';
      armW.setAttribute('x', len);
    }
  }
}

(function init() {
  document.getElementById('rp-total').textContent = REPLAY.length > 0 ? REPLAY[REPLAY.length - 1].totalWafers : 0;
  renderFrame();
})();
</script>""");
        } catch (Exception e) {
            sb.append("<p style='color:#888'>回放数据序列化失败: ").append(e.getMessage()).append("</p>\n");
        }
    }
}
