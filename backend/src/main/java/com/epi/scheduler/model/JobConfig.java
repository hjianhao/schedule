package com.epi.scheduler.model;

import java.util.List;

public class JobConfig {
    private String name;
    private List<ControlJob> controlJobs;

    public static class ControlJob {
        private String id;
        private String name;
        private String mode = "parallel"; // "serial" or "parallel"
        private List<ProcessJob> processJobs;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public List<ProcessJob> getProcessJobs() { return processJobs; }
        public void setProcessJobs(List<ProcessJob> processJobs) { this.processJobs = processJobs; }

        public int getTotalWaferCount() {
            int total = 0;
            if (processJobs != null) {
                for (ProcessJob pj : processJobs) {
                    if (pj.wafers != null && pj.wafers.subsets != null) {
                        for (WaferSubset subset : pj.wafers.subsets) {
                            if (subset.wafers != null) {
                                for (String w : subset.wafers) {
                                    total += parseWaferRange(w).size();
                                }
                            }
                        }
                    }
                }
            }
            return total;
        }
    }

    public static class ProcessJob {
        private String id;
        private String sequenceName;
        private WaferCollection wafers;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getSequenceName() { return sequenceName; }
        public void setSequenceName(String sequenceName) { this.sequenceName = sequenceName; }
        public WaferCollection getWafers() { return wafers; }
        public void setWafers(WaferCollection wafers) { this.wafers = wafers; }
    }

    public static class WaferCollection {
        private List<WaferSubset> subsets;

        public List<WaferSubset> getSubsets() { return subsets; }
        public void setSubsets(List<WaferSubset> subsets) { this.subsets = subsets; }
    }

    public static class WaferSubset {
        private String lp;
        private List<String> wafers;

        public String getLp() { return lp; }
        public void setLp(String lp) { this.lp = lp; }
        public List<String> getWafers() { return wafers; }
        public void setWafers(List<String> wafers) { this.wafers = wafers; }
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public List<ControlJob> getControlJobs() { return controlJobs; }
    public void setControlJobs(List<ControlJob> controlJobs) { this.controlJobs = controlJobs; }

    /**
     * Parse a wafer range string like "1-25" or single "5" into a list of wafer numbers.
     */
    public static List<Integer> parseWaferRange(String range) {
        List<Integer> result = new java.util.ArrayList<>();
        String trimmed = range.trim();
        if (trimmed.contains("-")) {
            String[] parts = trimmed.split("-");
            int start = Integer.parseInt(parts[0].trim());
            int end = Integer.parseInt(parts[1].trim());
            for (int i = start; i <= end; i++) {
                result.add(i);
            }
        } else {
            result.add(Integer.parseInt(trimmed));
        }
        return result;
    }

    /**
     * Get total wafer count from all CJ/PJ/subsets.
     */
    public int getTotalWaferCount() {
        int total = 0;
        if (controlJobs != null) {
            for (ControlJob cj : controlJobs) {
                total += cj.getTotalWaferCount();
            }
        }
        return total;
    }

    public ControlJob findControlJob(String cjId) {
        if (controlJobs == null) return null;
        return controlJobs.stream().filter(cj -> cj.getId().equals(cjId)).findFirst().orElse(null);
    }
}
