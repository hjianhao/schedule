package com.epi.scheduler.model;

import java.util.List;
import java.util.Map;

public class ScheduleConfig {
    private Map<String, RecipeConfig> recipes;
    private List<String> waferFlow;
    private SchedulingParams scheduling;
    private RobotParams robot;
    private TimingParams timing;
    private SimulationParams simulation;

    public static class RecipeConfig {
        private int avgProcessTimeSec;
        private int processTimeVariationSec;
        private int maxDwellTimeSec;

        public int getAvgProcessTimeSec() { return avgProcessTimeSec; }
        public void setAvgProcessTimeSec(int v) { this.avgProcessTimeSec = v; }
        public int getProcessTimeVariationSec() { return processTimeVariationSec; }
        public void setProcessTimeVariationSec(int v) { this.processTimeVariationSec = v; }
        public int getMaxDwellTimeSec() { return maxDwellTimeSec; }
        public void setMaxDwellTimeSec(int v) { this.maxDwellTimeSec = v; }
    }

    public static class SchedulingParams {
        private String policy;
        private int targetWPH;
        private int maxWafersInSystem;
        private int waferStartIntervalSec;
        private int dwellSafetyMarginSec;

        public String getPolicy() { return policy; }
        public void setPolicy(String v) { this.policy = v; }
        public int getTargetWPH() { return targetWPH; }
        public void setTargetWPH(int v) { this.targetWPH = v; }
        public int getMaxWafersInSystem() { return maxWafersInSystem; }
        public void setMaxWafersInSystem(int v) { this.maxWafersInSystem = v; }
        public int getWaferStartIntervalSec() { return waferStartIntervalSec; }
        public void setWaferStartIntervalSec(int v) { this.waferStartIntervalSec = v; }
        public int getDwellSafetyMarginSec() { return dwellSafetyMarginSec; }
        public void setDwellSafetyMarginSec(int v) { this.dwellSafetyMarginSec = v; }
    }

    public static class RobotParams {
        private String strategy;
        private boolean swapEnabled;
        private boolean priorityReturnWafer;

        public String getStrategy() { return strategy; }
        public void setStrategy(String v) { this.strategy = v; }
        public boolean isSwapEnabled() { return swapEnabled; }
        public void setSwapEnabled(boolean v) { this.swapEnabled = v; }
        public boolean isPriorityReturnWafer() { return priorityReturnWafer; }
        public void setPriorityReturnWafer(boolean v) { this.priorityReturnWafer = v; }
    }

    public static class TimingParams {
        private int loadlockPumpTimeSec;
        private int loadlockVentTimeSec;
        private int loadlockLoadTimeSec;
        private int loadlockUnloadTimeSec;
        private int passthroughTransferTimeSec;
        private int coolingStationCoolTimeSec;

        public int getLoadlockPumpTimeSec() { return loadlockPumpTimeSec; }
        public void setLoadlockPumpTimeSec(int v) { this.loadlockPumpTimeSec = v; }
        public int getLoadlockVentTimeSec() { return loadlockVentTimeSec; }
        public void setLoadlockVentTimeSec(int v) { this.loadlockVentTimeSec = v; }
        public int getLoadlockLoadTimeSec() { return loadlockLoadTimeSec; }
        public void setLoadlockLoadTimeSec(int v) { this.loadlockLoadTimeSec = v; }
        public int getLoadlockUnloadTimeSec() { return loadlockUnloadTimeSec; }
        public void setLoadlockUnloadTimeSec(int v) { this.loadlockUnloadTimeSec = v; }
        public int getPassthroughTransferTimeSec() { return passthroughTransferTimeSec; }
        public void setPassthroughTransferTimeSec(int v) { this.passthroughTransferTimeSec = v; }
        public int getCoolingStationCoolTimeSec() { return coolingStationCoolTimeSec; }
        public void setCoolingStationCoolTimeSec(int v) { this.coolingStationCoolTimeSec = v; }
    }

    public static class SimulationParams {
        private int speed;
        private int totalWafers;
        private int timeStepMs;

        public int getSpeed() { return speed; }
        public void setSpeed(int v) { this.speed = v; }
        public int getTotalWafers() { return totalWafers; }
        public void setTotalWafers(int v) { this.totalWafers = v; }
        public int getTimeStepMs() { return timeStepMs; }
        public void setTimeStepMs(int v) { this.timeStepMs = v; }
    }

    public Map<String, RecipeConfig> getRecipes() { return recipes; }
    public void setRecipes(Map<String, RecipeConfig> v) { this.recipes = v; }
    public List<String> getWaferFlow() { return waferFlow; }
    public void setWaferFlow(List<String> v) { this.waferFlow = v; }
    public SchedulingParams getScheduling() { return scheduling; }
    public void setScheduling(SchedulingParams v) { this.scheduling = v; }
    public RobotParams getRobot() { return robot; }
    public void setRobot(RobotParams v) { this.robot = v; }
    public TimingParams getTiming() { return timing; }
    public void setTiming(TimingParams v) { this.timing = v; }
    public SimulationParams getSimulation() { return simulation; }
    public void setSimulation(SimulationParams v) { this.simulation = v; }
}
