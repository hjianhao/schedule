package com.epi.scheduler.engine;

import com.epi.scheduler.model.*;

import java.util.*;
import java.util.stream.Collectors;

public class SchedulerEngine {

    public enum SimStatus { IDLE, RUNNING, PAUSED, COMPLETED }
    public enum ChamberState { IDLE, LOADING, PUMPING, READY, PROCESSING, DONE, VENTING, UNLOADING, COOLING, CLEANING, PURGING }

    private final DeviceConfig deviceConfig;
    private final ScheduleConfig scheduleConfig;
    private AmConfig amConfig = null;
    private final Random random = new Random(42);

    private int currentTimeSec = 0;
    private SimStatus status = SimStatus.IDLE;

    private final Map<String, Chamber> chambers = new LinkedHashMap<>();
    private final Map<String, Robot> robots = new LinkedHashMap<>();
    private final List<Wafer> wafers = new ArrayList<>();
    private final List<GanttEntry> ganttEntries = Collections.synchronizedList(new ArrayList<>());
    private final List<String> eventLog = new ArrayList<>();
    private final List<String> recentEvents = new ArrayList<>();
    private final Map<String, FoupSlot> foupSlots = new LinkedHashMap<>();

    private int completedWafers = 0;
    private int firstCompletionTime = -1;
    private int lastWaferStartTime = -99999;
    private int effectiveStartInterval = 0;
    private JobConfig.ControlJob activeCJ = null;
    private int currentPJIndex = 0;
    private final Set<String> onloadCleanedChambers = new java.util.HashSet<>();
    private final Set<String> firstWaferSeen = new java.util.HashSet<>();
    private final Set<String> awaitingOnloadClean = new java.util.HashSet<>();
    private final Map<String, String> chamberCleanType = new java.util.HashMap<>();
    private final List<SimulationSnapshot> replaySnapshots = new ArrayList<>();
    private int replaySnapshotIntervalSec = 10; // capture every 10s to catch robot moves
    private int nextReplaySnapshotTime = 0;
    private int epiAssignIndex = 0;
    private int cleanGanttCounter = 0;
    private final Map<String, Integer> cleanCounterPerChamber = new java.util.HashMap<>();
    private final Map<String, Integer> lastOnloadCleanStartPerType = new java.util.HashMap<>();
    private int wafersEnteredPreClean = 0; // counter for EPI OnLoadClean triggering
    private final Map<String, Integer> scheduledCleanStarts = new java.util.HashMap<>();
    private final Map<String, Integer> lastEpiEntryTime = new java.util.HashMap<>();
    private final Map<String, Integer> idleStartTime = new java.util.HashMap<>();

    public static class Chamber {
        String id; String type;
        ChamberState state = ChamberState.IDLE;
        String waferId = null;
        int remainingTime = 0;
        int totalTime = 0;
        int processStartTime = 0;
        boolean forwardDirection = true;
        int lastUsedTime = 0;
        List<String> waferIds = new ArrayList<>(); // for BatchLL: current wafers inside
        int batchTotal = 0; // for BatchLL: total wafers loaded in this batch
        boolean coolingStation = false;
        String warnedDwellWafer = null; // dedup dwell warnings per wafer per chamber

        public Chamber(String id, String type) { this.id = id; this.type = type; }
    }

    public static class Robot {
        public String id; public String tmId;
        public boolean busy = false;
        public int busyUntil = 0;
        public String armWaferId = null;
        public String currentAction = "";
        public String sourceChamber = "";
        public String targetChamber = "";
        Runnable onComplete = null;

        public Robot(String id, String tmId) { this.id = id; this.tmId = tmId; }
    }

    public static class Wafer {
        String id; int foupIndex; int slotIndex;
        String location; int flowStep = 0; String state = "IN_FOUP";
        int enteredCurrentLocation = 0;

        public Wafer(String id, int foupIndex, int slotIndex) {
            this.id = id; this.foupIndex = foupIndex; this.slotIndex = slotIndex;
            this.location = "FOUP_" + foupIndex;
        }
    }

    public static class FoupSlot {
        public String id; public int foupIndex; public int slotIndex; public String waferId;
        public String state = "NONE"; // NONE(no wafer) | FILLED(gray) | EMPTY(dark) | DONE(green)

        public FoupSlot(int fi, int si) {
            this.foupIndex = fi; this.slotIndex = si;
            this.id = fi + "_" + si;
        }
    }

    public SchedulerEngine(DeviceConfig dc, ScheduleConfig sc) {
        this.deviceConfig = dc; this.scheduleConfig = sc;
        initialize(null);
    }

    public SchedulerEngine(DeviceConfig dc, ScheduleConfig sc, JobConfig.ControlJob cj) {
        this.deviceConfig = dc; this.scheduleConfig = sc;
        this.activeCJ = cj;
        initialize(cj);
    }

    public void setAmConfig(AmConfig am) { this.amConfig = am; }

    private void initialize(JobConfig.ControlJob cj) {
        for (DeviceConfig.LoadlockConfig ll : deviceConfig.getLoadlocks())
            chambers.put(ll.getId(), new Chamber(ll.getId(), "LOADLOCK"));
        for (DeviceConfig.ChamberConfig cc : deviceConfig.getChambers())
            chambers.put(cc.getId(), new Chamber(cc.getId(), cc.getType()));
        for (DeviceConfig.PassthroughConfig pt : deviceConfig.getPassthroughs()) {
            int slots = pt.getSlots() > 0 ? pt.getSlots() : 1;
            for (int s = 0; s < slots; s++) {
                String slotId = pt.getId() + "_S" + s;
                Chamber c = new Chamber(slotId, "PASSTHROUGH");
                if (pt.getCoolingStationSlot() != null && s == pt.getCoolingStationSlot()) {
                    c.coolingStation = true;
                }
                chambers.put(slotId, c);
            }
        }
        // Aligner (inside EFEM)
        if (deviceConfig.getEfem() != null && deviceConfig.getEfem().getAligner() != null) {
            chambers.put("ALIGNER", new Chamber("ALIGNER", "ALIGNER"));
        }
        for (DeviceConfig.TransferModuleConfig tm : deviceConfig.getTransferModules())
            for (DeviceConfig.RobotConfig rc : tm.getRobots())
                robots.put(rc.getId(), new Robot(rc.getId(), tm.getId()));

        // ATM robot
        if (deviceConfig.getEfem() != null && deviceConfig.getEfem().getAtmRobot() != null) {
            DeviceConfig.AtmRobotConfig atm = deviceConfig.getEfem().getAtmRobot();
            robots.put(atm.getId(), new Robot(atm.getId(), "EFEM"));
        }

        int slotsPerFoup = getSlotsPerFoup();
        int foupCount = getFoupCount();

        // Create wafers from job config or default
        if (cj != null) {
            for (JobConfig.ProcessJob pj : cj.getProcessJobs()) {
                if (pj.getWafers() == null || pj.getWafers().getSubsets() == null) continue;
                for (JobConfig.WaferSubset subset : pj.getWafers().getSubsets()) {
                    int lpIdx = Integer.parseInt(subset.getLp().substring(2)) - 1; // "LP1" -> 0
                    if (subset.getWafers() == null) continue;
                    for (String w : subset.getWafers()) {
                        for (int wn : JobConfig.parseWaferRange(w)) {
                            int fi = lpIdx;
                            int si = wn - 1; // wafer 1 -> slot 0
                            String wid = "W" + (lpIdx + 1) + "." + wn;
                            wafers.add(new Wafer(wid, fi, si));
                        }
                    }
                }
            }
        } else {
            int totalWafers = scheduleConfig.getSimulation().getTotalWafers();
            for (int i = 0; i < totalWafers; i++) {
                int fi = i / slotsPerFoup;
                int si = i % slotsPerFoup;
                wafers.add(new Wafer("W" + (i + 1), fi, si));
            }
        }

        for (int fi = 0; fi < foupCount; fi++)
            for (int si = 0; si < slotsPerFoup; si++) {
                FoupSlot fs = new FoupSlot(fi, si);
                foupSlots.put(fs.id, fs);
            }
        for (Wafer w : wafers) {
            FoupSlot fs = foupSlots.get(w.foupIndex + "_" + w.slotIndex);
            if (fs != null) { fs.waferId = w.id; fs.state = "FILLED"; }
        }

        // Initialize idle start time for all chambers (they start IDLE)
        for (Chamber c : chambers.values()) {
            idleStartTime.put(c.id, currentTimeSec);
        }

        int interval = scheduleConfig.getScheduling().getWaferStartIntervalSec();
        if (interval <= 0) {
            long epiCount = deviceConfig.getChambers().stream().filter(c -> "EPI".equals(c.getType())).count();
            ScheduleConfig.RecipeConfig epiRecipe = scheduleConfig.getRecipes().get("EPI");
            effectiveStartInterval = epiRecipe != null && epiCount > 0
                    ? (int) Math.round(epiRecipe.getAvgProcessTimeSec() / (double) epiCount) : 450;
        } else {
            effectiveStartInterval = interval;
        }
    }

