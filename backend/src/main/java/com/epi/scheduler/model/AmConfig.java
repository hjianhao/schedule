package com.epi.scheduler.model;

import java.util.List;
import java.util.Map;

public class AmConfig {
    private String name;
    private List<MaintenanceTask> tasks;

    public static class MaintenanceTask {
        private String id;
        private String name;
        private String description;
        private String type;
        private double cleanTimeSec;
        private double gapTimeSec;
        private double idleThresholdSec;
        private List<Map<String, String>> appliesTo;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public double getCleanTimeSec() { return cleanTimeSec; }
        public void setCleanTimeSec(double cleanTimeSec) { this.cleanTimeSec = cleanTimeSec; }
        public double getGapTimeSec() { return gapTimeSec; }
        public void setGapTimeSec(double gapTimeSec) { this.gapTimeSec = gapTimeSec; }
        public double getIdleThresholdSec() { return idleThresholdSec; }
        public void setIdleThresholdSec(double idleThresholdSec) { this.idleThresholdSec = idleThresholdSec; }
        public List<Map<String, String>> getAppliesTo() { return appliesTo; }
        public void setAppliesTo(List<Map<String, String>> appliesTo) { this.appliesTo = appliesTo; }

        public boolean appliesToChamberType(String chamberType) {
            if (appliesTo == null) return false;
            return appliesTo.stream().anyMatch(a -> chamberType.equals(a.get("chamberType")));
        }
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public List<MaintenanceTask> getTasks() { return tasks; }
    public void setTasks(List<MaintenanceTask> tasks) { this.tasks = tasks; }

    public MaintenanceTask findPreProcessTask(String chamberType) {
        if (tasks == null) return null;
        return tasks.stream()
                .filter(t -> "PRE_PROCESS".equals(t.getType()) && t.appliesToChamberType(chamberType))
                .findFirst().orElse(null);
    }

    public MaintenanceTask findOnLoadCleanTask(String chamberType) {
        if (tasks == null) return null;
        return tasks.stream()
                .filter(t -> "ON_LOAD_CLEAN".equals(t.getType()) && t.appliesToChamberType(chamberType))
                .findFirst().orElse(null);
    }

    public MaintenanceTask findIdlePurgeTask(String chamberType) {
        if (tasks == null) return null;
        return tasks.stream()
                .filter(t -> "IDLE_PURGE".equals(t.getType()) && t.appliesToChamberType(chamberType))
                .findFirst().orElse(null);
    }

    public double getCleanTimeForChamber(String chamberType) {
        MaintenanceTask task = findPreProcessTask(chamberType);
        return task != null ? task.getCleanTimeSec() : 0;
    }

    public double getOnLoadCleanTime(String chamberType) {
        MaintenanceTask task = findOnLoadCleanTask(chamberType);
        return task != null ? task.getCleanTimeSec() : 0;
    }

    public double getIdlePurgeTime(String chamberType) {
        MaintenanceTask task = findIdlePurgeTask(chamberType);
        return task != null ? task.getCleanTimeSec() : 0;
    }

    public double getIdlePurgeThreshold(String chamberType) {
        MaintenanceTask task = findIdlePurgeTask(chamberType);
        return task != null ? task.getIdleThresholdSec() : 0;
    }
}
