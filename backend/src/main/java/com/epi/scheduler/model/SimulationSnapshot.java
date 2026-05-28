package com.epi.scheduler.model;

import java.util.List;
import java.util.Map;

public class SimulationSnapshot {
    private int currentTimeSec;
    private String status;
    private Map<String, ChamberSnapshot> chambers;
    private Map<String, RobotSnapshot> robots;
    private List<WaferSnapshot> wafers;
    private List<String> recentEvents;
    private double currentWPH;
    private int completedWafers;
    private int totalWafers;

    public static class ChamberSnapshot {
        private String id;
        private String type;
        private String state;
        private String waferId;
        private int remainingTimeSec;
        private int totalTimeSec;
        private int waferCount;

        public String getId() { return id; }
        public void setId(String v) { this.id = v; }
        public String getType() { return type; }
        public void setType(String v) { this.type = v; }
        public String getState() { return state; }
        public void setState(String v) { this.state = v; }
        public String getWaferId() { return waferId; }
        public void setWaferId(String v) { this.waferId = v; }
        public int getRemainingTimeSec() { return remainingTimeSec; }
        public void setRemainingTimeSec(int v) { this.remainingTimeSec = v; }
        public int getTotalTimeSec() { return totalTimeSec; }
        public void setTotalTimeSec(int v) { this.totalTimeSec = v; }
        public int getWaferCount() { return waferCount; }
        public void setWaferCount(int v) { this.waferCount = v; }
    }

    public static class RobotSnapshot {
        private String id;
        private String tmId;
        private String state;
        private String arm1WaferId;
        private String arm2WaferId;
        private String currentAction;
        private int remainingTimeSec;

        public String getId() { return id; }
        public void setId(String v) { this.id = v; }
        public String getTmId() { return tmId; }
        public void setTmId(String v) { this.tmId = v; }
        public String getState() { return state; }
        public void setState(String v) { this.state = v; }
        public String getArm1WaferId() { return arm1WaferId; }
        public void setArm1WaferId(String v) { this.arm1WaferId = v; }
        public String getArm2WaferId() { return arm2WaferId; }
        public void setArm2WaferId(String v) { this.arm2WaferId = v; }
        public String getCurrentAction() { return currentAction; }
        public void setCurrentAction(String v) { this.currentAction = v; }
        public int getRemainingTimeSec() { return remainingTimeSec; }
        public void setRemainingTimeSec(int v) { this.remainingTimeSec = v; }
    }

    public static class WaferSnapshot {
        private String id;
        private int foupIndex;
        private int slotIndex;
        private String location;
        private String state;
        private int flowStep;

        public String getId() { return id; }
        public void setId(String v) { this.id = v; }
        public int getFoupIndex() { return foupIndex; }
        public void setFoupIndex(int v) { this.foupIndex = v; }
        public int getSlotIndex() { return slotIndex; }
        public void setSlotIndex(int v) { this.slotIndex = v; }
        public String getLocation() { return location; }
        public void setLocation(String v) { this.location = v; }
        public String getState() { return state; }
        public void setState(String v) { this.state = v; }
        public int getFlowStep() { return flowStep; }
        public void setFlowStep(int v) { this.flowStep = v; }
    }

    public int getCurrentTimeSec() { return currentTimeSec; }
    public void setCurrentTimeSec(int v) { this.currentTimeSec = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public Map<String, ChamberSnapshot> getChambers() { return chambers; }
    public void setChambers(Map<String, ChamberSnapshot> v) { this.chambers = v; }
    public Map<String, RobotSnapshot> getRobots() { return robots; }
    public void setRobots(Map<String, RobotSnapshot> v) { this.robots = v; }
    public List<WaferSnapshot> getWafers() { return wafers; }
    public void setWafers(List<WaferSnapshot> v) { this.wafers = v; }
    public List<String> getRecentEvents() { return recentEvents; }
    public void setRecentEvents(List<String> v) { this.recentEvents = v; }
    public double getCurrentWPH() { return currentWPH; }
    public void setCurrentWPH(double v) { this.currentWPH = v; }
    public int getCompletedWafers() { return completedWafers; }
    public void setCompletedWafers(int v) { this.completedWafers = v; }
    public int getTotalWafers() { return totalWafers; }
    public void setTotalWafers(int v) { this.totalWafers = v; }
}