    public synchronized void reset() {
        currentTimeSec = 0; status = SimStatus.IDLE;
        completedWafers = 0; firstCompletionTime = -1; lastWaferStartTime = -99999;
        chambers.values().forEach(c -> {
            c.state = ChamberState.IDLE; c.waferId = null; c.remainingTime = 0; c.batchTotal = 0;
            c.totalTime = 0; c.forwardDirection = true; c.lastUsedTime = 0; c.waferIds.clear();
            c.warnedDwellWafer = null;
        });
        robots.values().forEach(r -> {
            r.busy = false; r.busyUntil = 0; r.armWaferId = null;
            r.currentAction = ""; r.onComplete = null;
        });
        wafers.clear();
        int slotsPerFoup = getSlotsPerFoup();
        if (activeCJ != null) {
            for (JobConfig.ProcessJob pj : activeCJ.getProcessJobs()) {
                if (pj.getWafers() == null || pj.getWafers().getSubsets() == null) continue;
                for (JobConfig.WaferSubset subset : pj.getWafers().getSubsets()) {
                    int lpIdx = Integer.parseInt(subset.getLp().substring(2)) - 1;
                    if (subset.getWafers() == null) continue;
                    for (String w : subset.getWafers()) {
                        for (int wn : JobConfig.parseWaferRange(w)) {
                            wafers.add(new Wafer("W" + (lpIdx + 1) + "." + wn, lpIdx, wn - 1));
                        }
                    }
                }
            }
        } else {
            int totalWafers = scheduleConfig.getSimulation().getTotalWafers();
            for (int i = 0; i < totalWafers; i++) {
                int fi = i / slotsPerFoup; int si = i % slotsPerFoup;
                wafers.add(new Wafer("W" + (i + 1), fi, si));
            }
        }
        for (FoupSlot fs : foupSlots.values()) { fs.state = "NONE"; fs.waferId = null; }
        for (Wafer w : wafers) {
            FoupSlot fs = foupSlots.get(w.foupIndex + "_" + w.slotIndex);
            if (fs != null) { fs.waferId = w.id; fs.state = "FILLED"; }
        }
        ganttEntries.clear(); eventLog.clear(); recentEvents.clear();
        pendingBatch.clear();
        alignerDestLL = null;
        lastScheduledLL = null;
        currentPJIndex = 0;
        onloadCleanedChambers.clear();
        firstWaferSeen.clear();
        awaitingOnloadClean.clear();
        chamberCleanType.clear();
        lastOnloadCleanStartPerType.clear();
        wafersEnteredPreClean = 0;
        replaySnapshots.clear();
        nextReplaySnapshotTime = 0;
        cleanCounterPerChamber.clear();
        idleStartTime.clear();
        epiAssignIndex = 0;
        scheduledCleanStarts.clear();
        lastEpiEntryTime.clear();
    }

    public synchronized void start() { status = SimStatus.RUNNING; }
    public synchronized void pause() { if (status == SimStatus.RUNNING) status = SimStatus.PAUSED; }

    public synchronized boolean tick() {
        if (status != SimStatus.RUNNING) return false;
        currentTimeSec++;
        updateChamberTimers();
        checkMaxDwellTimes();
        updateRobots();
        scheduleATM();
        scheduleTM1();
        scheduleTM2();
        manageBatchLL();
        prepareBatch();
        triggerOnLoadClean();
        triggerIdlePurge();
        healWaferLocations();
        captureReplaySnapshot();
        if (completedWafers >= wafers.size()) { status = SimStatus.COMPLETED; addEvent("ALL WAFERS COMPLETED!"); }
        return true;
    }

    public synchronized boolean step() {
        if (status != SimStatus.RUNNING) status = SimStatus.RUNNING;
        boolean r = tick();
        status = SimStatus.PAUSED;
        return r;
    }

    // --- Chamber updates ---
    private void updateChamberTimers() {
        for (Chamber c : chambers.values()) {
            if (c.remainingTime > 0) {
                c.remainingTime--;
                if (c.remainingTime == 0) handleChamberTimerDone(c);
            }
        }
    }

    private void handleChamberTimerDone(Chamber c) {
        switch (c.type) {
            case "LOADLOCK":
                if (c.state == ChamberState.PUMPING) {
                    c.state = ChamberState.READY;
                    addEvent(c.id + " pump complete, ready (wafers: " + c.waferIds.size() + ")");
                } else if (c.state == ChamberState.VENTING) {
                    c.state = ChamberState.DONE;
                    addEvent(c.id + " vent complete, ready for unload");
                } else if (c.state == ChamberState.LOADING) {
                    // Batch loading done, start pump
                    // Already have wafers in waferIds from the loading step
                    c.state = ChamberState.PUMPING;
                    c.remainingTime = scheduleConfig.getTiming().getLoadlockPumpTimeSec();
                    c.totalTime = c.remainingTime;
                    addEvent(c.id + " batch loaded (" + c.waferIds.size() + " wafers), pumping down");
                } else if (c.state == ChamberState.UNLOADING) {
                    // All wafers unloaded to FOUP
                    List<String> ids = new ArrayList<>(c.waferIds);
                    for (String wid : ids) {
                        Wafer w = findWafer(wid);
                        if (w != null) {
                            FoupSlot fs = foupSlots.get(w.foupIndex + "_" + w.slotIndex);
                            if (fs != null) { fs.state = "DONE"; fs.waferId = wid; }
                            w.location = "FOUP_" + w.foupIndex;
                            w.flowStep = 17; w.state = "COMPLETED";
                            completedWafers++;
                            if (firstCompletionTime < 0) firstCompletionTime = currentTimeSec;
                        }
                    }
                    c.waferIds.clear(); c.waferId = null; c.batchTotal = 0;
                    c.state = ChamberState.IDLE;
                    addEvent(c.id + " batch unload complete, " + completedWafers + " total done");
                }
                break;
            case "PRECLEAN": case "EPI":
                if (c.state == ChamberState.CLEANING || c.state == ChamberState.PURGING) {
                    c.state = ChamberState.IDLE;
                    idleStartTime.put(c.id, currentTimeSec); // reset idle timer after clean
                    closeCleanGantt(c.id);
                    String cleanType = chamberCleanType.getOrDefault(c.id, "Clean");
                    addEvent(c.id + " " + cleanType + " complete");
                    chamberCleanType.remove(c.id);
                } else if (c.state == ChamberState.PROCESSING) {
                    c.state = ChamberState.DONE;
                    addEvent(c.id + " processing done for " + c.waferId);
                    Wafer w = findWafer(c.waferId);
                    if (w != null) w.state = "DONE";
                }
                break;
            case "PASSTHROUGH":
                if (c.state == ChamberState.COOLING) {
                    c.state = ChamberState.READY;
                    addEvent(c.id + " cooling complete for " + c.waferId);
                }
                break;
            case "ALIGNER":
                if (c.state == ChamberState.PROCESSING) {
                    c.state = ChamberState.DONE;
                    addEvent("ALIGNER alignment done for " + c.waferId);
                }
                break;
        }
    }

    private void updateRobots() {
        for (Robot r : robots.values()) {
            if (r.busy && currentTimeSec >= r.busyUntil) {
                r.busy = false; r.currentAction = "";
                if (r.onComplete != null) { r.onComplete.run(); r.onComplete = null; }
            }
        }
    }

    // --- Batch LL management ---
    private void manageBatchLL() {
        for (DeviceConfig.LoadlockConfig llc : deviceConfig.getLoadlocks()) {
            Chamber ll = chambers.get(llc.getId());
            if (ll.state == ChamberState.DONE && !ll.waferIds.isEmpty()) {
                // Vent complete, now unload wafers to FOUP
                ll.state = ChamberState.UNLOADING;
                ll.remainingTime = scheduleConfig.getTiming().getLoadlockUnloadTimeSec();
                ll.totalTime = ll.remainingTime;
                addEvent(ll.id + " unloading " + ll.waferIds.size() + " wafers to FOUP");
            }
        }
    }

    // Pending batch load: LL id -> list of wafer IDs to load (ordered)
    private final Map<String, List<String>> pendingBatch = new LinkedHashMap<>();
    private String lastScheduledLL = null;

