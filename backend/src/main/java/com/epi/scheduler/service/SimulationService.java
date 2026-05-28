package com.epi.scheduler.service;

import com.epi.scheduler.engine.SchedulerEngine;
import com.epi.scheduler.model.GanttEntry;
import com.epi.scheduler.model.JobConfig;
import com.epi.scheduler.model.SimulationSnapshot;
import jakarta.annotation.PostConstruct;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SimulationService {

    private final ConfigService configService;
    private final SimpMessagingTemplate messagingTemplate;
    private volatile SchedulerEngine engine;
    private int simulationSpeed = 10;
    private int tickAccumulator = 0;

    public SimulationService(ConfigService configService, SimpMessagingTemplate messagingTemplate) {
        this.configService = configService;
        this.messagingTemplate = messagingTemplate;
    }

    @PostConstruct
    public void init() {
        engine = new SchedulerEngine(configService.getDeviceConfig(), configService.getScheduleConfig());
        engine.setAmConfig(configService.getAmConfig());
        simulationSpeed = configService.getScheduleConfig().getSimulation().getSpeed();
    }

    public void start() {
        engine.start();
    }

    public void startJob(String cjId) {
        JobConfig job = configService.getJobConfig();
        if (job != null) {
            JobConfig.ControlJob cj = job.findControlJob(cjId);
            if (cj != null) {
                engine = new SchedulerEngine(configService.getDeviceConfig(), configService.getScheduleConfig(), cj);
                engine.setAmConfig(configService.getAmConfig());
                simulationSpeed = configService.getScheduleConfig().getSimulation().getSpeed();
            }
        }
        engine.start();
    }

    public void pause() {
        engine.pause();
    }

    public void reset() {
        engine.reset();
    }

    public void step() {
        engine.step();
        broadcastState();
    }

    public void setSpeed(int speed) {
        this.simulationSpeed = Math.max(1, Math.min(100, speed));
    }

    public int getSpeed() {
        return simulationSpeed;
    }

    public SimulationSnapshot getState() {
        return engine.getSnapshot();
    }

    public List<GanttEntry> getGanttData() {
        return engine.getGanttData();
    }

    public List<String> getEventLog() {
        return engine.getFullEventLog();
    }

    public SchedulerEngine getEngine() {
        return engine;
    }

    public void reloadAndReset() throws Exception {
        configService.reloadConfigs();
        engine = new SchedulerEngine(configService.getDeviceConfig(), configService.getScheduleConfig());
    }

    @Scheduled(fixedRate = 10)
    public void simulationLoop() {
        if (engine.getStatus() != SchedulerEngine.SimStatus.RUNNING) return;

        tickAccumulator += simulationSpeed;
        int ticksToRun = tickAccumulator / 100;
        tickAccumulator %= 100;

        if (ticksToRun <= 0) return;

        for (int i = 0; i < ticksToRun; i++) {
            if (!engine.tick()) break;
        }
        broadcastState();
    }

    private void broadcastState() {
        try {
            messagingTemplate.convertAndSend("/topic/state", engine.getSnapshot());
        } catch (Exception ignored) {
        }
    }
}
