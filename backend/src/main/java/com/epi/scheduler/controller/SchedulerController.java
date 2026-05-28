package com.epi.scheduler.controller;

import com.epi.scheduler.model.DeviceConfig;
import com.epi.scheduler.model.GanttEntry;
import com.epi.scheduler.model.ScheduleConfig;
import com.epi.scheduler.model.SimulationSnapshot;
import com.epi.scheduler.service.ConfigService;
import com.epi.scheduler.service.PptxReportService;
import com.epi.scheduler.service.ReportService;
import com.epi.scheduler.service.SimulationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class SchedulerController {

    private final SimulationService simulationService;
    private final ConfigService configService;
    private final ReportService reportService;
    private final PptxReportService pptxReportService;

    public SchedulerController(SimulationService simulationService, ConfigService configService,
                               ReportService reportService, PptxReportService pptxReportService) {
        this.simulationService = simulationService;
        this.configService = configService;
        this.reportService = reportService;
        this.pptxReportService = pptxReportService;
    }

    @PostMapping("/simulation/start")
    public ResponseEntity<Map<String, String>> start(@RequestBody(required = false) Map<String, String> body) {
        String cjId = body != null ? body.get("cjId") : null;
        if (cjId != null && !cjId.isEmpty()) {
            var job = configService.getJobConfig();
            if (job == null || job.findControlJob(cjId) == null)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown control job: " + cjId);
            simulationService.startJob(cjId);
        } else {
            simulationService.start();
        }
        return ResponseEntity.ok(Map.of("status", "started"));
    }

    @PostMapping("/simulation/pause")
    public ResponseEntity<Map<String, String>> pause() {
        simulationService.pause();
        return ResponseEntity.ok(Map.of("status", "paused"));
    }

    @PostMapping("/simulation/reset")
    public ResponseEntity<Map<String, String>> reset() {
        simulationService.reset();
        return ResponseEntity.ok(Map.of("status", "reset"));
    }

    @PostMapping("/simulation/step")
    public ResponseEntity<SimulationSnapshot> step() {
        simulationService.step();
        return ResponseEntity.ok(simulationService.getState());
    }

    @PostMapping("/simulation/speed")
    public ResponseEntity<Map<String, Integer>> setSpeed(@RequestBody Map<String, Integer> body) {
        int speed = body.getOrDefault("speed", 10);
        if (speed < 1 || speed > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Speed must be between 1 and 100");
        simulationService.setSpeed(speed);
        return ResponseEntity.ok(Map.of("speed", simulationService.getSpeed()));
    }

    @GetMapping("/simulation/state")
    public ResponseEntity<SimulationSnapshot> getState() {
        return ResponseEntity.ok(simulationService.getState());
    }

    @GetMapping("/simulation/gantt")
    public ResponseEntity<List<GanttEntry>> getGantt() {
        return ResponseEntity.ok(simulationService.getGanttData());
    }

    @GetMapping("/simulation/events")
    public ResponseEntity<List<String>> getEvents() {
        return ResponseEntity.ok(simulationService.getEventLog());
    }

    @GetMapping("/config/device")
    public ResponseEntity<DeviceConfig> getDeviceConfig() {
        return ResponseEntity.ok(configService.getDeviceConfig());
    }

    @GetMapping("/config/schedule")
    public ResponseEntity<ScheduleConfig> getScheduleConfig() {
        return ResponseEntity.ok(configService.getScheduleConfig());
    }

    @GetMapping("/simulation/foups")
    public ResponseEntity<Map<String, Map<String, Object>>> getFoupState() {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        simulationService.getEngine().getFoupSlots().forEach((key, fs) -> {
            Map<String, Object> slot = new LinkedHashMap<>();
            slot.put("foupIndex", fs.foupIndex);
            slot.put("slotIndex", fs.slotIndex);
            slot.put("waferId", fs.waferId);
            slot.put("state", fs.state);
            result.put(key, slot);
        });
        return ResponseEntity.ok(result);
    }

    @GetMapping("/simulation/robots")
    public ResponseEntity<List<Map<String, Object>>> getRobotPositions() {
        List<Map<String, Object>> result = new ArrayList<>();
        simulationService.getEngine().getRobots().forEach((key, robot) -> {
            int now = simulationService.getEngine().getCurrentTimeSec();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", robot.id);
            r.put("tmId", robot.tmId);
            r.put("busy", robot.busy);
            r.put("busyUntil", robot.busyUntil);
            r.put("remainingTimeSec", Math.max(0, robot.busyUntil - now));
            r.put("armWaferId", robot.armWaferId);
            r.put("currentAction", robot.currentAction);
            r.put("sourceChamber", robot.sourceChamber);
            r.put("targetChamber", robot.targetChamber);
            result.add(r);
        });
        return ResponseEntity.ok(result);
    }

    @GetMapping("/config/job")
    public ResponseEntity<?> getJobConfig() {
        var job = configService.getJobConfig();
        if (job == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(job);
    }

    @GetMapping("/config/am")
    public ResponseEntity<?> getAmConfig() {
        var am = configService.getAmConfig();
        if (am == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(am);
    }

    @GetMapping("/config/sequence")
    public ResponseEntity<?> getSequenceConfig() {
        var seq = configService.getSequenceConfig();
        if (seq == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(seq);
    }

    @PostMapping("/config/reload")
    public ResponseEntity<Map<String, String>> reloadConfig() {
        try {
            simulationService.reloadAndReset();
            return ResponseEntity.ok(Map.of("status", "reloaded"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/report/generate")
    public ResponseEntity<Map<String, String>> generateReport() {
        try {
            var engine = simulationService.getEngine();
            String htmlPath = reportService.generateHtml(engine, configService);
            String pptxPath = pptxReportService.generatePptx(engine, configService);
            return ResponseEntity.ok(Map.of("status", "generated",
                    "html", htmlPath,
                    "pptx", pptxPath));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/simulation/replay")
    public ResponseEntity<List<com.epi.scheduler.model.SimulationSnapshot>> getReplayData() {
        return ResponseEntity.ok(simulationService.getEngine().getReplaySnapshots());
    }
}