    private void prepareBatch() {
        boolean isSerial = activeCJ != null && "serial".equals(activeCJ.getMode());

        for (DeviceConfig.LoadlockConfig llc : deviceConfig.getLoadlocks()) {
            Chamber ll = chambers.get(llc.getId());
            if (ll.state != ChamberState.IDLE || !ll.waferIds.isEmpty()) continue;
            if (pendingBatch.containsKey(ll.id)) continue;

            // Serial mode: only start next batch when no unprocessed wafers remain
            if (isSerial) {
                boolean hasUnprocessed = false;
                for (Chamber c : chambers.values()) {
                    if (!c.type.equals("LOADLOCK")) continue;
                    for (String wid : c.waferIds) {
                        Wafer wf = findWafer(wid);
                        if (wf != null && wf.flowStep == 1) { hasUnprocessed = true; break; }
                    }
                    if (hasUnprocessed) break;
                }
                for (List<String> batch : pendingBatch.values()) {
                    if (!batch.isEmpty()) { hasUnprocessed = true; break; }
                }
                if (hasUnprocessed) break;
            }

            List<Wafer> toLoad = wafers.stream()
                    .filter(w -> w.flowStep == 0 && !isWaferInPendingBatch(w.id))
                    .limit(Math.min(llc.getCapacity(), wafers.size() - completedWafers))
                    .collect(Collectors.toList());
            if (toLoad.isEmpty()) continue;

            int foupIdx = toLoad.get(0).foupIndex;
            toLoad = toLoad.stream().filter(w -> w.foupIndex == foupIdx)
                    .limit(llc.getCapacity()).collect(Collectors.toList());
            if (toLoad.isEmpty()) continue;

            List<String> waferIds = toLoad.stream().map(w -> w.id).collect(Collectors.toList());
            pendingBatch.put(ll.id, waferIds);
            ll.batchTotal = waferIds.size();
            addEvent(ll.id + " batch prepared: " + waferIds.size() + " wafers (ATM loading)");
        }
    }

    private boolean isWaferInPendingBatch(String waferId) {
        return pendingBatch.values().stream().anyMatch(list -> list.contains(waferId));
    }

    // Aligner destination tracking
    private String alignerDestLL = null;

    private void scheduleATM() {
        // Check if ALIGNER has a wafer ready to move to LL
        Chamber aligner = chambers.get("ALIGNER");
        Robot atm = robots.get("ATM1");
        if (atm == null || atm.busy) return;

        if (aligner != null && aligner.state == ChamberState.DONE && alignerDestLL != null) {
            // Step 2: ALIGNER → LL
            String wId = aligner.waferId;
            String llId = alignerDestLL;
            alignerDestLL = null;
            aligner.waferId = null;
            aligner.state = ChamberState.IDLE;

            int atmTime = getAtmXferAlignerToLL();
            atm.busy = true; atm.busyUntil = currentTimeSec + atmTime;
            atm.currentAction = "ALIGNER→" + llId;
            atm.armWaferId = wId;
            atm.sourceChamber = "ALIGNER";
            atm.targetChamber = llId;

            String llIdFinal = llId;
            atm.onComplete = () -> {
                Chamber ll = chambers.get(llIdFinal);
                Wafer wafer = findWafer(wId);
                if (wafer != null) {
                    wafer.flowStep = 1; wafer.state = "LOADING"; wafer.location = ll.id;
                    FoupSlot fs = foupSlots.get(wafer.foupIndex + "_" + wafer.slotIndex);
                    if (fs != null) { fs.state = "EMPTY"; fs.waferId = wId; }
                }
                ll.waferIds.add(wId);
                ll.waferId = wId;
                atm.armWaferId = null;
                addEvent("ATM: loaded " + wId + " → " + llIdFinal + " (" + ll.waferIds.size() + "/" + ll.batchTotal + ")");

                List<String> remaining = pendingBatch.get(llIdFinal);
                if (remaining != null && remaining.isEmpty()) {
                    pendingBatch.remove(llIdFinal);
                    ll.state = ChamberState.PUMPING;
                    ll.remainingTime = scheduleConfig.getTiming().getLoadlockPumpTimeSec();
                    ll.totalTime = ll.remainingTime;
                    addGanttEntry("BATCH", ll.id, "LOADLOCK", currentTimeSec, -1, "#2196F3");
                    addEvent(ll.id + " batch complete (" + ll.waferIds.size() + " wafers), pumping down");
                }
            };
            addEvent("ATM: picking " + wId + " from ALIGNER → " + llId);
            return;
        }

        // Find active batch: pick the one with fewest remaining wafers (balance load)
        String llId = null;
        int minSize = Integer.MAX_VALUE;
        for (Map.Entry<String, List<String>> e : pendingBatch.entrySet()) {
            int size = e.getValue().size();
            // Skip empty batches and batches whose LL is not IDLE (already pumping/processing)
            Chamber llChamber = chambers.get(e.getKey());
            if (llChamber != null && llChamber.state == ChamberState.PUMPING) continue;
            if (size > 0 && size < minSize) {
                minSize = size; llId = e.getKey();
            }
        }
        if (llId == null) return;
        if (llId == null) return;

        if (aligner == null) {
            scheduleATM_Direct(llId, atm);
            return;
        }

        if (aligner.state == ChamberState.IDLE && aligner.waferId == null && alignerDestLL == null) {
            // Step 1: FOUP → ALIGNER
            // Step 1: FOUP → ALIGNER
            List<String> waiting = pendingBatch.get(llId);
            if (waiting.isEmpty()) return;

            String wId = waiting.remove(0);
            Wafer w = findWafer(wId);
            if (w == null) return;

            w.flowStep = 1; w.state = "LOADING";
            alignerDestLL = llId;

            String alignerId = "ALIGNER";
            int atmTime = getAtmXferFoupToAligner();
            atm.busy = true; atm.busyUntil = currentTimeSec + atmTime;
            atm.currentAction = "FOUP→ALIGNER";
            atm.armWaferId = wId;
            atm.sourceChamber = "LP" + (w.foupIndex + 1);
            atm.targetChamber = "ALIGNER";

            double alignTime = deviceConfig.getEfem().getAligner().getAlignTimeSec();
            atm.onComplete = () -> {
                Chamber al = chambers.get(alignerId);
                al.waferId = wId;
                al.state = ChamberState.PROCESSING;
                al.remainingTime = (int) Math.ceil(alignTime);
                al.totalTime = al.remainingTime;
                atm.armWaferId = null;
                Wafer wafer = findWafer(wId);
                if (wafer != null) wafer.location = "ALIGNER";
                addEvent("ATM: placed " + wId + " in ALIGNER (aligning " + String.format("%.1f", alignTime) + "s)");
            };
            addEvent("ATM: picking " + wId + " from LP" + (w.foupIndex + 1) + " → ALIGNER");
        }
    }

    private void scheduleATM_Direct(String llId, Robot atm) {
        List<String> waiting = pendingBatch.get(llId);
        if (waiting.isEmpty()) return;

        String wId = waiting.remove(0);
        Wafer w = findWafer(wId);
        if (w == null) return;

        w.flowStep = 1; w.state = "LOADING";

        int atmTime = getAtmXferFoupToAligner(); // fallback: use FOUP→Aligner timing
        atm.busy = true; atm.busyUntil = currentTimeSec + atmTime;
        atm.currentAction = "FOUP→" + llId;
        atm.armWaferId = wId;
        atm.sourceChamber = "LP" + (w.foupIndex + 1);
        atm.targetChamber = llId;

        String llIdFinal = llId;
        atm.onComplete = () -> {
            Chamber ll = chambers.get(llIdFinal);
            Wafer wafer = findWafer(wId);
            if (wafer != null) {
                wafer.flowStep = 1; wafer.state = "LOADING"; wafer.location = ll.id;
                FoupSlot fs = foupSlots.get(wafer.foupIndex + "_" + wafer.slotIndex);
                if (fs != null) { fs.state = "EMPTY"; fs.waferId = wId; }
            }
            ll.waferIds.add(wId);
            ll.waferId = wId;
            atm.armWaferId = null;
            addEvent("ATM: loaded " + wId + " → " + llIdFinal + " (" + ll.waferIds.size() + "/" + ll.batchTotal + ")");

            List<String> remaining = pendingBatch.get(llIdFinal);
            if (remaining != null && remaining.isEmpty()) {
                pendingBatch.remove(llIdFinal);
                ll.state = ChamberState.PUMPING;
                ll.remainingTime = scheduleConfig.getTiming().getLoadlockPumpTimeSec();
                ll.totalTime = ll.remainingTime;
                addGanttEntry("BATCH", ll.id, "LOADLOCK", currentTimeSec, -1, "#2196F3");
                addEvent(ll.id + " batch complete (" + ll.waferIds.size() + " wafers), pumping down");
            }
        };
        addEvent("ATM: picking " + wId + " from LP" + (w.foupIndex + 1) + " → " + llId);
    }

