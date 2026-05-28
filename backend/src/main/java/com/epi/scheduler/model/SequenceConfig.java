package com.epi.scheduler.model;

import java.util.List;
import java.util.Map;

public class SequenceConfig {
    private String name;
    private String description;
    private List<FlowStep> flow;
    private Map<String, Map<String, RobotOpRef>> robotOperations;

    public static class FlowStep {
        private int step;
        private String station;
        private String action;
        private String robot;
        private String robotIn;
        private String robotOut;
        private String timeKey;
        private String recipeKey;
        private String from;
        private String next;

        public int getStep() { return step; }
        public void setStep(int step) { this.step = step; }
        public String getStation() { return station; }
        public void setStation(String station) { this.station = station; }
        public String getAction() { return action; }
        public void setAction(String action) { this.action = action; }
        public String getRobot() { return robot; }
        public void setRobot(String robot) { this.robot = robot; }
        public String getRobotIn() { return robotIn; }
        public void setRobotIn(String robotIn) { this.robotIn = robotIn; }
        public String getRobotOut() { return robotOut; }
        public void setRobotOut(String robotOut) { this.robotOut = robotOut; }
        public String getTimeKey() { return timeKey; }
        public void setTimeKey(String timeKey) { this.timeKey = timeKey; }
        public String getRecipeKey() { return recipeKey; }
        public void setRecipeKey(String recipeKey) { this.recipeKey = recipeKey; }
        public String getFrom() { return from; }
        public void setFrom(String from) { this.from = from; }
        public String getNext() { return next; }
        public void setNext(String next) { this.next = next; }
    }

    public static class RobotOpRef {
        private String opKey;
        public String getOpKey() { return opKey; }
        public void setOpKey(String opKey) { this.opKey = opKey; }
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public List<FlowStep> getFlow() { return flow; }
    public void setFlow(List<FlowStep> flow) { this.flow = flow; }
    public Map<String, Map<String, RobotOpRef>> getRobotOperations() { return robotOperations; }
    public void setRobotOperations(Map<String, Map<String, RobotOpRef>> robotOperations) { this.robotOperations = robotOperations; }
}
