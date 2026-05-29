package com.epi.scheduler.engine;

import com.epi.scheduler.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.*;
import java.util.*;

class EpiGapAnalysisTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void analyzeCleanToProcessGap() throws Exception {
        String profilePath = "../conf/sige-epi";

        DeviceConfig dc = mapper.readValue(new File(profilePath, "device.json"), DeviceConfig.class);
        ScheduleConfig sc = mapper.readValue(new File(profilePath, "schedule.json"), ScheduleConfig.class);
        AmConfig am = mapper.readValue(new File(profilePath, "am.json"), AmConfig.class);

        sc.getSimulation().setTotalWafers(25);

        var engine = new SchedulerEngine(dc, sc);
        engine.setAmConfig(am);
        engine.start();

        int maxTicks = 500_000;
        while (engine.getStatus() != SchedulerEngine.SimStatus.COMPLETED) {
            if (!engine.tick()) break;
            if (engine.getCurrentTimeSec() > maxTicks) break;
        }

        List<GanttEntry> gantt = engine.getGanttData();
        Map<String, List<GanttEntry>> byChamber = new LinkedHashMap<>();

        for (GanttEntry e : gantt) {
            if (!e.getLocation().startsWith("EPI")) continue;
            byChamber.computeIfAbsent(e.getLocation(), k -> new ArrayList<>()).add(e);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n========== EPI Clean→Process Gap Analysis ==========\n");
        sb.append("EPI process: ").append(sc.getRecipes().get("EPI").getAvgProcessTimeSec())
                .append("s ±").append(sc.getRecipes().get("EPI").getProcessTimeVariationSec()).append("s\n");
        sb.append("1X Clean: ").append((int) am.getCleanTimeForChamber("EPI")).append("s\n");
        sb.append("dwellSafetyMargin: ").append(sc.getScheduling().getDwellSafetyMarginSec()).append("s\n");
        sb.append("\n");

        List<Double> allGaps = new ArrayList<>();

        for (Map.Entry<String, List<GanttEntry>> entry : byChamber.entrySet()) {
            String chId = entry.getKey();
            List<GanttEntry> entries = entry.getValue();
            entries.sort(Comparator.comparingInt(GanttEntry::getStartTimeSec));

            sb.append("--- ").append(chId).append(" ---\n");

            List<Double> chamberGaps = new ArrayList<>();
            int cycleNum = 0;

            for (int i = 0; i < entries.size(); i++) {
                GanttEntry e = entries.get(i);
                int end = e.getEndTimeSec() > 0 ? e.getEndTimeSec() : engine.getCurrentTimeSec();

                if ("CLEAN".equals(e.getType()) || "PURGE".equals(e.getType())) {
                    GanttEntry nextEpi = null;
                    for (int j = i + 1; j < entries.size(); j++) {
                        if ("EPI".equals(entries.get(j).getType())) {
                            nextEpi = entries.get(j);
                            break;
                        }
                    }
                    if (nextEpi != null) {
                        cycleNum++;
                        int cleanEnd = end;
                        int epiStart = nextEpi.getStartTimeSec();
                        int gap = epiStart - cleanEnd;

                        chamberGaps.add((double) gap);
                        allGaps.add((double) gap);

                        sb.append(String.format("  Cycle %d: Clean end=%s, EPI start=%s, GAP=%ds%s%n",
                                cycleNum, formatTime(cleanEnd), formatTime(epiStart),
                                gap, gap < 0 ? " (OVERLAP!)" : ""));

                        if (gap > 0) {
                            // Find PT dwell for context
                            String waferId = nextEpi.getWaferId();
                            int ptArrival = -1;
                            for (GanttEntry ge : gantt) {
                                if (ge.getWaferId().equals(waferId) && ge.getLocation().startsWith("PT")
                                        && !ge.getType().contains("RET")) {
                                    if (ge.getStartTimeSec() <= epiStart) {
                                        ptArrival = ge.getStartTimeSec();
                                        break;
                                    }
                                }
                            }
                            if (ptArrival > 0) {
                                int ptDwell = epiStart - ptArrival;
                                sb.append(String.format("    Wafer arrived PT at %s, PT dwell=%ds%n",
                                        formatTime(ptArrival), ptDwell));
                            }
                        }
                    }
                }
            }

            if (!chamberGaps.isEmpty()) {
                double avg = chamberGaps.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                double max = chamberGaps.stream().mapToDouble(Double::doubleValue).max().orElse(0);
                double min = chamberGaps.stream().mapToDouble(Double::doubleValue).min().orElse(0);
                sb.append(String.format("  Summary: %d cycles, gap min=%.0fs, avg=%.0fs, max=%.0fs%n%n",
                        chamberGaps.size(), min, avg, max));
            }
        }

        sb.append("--- Overall ---\n");
        if (!allGaps.isEmpty()) {
            double avg = allGaps.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double max = allGaps.stream().mapToDouble(Double::doubleValue).max().orElse(0);
            double min = allGaps.stream().mapToDouble(Double::doubleValue).min().orElse(0);
            sb.append(String.format("All cycles: %d gaps, min=%.0fs, avg=%.0fs, max=%.0fs%n",
                    allGaps.size(), min, avg, max));

            sb.append("Gap distribution:\n");
            int[] buckets = new int[10];
            for (double g : allGaps) {
                int idx = Math.min((int) (g / 50), 9);
                buckets[idx]++;
            }
            for (int i = 0; i < 10; i++) {
                if (buckets[i] > 0) {
                    String range = i < 9 ? String.format("%d-%ds", i * 50, (i + 1) * 50) : "450s+";
                    sb.append(String.format("  %s: %d%n", range, buckets[i]));
                }
            }
        }

        sb.append(String.format("%nSim time: %s, Completed: %d, WPH: %.2f%n",
                formatTime(engine.getCurrentTimeSec()),
                engine.getSnapshot().getCompletedWafers(),
                engine.getSnapshot().getCompletedWafers() / (engine.getCurrentTimeSec() / 3600.0)));
        sb.append("====================================================\n");

        String output = sb.toString();
        System.out.print(output);

        Files.createDirectories(Path.of("../result"));
        Files.writeString(Path.of("../result/gap_analysis.txt"), output);
    }

    private static String formatTime(int s) {
        return String.format("%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }
}