    // --- TM1 Scheduling (single arm, sequential) ---
    private void scheduleTM1() {
        Robot robot = robots.get("Robot1");
        if (robot == null || robot.busy) return;

        if (tryTM1ReturnFromPT(robot)) return;
        if (tryTM1PreCleanToPT(robot)) return;
        if (tryTM1LLToPreClean(robot)) return;
    }

    private boolean tryTM1ReturnFromPT(Robot robot) {
        Chamber ptRet = findPTWithReturnWafer();
        if (ptRet == null) return false;
        Chamber ll = findReadyBatchLL();
        if (ll == null) return false;

        String wId = ptRet.waferId;
        int dur = getOpDur("Robot1", "PT_TO_LL");
        robot.busy = true; robot.busyUntil = currentTimeSec + dur;
        robot.currentAction = "PT→LL: " + wId; robot.armWaferId = wId;
        robot.sourceChamber = ptRet.id; robot.targetChamber = ll.id;

        robot.onComplete = () -> {
            Chamber pt = chambers.get(ptRet.id);
            Chamber l = chambers.get(ll.id);
            pt.waferId = null; pt.state = ChamberState.IDLE; pt.forwardDirection = true;
            l.waferIds.add(wId);
            Wafer w = findWafer(wId);
            if (w != null) { w.location = ll.id; w.flowStep = 15; w.state = "RETURNED"; }
            robot.armWaferId = null;
            closeGanttEntry(wId, currentTimeSec);
            addGanttEntry(wId, ll.id, "LOADLOCK_RET", currentTimeSec, -1, "#9C27B0");
            addEvent("TM1: returned " + wId + " from " + ptRet.id + " to " + ll.id);
            checkBatchLLComplete(l);
        };
        addEvent("TM1: picking " + wId + " from " + ptRet.id + " → " + ll.id);
        return true;
    }

    private boolean tryTM1PreCleanToPT(Robot robot) {
        Chamber pcDone = findPreCleanDone();
        if (pcDone == null) return false;
        Chamber pt = findAvailablePTForward();
        if (pt == null) return false;
        if (!canMovePCToPT()) return false;

        String wId = pcDone.waferId;
        int dur = getOpDur("Robot1", "PRECLEAN_TO_PT");
        robot.busy = true; robot.busyUntil = currentTimeSec + dur;
        robot.currentAction = "PC→PT: " + wId; robot.armWaferId = wId;
        robot.sourceChamber = pcDone.id; robot.targetChamber = pt.id;

        robot.onComplete = () -> {
            Chamber pc = chambers.get(pcDone.id);
            Chamber p = chambers.get(pt.id);
            pc.waferId = null; pc.state = ChamberState.IDLE; pc.lastUsedTime = currentTimeSec;
            idleStartTime.put(pc.id, currentTimeSec); // track when PC becomes idle
            p.waferId = wId; p.state = ChamberState.READY; p.forwardDirection = true; p.lastUsedTime = currentTimeSec;
            Wafer w = findWafer(wId);
            if (w != null) { w.location = pt.id; w.flowStep = 8; w.state = "IN_PT"; }
            robot.armWaferId = null;
            closeGanttEntry(wId, currentTimeSec);
            addGanttEntry(wId, pt.id, "PASSTHROUGH", currentTimeSec, -1, "#FFEB3B");
            addEvent("TM1: moved " + wId + " from " + pcDone.id + " to " + pt.id);
        };
        addEvent("TM1: picking " + wId + " from " + pcDone.id + " → " + pt.id);
        return true;
    }

    private boolean tryTM1LLToPreClean(Robot robot) {
        Chamber ll = findReadyBatchLLWithUnprocessed();
        if (ll == null) return false;
        Chamber pc = findAvailablePreClean();
        if (pc == null) return false;
        if (!canPullWaferFromLL()) return false;

        String wId = getNextUnprocessedWafer(ll);
        if (wId == null) return false;

        int dur = getOpDur("Robot1", "LL_TO_PRECLEAN");
        int processTime = getProcessTime("PRECLEAN");
        robot.busy = true; robot.busyUntil = currentTimeSec + dur;
        robot.currentAction = "LL→PC: " + wId; robot.armWaferId = wId;
        robot.sourceChamber = ll.id; robot.targetChamber = pc.id;

        robot.onComplete = () -> {
            Chamber l = chambers.get(ll.id); Chamber c = chambers.get(pc.id);
            l.waferIds.remove(wId);
            c.waferId = wId; c.state = ChamberState.PROCESSING;
            c.remainingTime = processTime; c.totalTime = processTime;
            c.processStartTime = currentTimeSec; c.lastUsedTime = currentTimeSec;
            wafersEnteredPreClean++;
            Wafer w = findWafer(wId);
            if (w != null) { w.location = pc.id; w.flowStep = 5; w.state = "PROCESSING"; w.enteredCurrentLocation = currentTimeSec + dur; }
            robot.armWaferId = null;
            closeGanttEntry(wId, currentTimeSec);
            addGanttEntry(wId, pc.id, "PRECLEAN", currentTimeSec, -1, "#FF9800");
            addEvent("TM1: moved " + wId + " from " + ll.id + " to " + pc.id);
        };
        addEvent("TM1: picking " + wId + " from " + ll.id + " → " + pc.id);
        return true;
    }

    // --- TM2 Scheduling ---
    private void scheduleTM2() {
        Robot robot = robots.get("Robot2");
        if (robot == null || robot.busy) return;

        if (tryTM2EpiToPT(robot)) return;
        if (tryTM2PTToEpi(robot)) return;
    }

    private boolean tryTM2EpiToPT(Robot robot) {
        Chamber epiDone = findEpiDone();
        if (epiDone == null) return false;
        Chamber pt = findAvailablePTReturn();
        if (pt == null) return false;

        String wId = epiDone.waferId;
        int dur = getOpDur("Robot2", "EPI_TO_PT");
        robot.busy = true; robot.busyUntil = currentTimeSec + dur;
        robot.currentAction = "EPI→PT: " + wId; robot.armWaferId = wId;
        robot.sourceChamber = epiDone.id; robot.targetChamber = pt.id;

        robot.onComplete = () -> {
            Chamber epi = chambers.get(epiDone.id); Chamber p = chambers.get(pt.id);
            epi.waferId = null; epi.lastUsedTime = currentTimeSec;
            // Start 1X Clean when EPI is freed (skip if no more wafers need EPI)
            if (amConfig != null && amConfig.getCleanTimeForChamber("EPI") > 0) {
                boolean hasWaferNeedsEpi = wafers.stream().anyMatch(w -> w.flowStep > 0 && w.flowStep < 10);
                if (hasWaferNeedsEpi) {
                    double cleanTime = amConfig.getCleanTimeForChamber("EPI");
                    start1XClean(epi, cleanTime);
                } else {
                    epi.state = ChamberState.IDLE;
                }
            } else {
                epi.state = ChamberState.IDLE;
            }
            p.waferId = wId; p.forwardDirection = false; p.lastUsedTime = currentTimeSec;
            Wafer w = findWafer(wId);
            if (w != null) { w.location = pt.id; w.flowStep = 13; w.state = "IN_PT_RET"; }
            robot.armWaferId = null;
            closeGanttEntry(wId, currentTimeSec);
            addGanttEntry(wId, pt.id, "PT_RETURN", currentTimeSec, -1, "#E91E63");
            if (p.coolingStation) {
                int coolTime = scheduleConfig.getTiming().getCoolingStationCoolTimeSec();
                p.state = ChamberState.COOLING;
                p.remainingTime = coolTime; p.totalTime = coolTime;
                addEvent("TM2: moved " + wId + " from " + epiDone.id + " to " + pt.id + " (cooling " + coolTime + "s)");
            } else {
                p.state = ChamberState.READY;
                addEvent("TM2: moved " + wId + " from " + epiDone.id + " to " + pt.id);
            }
        };
        addEvent("TM2: picking " + wId + " from " + epiDone.id + " → " + pt.id);
        return true;
    }

