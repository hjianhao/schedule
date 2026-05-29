package com.epi.scheduler.engine;

import com.epi.scheduler.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.PrintStream;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class EpiUtilizationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void measureEpiChamberUtilization() throws Exception {
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

        assertTrue(engine.getCurrentTimeSec() < maxTicks,
                "Simulation timed out at " + engine.getCurrentTimeSec() + "s");

        List<GanttEntry> gantt = engine.getGanttData();
        Map<String, List<GanttEntry>> byChamber = new LinkedHashMap<>();

        for (GanttEntry e : gantt) {
            if (!e.getLocation().startsWith("EPI")) continue;
            byChamber.computeIfAbsent(e.getLocation(), k -> new ArrayList<>()).add(e);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n========== EPI Chamber Utilization ==========\n");
        sb.append("Config: dwellSafetyMarginSec=").append(sc.getScheduling().getDwellSafetyMarginSec()).append("\n");
        sb.append("EPI process: ").append(sc.getRecipes().get("EPI").getAvgProcessTimeSec())
                .append("s ±").append(sc.getRecipes().get("EPI").getProcessTimeVariationSec()).append("s\n");
        sb.append("1X Clean: ").append(am.getCleanTimeForChamber("EPI")).append("s\n");
        sb.append("Total EPI cycle: ").append(sc.getRecipes().get("EPI").getAvgProcessTimeSec()
                + (int)am.getCleanTimeForChamber("EPI")).append("s\n");
        sb.append("Stagger interval: ").append((sc.getRecipes().get("EPI").getAvgProcessTimeSec()
                + (int)am.getCleanTimeForChamber("EPI")) / 4).append("s\n");
        sb.append("Total wafers: ").append(engine.getSnapshot().getTotalWafers()).append("\n");
        sb.append("Completed: ").append(engine.getSnapshot().getCompletedWafers()).append("\n");
        sb.append("Sim time: ").append(formatTime(engine.getCurrentTimeSec())).append("\n");

        double completionWindow = engine.getCurrentTimeSec();
        int completed = engine.getSnapshot().getCompletedWafers();
        double wph = completed > 0 ? completed / (completionWindow / 3600.0) : 0;
        sb.append(String.format("Throughput: %.2f WPH%n", wph));
        sb.append("\n");

        double totalProcess = 0, totalClean = 0, totalIdle = 0;

        for (Map.Entry<String, List<GanttEntry>> entry : byChamber.entrySet()) {
            String chId = entry.getKey();
            List<GanttEntry> entries = entry.getValue();
            entries.sort(Comparator.comparingInt(GanttEntry::getStartTimeSec));

            double processTime = 0, cleanTime = 0;

            for (GanttEntry e : entries) {
                int end = e.getEndTimeSec() > 0 ? e.getEndTimeSec() : engine.getCurrentTimeSec();
                double dur = end - e.getStartTimeSec();
                if ("EPI".equals(e.getType())) {
                    processTime += dur;
                } else {
                    cleanTime += dur;
                }
            }

            int firstStart = entries.get(0).getStartTimeSec();
            int lastEnd = entries.get(entries.size() - 1).getEndTimeSec();
            if (lastEnd < 0) lastEnd = engine.getCurrentTimeSec();
            double activeWindow = lastEnd - firstStart;

            double idleTime = activeWindow - processTime - cleanTime;
            double utilization = activeWindow > 0 ? processTime / activeWindow * 100 : 0;

            sb.append(String.format("%s:%n", chId));
            sb.append(String.format("  Active window:  %.0fs (%s → %s)%n",
                    activeWindow, formatTime(firstStart), formatTime(lastEnd)));
            sb.append(String.format("  EPI Processing: %.0fs (%.1f%%)%n", processTime, processTime / activeWindow * 100));
            sb.append(String.format("  Clean:          %.0fs (%.1f%%)%n", cleanTime, cleanTime / activeWindow * 100));
            sb.append(String.format("  Idle:           %.0fs (%.1f%%)%n", idleTime, idleTime / activeWindow * 100));
            sb.append(String.format("  Utilization:    %.1f%% (processing / active_window)%n", utilization));
            sb.append(String.format("  EPI cycles:     %d%n", entries.stream().filter(e -> "EPI".equals(e.getType())).count()));

            List<double[]> gaps = new ArrayList<>();
            for (int i = 1; i < entries.size(); i++) {
                int prevEnd = entries.get(i - 1).getEndTimeSec();
                int currStart = entries.get(i).getStartTimeSec();
                if (prevEnd > 0 && currStart > prevEnd) {
                    gaps.add(new double[]{prevEnd, currStart, currStart - prevEnd});
                }
            }
            if (!gaps.isEmpty()) {
                double maxGap = gaps.stream().mapToDouble(g -> g[2]).max().orElse(0);
                double avgGap = gaps.stream().mapToDouble(g -> g[2]).average().orElse(0);
                sb.append(String.format("  Idle gaps: %d (max %.0fs, avg %.0fs)%n", gaps.size(), maxGap, avgGap));
            }

            totalProcess += processTime;
            totalClean += cleanTime;
            totalIdle += idleTime;
        }

        sb.append("\n--- Aggregate ---\n");
        double totalActive = totalProcess + totalClean + totalIdle;
        sb.append(String.format("Total Processing: %.0fs (%.1f%%)%n", totalProcess, totalProcess / totalActive * 100));
        sb.append(String.format("Total Clean:      %.0fs (%.1f%%)%n", totalClean, totalClean / totalActive * 100));
        sb.append(String.format("Total Idle:       %.0fs (%.1f%%)%n", totalIdle, totalIdle / totalActive * 100));
        sb.append(String.format("Avg Utilization:  %.1f%%%n", totalProcess / (totalProcess + totalIdle) * 100));
        sb.append("==============================================\n");

        String output = sb.toString();
        System.out.print(output);

        // Write to result/ directory
        Files.createDirectories(Path.of("../result"));
        Files.writeString(Path.of("../result/utilization.txt"), output);

        double avgUtil = totalProcess / (totalProcess + totalIdle) * 100;
        assertTrue(avgUtil > 80, "Expected EPI utilization > 80%, got " + String.format("%.1f%%", avgUtil));
    }

    private static String formatTime(int s) {
        return String.format("%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }
}
