package com.epi.scheduler.model;

public class GanttEntry {
    private String waferId;
    private String location;
    private String type;
    private int startTimeSec;
    private int endTimeSec;
    private String color;

    public GanttEntry() {}

    public GanttEntry(String waferId, String location, String type, int startTimeSec, int endTimeSec, String color) {
        this.waferId = waferId;
        this.location = location;
        this.type = type;
        this.startTimeSec = startTimeSec;
        this.endTimeSec = endTimeSec;
        this.color = color;
    }

    public String getWaferId() { return waferId; }
    public void setWaferId(String v) { this.waferId = v; }
    public String getLocation() { return location; }
    public void setLocation(String v) { this.location = v; }
    public String getType() { return type; }
    public void setType(String v) { this.type = v; }
    public int getStartTimeSec() { return startTimeSec; }
    public void setStartTimeSec(int v) { this.startTimeSec = v; }
    public int getEndTimeSec() { return endTimeSec; }
    public void setEndTimeSec(int v) { this.endTimeSec = v; }
    public String getColor() { return color; }
    public void setColor(String v) { this.color = v; }
}