    private boolean tryTM2PTToEpi(Robot robot) {
        Chamber ptFwd = findPTWithForwardWafer();
        if (ptFwd == null) return false;
        Chamber epi = findAvailableEpi();
        if (epi == null) return false;

        String wId = ptFwd.waferId;
        int dur = getOpDur("Robot2", "PT_TO_EPI");
        int processTime = getProcessTime("EPI");
        robot.busy = true; robot.busyUntil = currentTimeSec + dur;
        robot.currentAction = "PT→EPI: " + wId; robot.armWaferId = wId;
        robot.sourceChamber = ptFwd.id; robot.targetChamber = epi.id;

        robot.onComplete = () -> {
            Chamber p = chambers.get(ptFwd.id); Chamber e = chambers.get(epi.id);
            p.waferId = null; p.state = ChamberState.IDLE; p.forwardDirection = true;
            e.waferId = wId; e.state = ChamberState.PROCESSING;
            e.remainingTime = processTime; e.totalTime = processTime;
            e.processStartTime = currentTimeSec; e.lastUsedTime = currentTimeSec;
            lastEpiEntryTime.put(e.id, currentTimeSec); // track for clean scheduling
            Wafer w = findWafer(wId);
            if (w != null) { w.location = epi.id; w.flowStep = 10; w.state = "PROCESSING"; w.enteredCurrentLocation = currentTimeSec + dur; }
            robot.armWaferId = null;
            closeGanttEntry(wId, currentTimeSec);
            addGanttEntry(wId, epi.id, "EPI", currentTimeSec, -1, "#4CAF50");
            addEvent("TM2: moved " + wId + " from " + ptFwd.id + " to " + epi.id);
        };
        addEvent("TM2: picking " + wId + " from " + ptFwd.id + " → " + epi.id);
        return true;
    }

    // --- Batch LL helpers ---
    private void checkBatchLLComplete(Chamber ll) {
        if (!ll.type.equals("LOADLOCK")) return;
        if (ll.batchTotal <= 0 || ll.state != ChamberState.READY) return;

        long returned = ll.waferIds.stream()
                .filter(wid -> { Wafer w = findWafer(wid); return w != null && w.flowStep >= 15; })
                .count();

        if (returned >= ll.batchTotal) {
            ll.state = ChamberState.VENTING;
            ll.remainingTime = scheduleConfig.getTiming().getLoadlockVentTimeSec();
            ll.totalTime = ll.remainingTime;
            addEvent(ll.id + " batch complete (" + returned + "/" + ll.batchTotal + " wafers returned), venting");

            // Serial mode: advance to next PJ so its batch can be prepared in another LL
            if (activeCJ != null && "serial".equals(activeCJ.getMode())) {
                currentPJIndex++;
            }
        }
    }

    private String getNextUnprocessedWafer(Chamber ll) {
        for (String wid : ll.waferIds) {
            Wafer w = findWafer(wid);
            if (w != null && w.flowStep == 1) return wid;
        }
        // Also pick wafers that have been returned (flowStep 15) - they're already processed
        // Only pick unprocessed ones
        return null;
    }

    // --- Finder methods ---
    private Chamber findReadyBatchLL() {
        return chambers.values().stream()
                .filter(c -> c.type.equals("LOADLOCK") && c.state == ChamberState.READY)
                .findFirst().orElse(null);
    }

    private Chamber findReadyBatchLLWithUnprocessed() {
        return chambers.values().stream()
                .filter(c -> c.type.equals("LOADLOCK") && c.state == ChamberState.READY
                        && c.waferIds.stream().anyMatch(wid -> {
                    Wafer w = findWafer(wid);
                    return w != null && w.flowStep == 1;
                }))
                .findFirst().orElse(null);
    }

    private Chamber findAvailablePreClean() {
        return chambers.values().stream()
                .filter(c -> c.type.equals("PRECLEAN") && c.state == ChamberState.IDLE && c.waferId == null)
                .min(Comparator.comparingInt(c -> c.lastUsedTime)).orElse(null);
    }

    private Chamber findPreCleanDone() {
        return chambers.values().stream()
                .filter(c -> c.type.equals("PRECLEAN") && c.state == ChamberState.DONE)
                .min(Comparator.comparingInt(c -> {
                    Wafer w = findWafer(c.waferId);
                    return w != null ? w.enteredCurrentLocation : Integer.MAX_VALUE;
                })).orElse(null);
    }

    private Chamber findAvailablePTForward() {
        // Prefer buffer (non-cooling) slots for forward wafers
        Chamber buffer = chambers.values().stream()
                .filter(c -> c.type.equals("PASSTHROUGH") && !c.coolingStation
                        && c.state == ChamberState.IDLE && c.waferId == null)
                .findFirst().orElse(null);
        if (buffer != null) return buffer;
        return chambers.values().stream()
                .filter(c -> c.type.equals("PASSTHROUGH") && c.state == ChamberState.IDLE && c.waferId == null)
                .findFirst().orElse(null);
    }

    private Chamber findAvailablePTReturn() {
        // Prefer cooling station slots for EPI return wafers
        Chamber cooling = chambers.values().stream()
                .filter(c -> c.type.equals("PASSTHROUGH") && c.coolingStation
                        && c.state == ChamberState.IDLE && c.waferId == null)
                .findFirst().orElse(null);
        if (cooling != null) return cooling;
        return chambers.values().stream()
                .filter(c -> c.type.equals("PASSTHROUGH") && c.state == ChamberState.IDLE && c.waferId == null)
                .findFirst().orElse(null);
    }

    private Chamber findPTWithForwardWafer() {
        return chambers.values().stream()
                .filter(c -> c.type.equals("PASSTHROUGH") && c.waferId != null && c.forwardDirection)
                .findFirst().orElse(null);
    }

    private Chamber findPTWithReturnWafer() {
        return chambers.values().stream()
                .filter(c -> c.type.equals("PASSTHROUGH") && c.waferId != null && !c.forwardDirection
                        && c.state != ChamberState.COOLING)
                .findFirst().orElse(null);
    }

    private Chamber findAvailableEpi() {
        return chambers.values().stream()
                .filter(c -> c.type.equals("EPI") && c.state == ChamberState.IDLE && c.waferId == null
                        && !awaitingOnloadClean.contains(c.id))
                .min(Comparator.comparingInt(c -> c.lastUsedTime)).orElse(null);
    }

    private Chamber findEpiDone() {
        return chambers.values().stream()
                .filter(c -> c.type.equals("EPI") && c.state == ChamberState.DONE)
                .min(Comparator.comparingInt(c -> {
                    Wafer w = findWafer(c.waferId);
                    return w != null ? w.enteredCurrentLocation : Integer.MAX_VALUE;
                })).orElse(null);
    }

    private boolean downstreamFull() {
        long busyEpi = chambers.values().stream()
                .filter(c -> c.type.equals("EPI") && (c.state == ChamberState.PROCESSING || c.state == ChamberState.DONE)).count();
        long occPT = chambers.values().stream()
                .filter(c -> c.type.equals("PASSTHROUGH") && c.waferId != null && c.forwardDirection).count();
        long busyPC = chambers.values().stream()
                .filter(c -> c.type.equals("PRECLEAN") && (c.state == ChamberState.PROCESSING || c.state == ChamberState.DONE)).count();
        return busyEpi + occPT + busyPC >= 8;
    }

    private boolean canPullWaferFromLL() {
        // Count ALL future EPI demand: PT fwd + PreClean (will enter PT) + this new wafer
        int ptFwdCount = (int) chambers.values().stream()
                .filter(c -> c.type.equals("PASSTHROUGH") && c.waferId != null && c.forwardDirection)
                .count();
        int pcBusy = (int) chambers.values().stream()
                .filter(c -> c.type.equals("PRECLEAN") && (c.state == ChamberState.PROCESSING || c.state == ChamberState.DONE))
                .count();

        int demand = ptFwdCount + pcBusy + 1;

        // Uniform stagger to match EPI throughput
        long epiCount = deviceConfig.getChambers().stream().filter(c -> "EPI".equals(c.getType())).count();
        ScheduleConfig.RecipeConfig epiRecipe = scheduleConfig.getRecipes().get("EPI");
        int epiTime = epiRecipe.getAvgProcessTimeSec();
        double cleanTime = amConfig != null ? amConfig.getCleanTimeForChamber("EPI") : 0;
        int totalCycle = epiTime + (int) Math.ceil(cleanTime);
        int staggerInterval = (int)(totalCycle / epiCount);
        if (currentTimeSec - lastWaferStartTime < staggerInterval) return false;

        // Count IDLE + CLEANING chambers as available
        long epiIdle = chambers.values().stream()
                .filter(c -> c.type.equals("EPI") && c.state == ChamberState.IDLE && c.waferId == null)
                .count();
        long epiCleaning = chambers.values().stream()
                .filter(c -> c.type.equals("EPI") && c.state == ChamberState.CLEANING)
                .count();

        if (epiIdle + epiCleaning >= demand) {
            lastWaferStartTime = currentTimeSec;
            return true;
        }

        List<Integer> epiRemaining = chambers.values().stream()
                .filter(c -> c.type.equals("EPI") && c.state == ChamberState.PROCESSING)
                .map(c -> c.remainingTime)
                .sorted()
                .collect(Collectors.toList());

        int needSlot = demand - (int) epiIdle;
        if (needSlot > epiRemaining.size()) return false;

        int epiReadyIn = epiRemaining.get(needSlot - 1);

        ScheduleConfig.RecipeConfig pcRecipe = scheduleConfig.getRecipes().get("PRECLEAN");
        int precleanTime = pcRecipe.getAvgProcessTimeSec() + pcRecipe.getProcessTimeVariationSec();
        int ptMaxDwell = scheduleConfig.getRecipes().get("PASSTHROUGH").getMaxDwellTimeSec();
        int robotTimeLLtoPC = getOpDur("Robot1", "LL_TO_PRECLEAN");
        int robotTimePCtoPT = getOpDur("Robot1", "PRECLEAN_TO_PT");

        int safetyMargin = scheduleConfig.getScheduling().getDwellSafetyMarginSec();
        int maxWait = robotTimeLLtoPC + precleanTime + robotTimePCtoPT + ptMaxDwell - safetyMargin;

        if (epiReadyIn <= maxWait) {
            lastWaferStartTime = currentTimeSec;
            return true;
        }
        return false;
    }

    private boolean canMovePCToPT() {
        int ptFwdCount = (int) chambers.values().stream()
                .filter(c -> c.type.equals("PASSTHROUGH") && c.waferId != null && c.forwardDirection)
                .count();
        long epiIdle = chambers.values().stream()
                .filter(c -> c.type.equals("EPI") && c.state == ChamberState.IDLE && c.waferId == null)
                .count();

        int demand = ptFwdCount + 1;
        if (epiIdle >= demand) return true;

        // Also count CLEANING chambers as future IDLE (they'll be ready soon)
        long epiCleaning = chambers.values().stream()
                .filter(c -> c.type.equals("EPI") && c.state == ChamberState.CLEANING)
                .count();
        if (epiIdle + epiCleaning >= demand) {
            // Clean will complete soon — check if within PT dwell limit
            List<Integer> epiTimers = new java.util.ArrayList<>();
            chambers.values().stream()
                    .filter(c -> c.type.equals("EPI") && c.state == ChamberState.CLEANING)
                    .forEach(c -> epiTimers.add(c.remainingTime));
            java.util.Collections.sort(epiTimers);
            int needSlot = demand - (int) epiIdle;
            if (needSlot <= epiTimers.size()) {
                int cleanReadyIn = epiTimers.get(needSlot - 1);
                int ptMaxDwell = scheduleConfig.getRecipes().get("PASSTHROUGH").getMaxDwellTimeSec();
                int safetyMargin = scheduleConfig.getScheduling().getDwellSafetyMarginSec();
                if (cleanReadyIn <= ptMaxDwell - safetyMargin) return true;
            }
        }

        List<Integer> epiRemaining = chambers.values().stream()
                .filter(c -> c.type.equals("EPI") && c.state == ChamberState.PROCESSING)
                .map(c -> c.remainingTime)
                .sorted()
                .collect(Collectors.toList());

        int needSlot = demand - (int) epiIdle;
        if (needSlot <= epiRemaining.size()) {
            int epiReadyIn = epiRemaining.get(needSlot - 1);
            int ptMaxDwell = scheduleConfig.getRecipes().get("PASSTHROUGH").getMaxDwellTimeSec();
            int safetyMargin = scheduleConfig.getScheduling().getDwellSafetyMarginSec();
            if (epiReadyIn <= ptMaxDwell - safetyMargin) return true;
        }

        // EPI won't be ready in time; force move if PC dwell about to be violated
        Chamber pcDone = findPreCleanDone();
        if (pcDone != null) {
            int pcDwell = currentTimeSec - pcDone.processStartTime - pcDone.totalTime;
            int robotTime = getOpDur("Robot1", "PRECLEAN_TO_PT");
            int pcMaxDwell = scheduleConfig.getRecipes().get("PRECLEAN").getMaxDwellTimeSec();
            if (pcDwell + robotTime >= pcMaxDwell) return true;
        }
        return false;
    }

    private int getFoupCount() {
        if (deviceConfig.getEfem() != null) return deviceConfig.getEfem().getLoadPorts().size();
        if (deviceConfig.getFoups() != null) return deviceConfig.getFoups().getCount();
        return 3;
    }

    private int getSlotsPerFoup() {
        if (deviceConfig.getEfem() != null && !deviceConfig.getEfem().getLoadPorts().isEmpty())
            return deviceConfig.getEfem().getLoadPorts().get(0).getSlots();
        if (deviceConfig.getFoups() != null) return deviceConfig.getFoups().getSlotsPerFoup();
        return 25;
    }

    private double getOpTime(String robotId, String opKey, String field) {
        for (DeviceConfig.TransferModuleConfig tm : deviceConfig.getTransferModules()) {
            for (DeviceConfig.RobotConfig rc : tm.getRobots()) {
                if (!rc.getId().equals(robotId)) continue;
                if (rc.getOperations() == null) break;
                DeviceConfig.AtmOperationConfig op = rc.getOperations().get(opKey);
                if (op == null) break;
                switch (field) {
                    case "pick": return op.getPickTimeSec();
                    case "rotate": return op.getRotateTimeSec();
                    case "place": return op.getPlaceTimeSec();
                    case "total": return op.getPickTimeSec() + op.getRotateTimeSec() + op.getPlaceTimeSec();
                }
            }
        }
        // Fallback to generic robot config
        for (DeviceConfig.TransferModuleConfig tm : deviceConfig.getTransferModules()) {
            for (DeviceConfig.RobotConfig rc : tm.getRobots()) {
                if (!rc.getId().equals(robotId)) continue;
                switch (field) {
                    case "pick": return rc.getPickTimeSec();
                    case "rotate": return rc.getRotateTimeSec();
                    case "place": return rc.getPlaceTimeSec();
                    case "total": return rc.getPickTimeSec() + rc.getRotateTimeSec() + rc.getPlaceTimeSec();
                }
            }
        }
        return 6; // default
    }

    private int getOpDur(String robotId, String opKey) {
        return (int) Math.ceil(getOpTime(robotId, opKey, "total"));
    }

    private int getAtmXferFoupToAligner() {
        if (deviceConfig.getEfem() != null && deviceConfig.getEfem().getAtmRobot() != null) {
            DeviceConfig.AtmRobotConfig atm = deviceConfig.getEfem().getAtmRobot();
            if (atm.getFoupToAligner() != null) {
                DeviceConfig.AtmOperationConfig op = atm.getFoupToAligner();
                return (int) Math.ceil(op.getPickTimeSec() + op.getRotateTimeSec() + op.getPlaceTimeSec());
            }
            return atm.getPickTimeSec() + atm.getRotateTimeSec() + atm.getPlaceTimeSec();
        }
        return 15;
    }

    private int getAtmXferAlignerToLL() {
        if (deviceConfig.getEfem() != null && deviceConfig.getEfem().getAtmRobot() != null) {
            DeviceConfig.AtmRobotConfig atm = deviceConfig.getEfem().getAtmRobot();
            if (atm.getAlignerToLL() != null) {
                DeviceConfig.AtmOperationConfig op = atm.getAlignerToLL();
                return (int) Math.ceil(op.getPickTimeSec() + op.getRotateTimeSec() + op.getPlaceTimeSec());
            }
            return atm.getPickTimeSec() + atm.getRotateTimeSec() + atm.getPlaceTimeSec();
        }
        return 15;
    }

    private Wafer findWafer(String id) {
        if (id == null) return null;
        return wafers.stream().filter(w -> w.id.equals(id)).findFirst().orElse(null);
    }

    private int getProcessTime(String type) {
        ScheduleConfig.RecipeConfig rc = scheduleConfig.getRecipes().get(type);
        if (rc == null) return 60;
        int v = rc.getProcessTimeVariationSec();
        return rc.getAvgProcessTimeSec() + (v > 0 ? random.nextInt(2 * v + 1) - v : 0);
    }

    private void triggerOnLoadClean() {
        if (amConfig == null) return;
        // Handle all chamber types with OnLoadClean tasks
        for (String chType : new String[]{"EPI", "PRECLEAN"}) {
            double onLoadTime = amConfig.getOnLoadCleanTime(chType);
            if (onLoadTime <= 0) continue;
            triggerOnLoadCleanForType(chType, onLoadTime);
        }
    }

    private void triggerOnLoadCleanForType(String chType, double onLoadTime) {
        int onloadTimeCeil = (int) Math.ceil(onLoadTime);

        // Track chambers that need OnLoadClean but haven't started yet
        for (Chamber c : chambers.values()) {
            if (!c.type.equals(chType)) continue;
            if (c.state == ChamberState.IDLE && !onloadCleanedChambers.contains(c.id)) {
                awaitingOnloadClean.add(c.id);
            }
            if (onloadCleanedChambers.contains(c.id)) {
                awaitingOnloadClean.remove(c.id);
            }
        }

        // --- EPI-specific: counter-based triggering for chambers after the first ---
        if ("EPI".equals(chType)) {
            long epiTotal = deviceConfig.getChambers().stream()
                    .filter(ch -> "EPI".equals(ch.getType())).count();
            long epiDone = chambers.values().stream()
                    .filter(c -> "EPI".equals(c.type) && onloadCleanedChambers.contains(c.id)).count();

            if (epiDone >= epiTotal) return; // all EPI chambers already cleaned

            if (epiDone == 0) {
                // First EPI: delay until first wafer can realistically arrive
                if (amConfig == null) return;
                int pcOnload = (int) Math.ceil(amConfig.getOnLoadCleanTime("PRECLEAN"));
                double purgeThreshold = amConfig.getIdlePurgeThreshold("PRECLEAN");
                double purgeTime = amConfig.getIdlePurgeTime("PRECLEAN");
                int pcProcess = scheduleConfig.getRecipes().get("PRECLEAN") != null
                        ? scheduleConfig.getRecipes().get("PRECLEAN").getAvgProcessTimeSec()
                          + scheduleConfig.getRecipes().get("PRECLEAN").getProcessTimeVariationSec()
                        : 280;
                int transportTime = getOpDur("Robot1", "LL_TO_PRECLEAN")
                        + getOpDur("Robot1", "PRECLEAN_TO_PT")
                        + getOpDur("Robot2", "PT_TO_EPI");
                int arrivalEstimate = pcOnload + (int) Math.ceil(purgeThreshold + purgeTime)
                        + pcProcess + transportTime;
                int minStartTime = Math.max(0, arrivalEstimate - onloadTimeCeil + 120);
                if (currentTimeSec < minStartTime) return;
            } else {
                // Subsequent EPI chambers: trigger when enough wafers have entered PreClean
                // EPI idx 2 needs 2 wafers entered, EPI 3 needs 3, etc.
                int epiIdx = (int) epiDone + 1; // next EPI chamber index (1-based)
                if (wafersEnteredPreClean < epiIdx) return;
            }

            // Start the next EPI chamber's OnLoadClean
            for (Chamber c : chambers.values()) {
                if (!"EPI".equals(c.type)) continue;
                if (c.state != ChamberState.IDLE) continue;
                if (onloadCleanedChambers.contains(c.id)) continue;
                c.state = ChamberState.CLEANING;
                c.remainingTime = onloadTimeCeil;
                c.totalTime = c.remainingTime;
                onloadCleanedChambers.add(c.id);
                chamberCleanType.put(c.id, "OnLoadClean");
                addEvent(c.id + " OnLoad Clean started (" + onloadTimeCeil + "s)");
                addGanttEntry(nextCleanGanttId(c.id), c.id, "CLEAN", currentTimeSec, -1, "#FF5722");
                return; // one per tick
            }
            return;
        }

        // --- Non-EPI: staggered OnLoadClean ---
        ScheduleConfig.RecipeConfig recipe = scheduleConfig.getRecipes().get(chType);
        if (recipe == null) return;
        long chCount = deviceConfig.getChambers().stream().filter(ch -> chType.equals(ch.getType())).count();
        int staggerBase = recipe.getAvgProcessTimeSec() + onloadTimeCeil;
        int staggerInterval = (int)(staggerBase / (double) chCount);

        int lastStart = lastOnloadCleanStartPerType.getOrDefault(chType, -99999);
        if (currentTimeSec - lastStart < staggerInterval) return;
        lastOnloadCleanStartPerType.put(chType, currentTimeSec);

        // Start ONE chamber's OnLoadClean per stagger interval
        for (Chamber c : chambers.values()) {
            if (!c.type.equals(chType)) continue;
            if (c.state != ChamberState.IDLE) continue;
            if (onloadCleanedChambers.contains(c.id)) continue;

            c.state = ChamberState.CLEANING;
            c.remainingTime = onloadTimeCeil;
            c.totalTime = c.remainingTime;
            onloadCleanedChambers.add(c.id);
            chamberCleanType.put(c.id, "OnLoadClean");
            addEvent(c.id + " OnLoad Clean started (" + onloadTimeCeil + "s)");
            addGanttEntry(nextCleanGanttId(c.id), c.id, "CLEAN", currentTimeSec, -1, "#FF5722");
            return; // one per tick
        }
    }

    private String nextCleanGanttId(String chamberId) {
        int idx = cleanCounterPerChamber.getOrDefault(chamberId, 0) + 1;
        cleanCounterPerChamber.put(chamberId, idx);
        return "CLEAN_" + chamberId + "_" + idx;
    }

    private void closeCleanGantt(String chamberId) {
        // close most recent matching CLEAN gantt entry for this chamber
        String prefix = "CLEAN_" + chamberId + "_";
        for (int i = ganttEntries.size() - 1; i >= 0; i--) {
            GanttEntry e = ganttEntries.get(i);
            if (e.getWaferId().startsWith(prefix) && e.getEndTimeSec() < 0) {
                e.setEndTimeSec(currentTimeSec); return;
            }
        }
    }

    private void start1XClean(Chamber epi, double cleanTime) {
        epi.state = ChamberState.CLEANING;
        epi.remainingTime = (int) Math.ceil(cleanTime);
        epi.totalTime = epi.remainingTime;
        chamberCleanType.put(epi.id, "1X Clean");
        addEvent(epi.id + " 1X Clean started (" + (int) cleanTime + "s)");
        addGanttEntry(nextCleanGanttId(epi.id), epi.id, "CLEAN", currentTimeSec, -1, "#FF5722");
    }

    private void triggerScheduledCleans() {
        for (Map.Entry<String, Integer> e : new java.util.ArrayList<>(scheduledCleanStarts.entrySet())) {
            if (currentTimeSec >= e.getValue()) {
                Chamber epi = chambers.get(e.getKey());
                if (epi != null && epi.state == ChamberState.IDLE && epi.waferId == null) {
                    double cleanTime = amConfig.getCleanTimeForChamber("EPI");
                    start1XClean(epi, cleanTime);
                }
                scheduledCleanStarts.remove(e.getKey());
            }
        }
    }

    private void triggerIdlePurge() {
        if (amConfig == null) return;
        for (String chType : new String[]{"PRECLEAN"}) {
            double purgeTime = amConfig.getIdlePurgeTime(chType);
            double threshold = amConfig.getIdlePurgeThreshold(chType);
            if (purgeTime <= 0 || threshold <= 0) continue;
            for (Chamber c : chambers.values()) {
                if (!c.type.equals(chType)) continue;
                if (c.state != ChamberState.IDLE) continue;
                Integer idleStart = idleStartTime.get(c.id);
                if (idleStart == null) continue;
                if (currentTimeSec - idleStart < threshold) continue;
                // Start IdlePurge — triggered purely by idle time
                c.state = ChamberState.PURGING;
                c.remainingTime = (int) Math.ceil(purgeTime);
                c.totalTime = c.remainingTime;
                idleStartTime.put(c.id, currentTimeSec); // reset idle timer
                chamberCleanType.put(c.id, "IdlePurge");
                addEvent(c.id + " IdlePurge started (" + (int) purgeTime + "s)");
                addGanttEntry(nextCleanGanttId(c.id), c.id, "PURGE", currentTimeSec, -1, "#9C27B0");
            }
        }
    }

    private void healWaferLocations() {
        for (Wafer w : wafers) {
            if (!w.location.startsWith("PT")) continue;
            Chamber c = chambers.get(w.location);
            if (c != null && c.waferId != null && c.waferId.equals(w.id)) continue;
            // Inconsistency detected: wafer thinks it's in PT but chamber disagrees
            if (c == null || c.waferId == null) {
                if (w.flowStep >= 15) {
                    // Return wafer lost: move to LL
                    Chamber ll = findReadyBatchLL();
                    if (ll == null) continue;
                    w.location = ll.id;
                    ll.waferIds.add(w.id);
                    addEvent("HEAL: " + w.id + " location fixed PT→" + ll.id + " (was lost)");
                } else if (w.flowStep == 8 || w.flowStep == 13) {
                    // Forward or return wafer whose PT slot was cleared: restore
                    if (c != null) {
                        c.waferId = w.id;
                        c.forwardDirection = (w.flowStep == 8);
                        c.state = ChamberState.READY;
                        addEvent("HEAL: " + w.id + " restored to " + w.location);
                    }
                }
            }
        }
    }

    private void addEvent(String e) {
        String msg = "[" + formatTime(currentTimeSec) + "] " + e;
        eventLog.add(msg); recentEvents.add(msg);
    }

    private void addGanttEntry(String waferId, String location, String type, int start, int end, String color) {
        ganttEntries.add(new GanttEntry(waferId, location, type, start, end, color));
    }

    private void closeGanttEntry(String waferId, int endTime) {
        for (int i = ganttEntries.size() - 1; i >= 0; i--) {
            GanttEntry e = ganttEntries.get(i);
            if (e.getWaferId().equals(waferId) && e.getEndTimeSec() < 0) {
                e.setEndTimeSec(endTime); return;
            }
        }
    }

    private String formatTime(int s) {
        return String.format("%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }

    private void checkMaxDwellTimes() {
        for (Chamber c : chambers.values()) {
            if (c.waferId == null) continue;
            // Wafer being handled by a robot → not dwelling anymore
            boolean beingHandled = false;
            for (Robot r : robots.values()) {
                if (r.busy && c.waferId.equals(r.armWaferId)) {
                    beingHandled = true; break;
                }
            }
            if (beingHandled) {
                c.warnedDwellWafer = null;
                continue;
            }
            int dwell;
            String recipeKey;
            switch (c.type) {
                case "PRECLEAN":
                case "EPI":
                    if (c.state == ChamberState.CLEANING || c.state == ChamberState.PURGING) continue;
                    if (c.state != ChamberState.DONE) continue;
                    dwell = currentTimeSec - c.processStartTime - c.totalTime;
                    recipeKey = c.type;
                    break;
                case "PASSTHROUGH":
                    if (c.state == ChamberState.COOLING) continue; // cooling is intentional dwell
                    dwell = currentTimeSec - c.lastUsedTime;
                    recipeKey = "PASSTHROUGH";
                    break;
                default: continue;
            }
            ScheduleConfig.RecipeConfig recipe = scheduleConfig.getRecipes().get(recipeKey);
            if (recipe == null) continue;
            int maxDwell = recipe.getMaxDwellTimeSec();
            if (maxDwell > 0 && dwell > maxDwell) {
                String key = c.id + "/" + c.waferId;
                if (!key.equals(c.warnedDwellWafer)) {
                    c.warnedDwellWafer = key;
                    addEvent("WARN: " + c.id + " " + c.waferId + " dwell " + dwell + "s exceeds max " + maxDwell + "s");
                }
            } else {
                c.warnedDwellWafer = null;
            }
        }
    }
    public synchronized SimulationSnapshot getSnapshot() {
        SimulationSnapshot snap = new SimulationSnapshot();
        snap.setCurrentTimeSec(currentTimeSec);
        snap.setStatus(status.name());
        snap.setCompletedWafers(completedWafers);
        snap.setTotalWafers(wafers.size());
        if (completedWafers > 0 && firstCompletionTime > 0) {
            double h = (double) (currentTimeSec - firstCompletionTime) / 3600.0;
            snap.setCurrentWPH(h > 0 ? (completedWafers - 1) / h : 0);
        }

        Map<String, SimulationSnapshot.ChamberSnapshot> cm = new LinkedHashMap<>();
        for (Chamber c : chambers.values()) {
            SimulationSnapshot.ChamberSnapshot cs = new SimulationSnapshot.ChamberSnapshot();
            cs.setId(c.id); cs.setType(c.type); cs.setState(c.state.name());
            cs.setWaferId(c.waferId); cs.setRemainingTimeSec(c.remainingTime); cs.setTotalTimeSec(c.totalTime);
            if (!c.waferIds.isEmpty()) { cs.setWaferId(String.join(",", c.waferIds.subList(0, Math.min(3, c.waferIds.size())))); }
            cs.setWaferCount(c.waferIds.size());
            cm.put(c.id, cs);
        }
        snap.setChambers(cm);

        Map<String, SimulationSnapshot.RobotSnapshot> rm = new LinkedHashMap<>();
        for (Robot r : robots.values()) {
            SimulationSnapshot.RobotSnapshot rs = new SimulationSnapshot.RobotSnapshot();
            rs.setId(r.id); rs.setTmId(r.tmId);
            rs.setState(r.busy ? "BUSY" : "IDLE");
            rs.setArm1WaferId(r.armWaferId);
            rs.setCurrentAction(r.currentAction);
            rs.setRemainingTimeSec(Math.max(0, r.busyUntil - currentTimeSec));
            rm.put(r.id, rs);
        }
        snap.setRobots(rm);

        List<SimulationSnapshot.WaferSnapshot> wl = new ArrayList<>();
        for (Wafer w : wafers) {
            SimulationSnapshot.WaferSnapshot ws = new SimulationSnapshot.WaferSnapshot();
            ws.setId(w.id); ws.setFoupIndex(w.foupIndex); ws.setSlotIndex(w.slotIndex);
            ws.setLocation(w.location); ws.setState(w.state); ws.setFlowStep(w.flowStep);
            wl.add(ws);
        }
        snap.setWafers(wl);

        // Return last N events (capped), without clearing the buffer
        int n = Math.min(recentEvents.size(), 30);
        snap.setRecentEvents(new ArrayList<>(recentEvents.subList(Math.max(0, recentEvents.size() - n), recentEvents.size())));
        // Cap buffer to prevent unbounded growth
        if (recentEvents.size() > 200) {
            recentEvents.subList(0, recentEvents.size() - 100).clear();
        }
        return snap;
    }

    public List<GanttEntry> getGanttData() {
        List<GanttEntry> result = new ArrayList<>();
        synchronized (ganttEntries) {
            for (GanttEntry e : ganttEntries) {
                int et = e.getEndTimeSec() < 0 ? currentTimeSec : e.getEndTimeSec();
                result.add(new GanttEntry(e.getWaferId(), e.getLocation(), e.getType(), e.getStartTimeSec(), et, e.getColor()));
            }
        }
        return result;
    }

    public List<String> getFullEventLog() { return new ArrayList<>(eventLog); }
    public SimStatus getStatus() { return status; }
    public int getCurrentTimeSec() { return currentTimeSec; }

    public Map<String, FoupSlot> getFoupSlots() { return foupSlots; }
    public Map<String, Robot> getRobots() { return robots; }
    public Map<String, Chamber> getChambers() { return chambers; }

    private void captureReplaySnapshot() {
        if (currentTimeSec < nextReplaySnapshotTime) return;
        nextReplaySnapshotTime = currentTimeSec + replaySnapshotIntervalSec;
        replaySnapshots.add(buildLightSnapshot());
    }

    /** Lightweight snapshot with only data needed for tool-layout animation replay */
    private SimulationSnapshot buildLightSnapshot() {
        SimulationSnapshot snap = new SimulationSnapshot();
        snap.setCurrentTimeSec(currentTimeSec);
        snap.setStatus(status.name());
        snap.setCompletedWafers(completedWafers);
        snap.setTotalWafers(wafers.size());

        Map<String, SimulationSnapshot.ChamberSnapshot> cm = new LinkedHashMap<>();
        for (Chamber c : chambers.values()) {
            SimulationSnapshot.ChamberSnapshot cs = new SimulationSnapshot.ChamberSnapshot();
            cs.setId(c.id); cs.setType(c.type); cs.setState(c.state.name());
            cs.setWaferId(c.waferId); cs.setRemainingTimeSec(c.remainingTime); cs.setTotalTimeSec(c.totalTime);
            if (!c.waferIds.isEmpty()) {
                cs.setWaferId(String.join(",", c.waferIds.subList(0, Math.min(3, c.waferIds.size()))));
            }
            cs.setWaferCount(c.waferIds.size());
            cm.put(c.id, cs);
        }
        snap.setChambers(cm);

        List<SimulationSnapshot.WaferSnapshot> wl = new ArrayList<>();
        for (Wafer w : wafers) {
            SimulationSnapshot.WaferSnapshot ws = new SimulationSnapshot.WaferSnapshot();
            ws.setId(w.id); ws.setFoupIndex(w.foupIndex); ws.setSlotIndex(w.slotIndex);
            ws.setLocation(w.location); ws.setState(w.state); ws.setFlowStep(w.flowStep);
            wl.add(ws);
        }
        snap.setWafers(wl);

        Map<String, SimulationSnapshot.RobotSnapshot> rm = new LinkedHashMap<>();
        for (Robot r : robots.values()) {
            SimulationSnapshot.RobotSnapshot rs = new SimulationSnapshot.RobotSnapshot();
            rs.setId(r.id); rs.setTmId(r.tmId);
            rs.setState(r.busy ? "BUSY" : "IDLE");
            rs.setArm1WaferId(r.armWaferId);
            rs.setCurrentAction(r.currentAction);
            rs.setRemainingTimeSec(Math.max(0, r.busyUntil - currentTimeSec));
            rm.put(r.id, rs);
        }
        snap.setRobots(rm);
        return snap;
    }

    public List<SimulationSnapshot> getReplaySnapshots() { return replaySnapshots; }
}
