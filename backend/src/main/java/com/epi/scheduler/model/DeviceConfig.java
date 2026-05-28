package com.epi.scheduler.model;

import java.util.List;
import java.util.Map;

public class DeviceConfig {
    private String equipmentId;
    private String equipmentName;
    private FoupConfig foups;
    private EfemConfig efem;
    private List<LoadlockConfig> loadlocks;
    private List<TransferModuleConfig> transferModules;
    private List<ChamberConfig> chambers;
    private List<PassthroughConfig> passthroughs;

    public static class FoupConfig {
        private int count;
        private int slotsPerFoup;

        public int getCount() { return count; }
        public void setCount(int count) { this.count = count; }
        public int getSlotsPerFoup() { return slotsPerFoup; }
        public void setSlotsPerFoup(int slotsPerFoup) { this.slotsPerFoup = slotsPerFoup; }
    }

    public static class EfemConfig {
        private String id;
        private List<LoadPortConfig> loadPorts;
        private AlignerConfig aligner;
        private AtmRobotConfig atmRobot;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public List<LoadPortConfig> getLoadPorts() { return loadPorts; }
        public void setLoadPorts(List<LoadPortConfig> loadPorts) { this.loadPorts = loadPorts; }
        public AlignerConfig getAligner() { return aligner; }
        public void setAligner(AlignerConfig aligner) { this.aligner = aligner; }
        public AtmRobotConfig getAtmRobot() { return atmRobot; }
        public void setAtmRobot(AtmRobotConfig atmRobot) { this.atmRobot = atmRobot; }
    }

    public static class AlignerConfig {
        private double alignTimeSec;

        public double getAlignTimeSec() { return alignTimeSec; }
        public void setAlignTimeSec(double alignTimeSec) { this.alignTimeSec = alignTimeSec; }
    }

    public static class LoadPortConfig {
        private String id;
        private int foupIndex;
        private int slots;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public int getFoupIndex() { return foupIndex; }
        public void setFoupIndex(int foupIndex) { this.foupIndex = foupIndex; }
        public int getSlots() { return slots; }
        public void setSlots(int slots) { this.slots = slots; }
    }

    public static class AtmRobotConfig {
        private String id;
        private int arms;
        private int fingersPerArm;
        private int pickTimeSec;
        private int placeTimeSec;
        private int rotateTimeSec;
        private AtmOperationConfig foupToAligner;
        private AtmOperationConfig alignerToLL;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public int getArms() { return arms; }
        public void setArms(int arms) { this.arms = arms; }
        public int getFingersPerArm() { return fingersPerArm; }
        public void setFingersPerArm(int fingersPerArm) { this.fingersPerArm = fingersPerArm; }
        public int getPickTimeSec() { return pickTimeSec; }
        public void setPickTimeSec(int pickTimeSec) { this.pickTimeSec = pickTimeSec; }
        public int getPlaceTimeSec() { return placeTimeSec; }
        public void setPlaceTimeSec(int placeTimeSec) { this.placeTimeSec = placeTimeSec; }
        public int getRotateTimeSec() { return rotateTimeSec; }
        public void setRotateTimeSec(int rotateTimeSec) { this.rotateTimeSec = rotateTimeSec; }
        public AtmOperationConfig getFoupToAligner() { return foupToAligner; }
        public void setFoupToAligner(AtmOperationConfig foupToAligner) { this.foupToAligner = foupToAligner; }
        public AtmOperationConfig getAlignerToLL() { return alignerToLL; }
        public void setAlignerToLL(AtmOperationConfig alignerToLL) { this.alignerToLL = alignerToLL; }
    }

    public static class AtmOperationConfig {
        private double pickTimeSec;
        private double rotateTimeSec;
        private double placeTimeSec;

        public double getPickTimeSec() { return pickTimeSec; }
        public void setPickTimeSec(double pickTimeSec) { this.pickTimeSec = pickTimeSec; }
        public double getRotateTimeSec() { return rotateTimeSec; }
        public void setRotateTimeSec(double rotateTimeSec) { this.rotateTimeSec = rotateTimeSec; }
        public double getPlaceTimeSec() { return placeTimeSec; }
        public void setPlaceTimeSec(double placeTimeSec) { this.placeTimeSec = placeTimeSec; }
    }

    public static class LoadlockConfig {
        private String id;
        private String type;
        private int capacity;
        private int pumpTimeSec;
        private int ventTimeSec;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public int getCapacity() { return capacity; }
        public void setCapacity(int capacity) { this.capacity = capacity; }
        public int getPumpTimeSec() { return pumpTimeSec; }
        public void setPumpTimeSec(int pumpTimeSec) { this.pumpTimeSec = pumpTimeSec; }
        public int getVentTimeSec() { return ventTimeSec; }
        public void setVentTimeSec(int ventTimeSec) { this.ventTimeSec = ventTimeSec; }
    }

    public static class TransferModuleConfig {
        private String id;
        private List<RobotConfig> robots;
        private List<String> connectedChambers;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public List<RobotConfig> getRobots() { return robots; }
        public void setRobots(List<RobotConfig> robots) { this.robots = robots; }
        public List<String> getConnectedChambers() { return connectedChambers; }
        public void setConnectedChambers(List<String> connectedChambers) { this.connectedChambers = connectedChambers; }
    }

    public static class RobotConfig {
        private String id;
        private int arms;
        private int fingersPerArm;
        private int pickTimeSec;
        private int placeTimeSec;
        private int rotateTimeSec;
        private Map<String, AtmOperationConfig> operations;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public int getArms() { return arms; }
        public void setArms(int arms) { this.arms = arms; }
        public int getFingersPerArm() { return fingersPerArm; }
        public void setFingersPerArm(int fingersPerArm) { this.fingersPerArm = fingersPerArm; }
        public int getPickTimeSec() { return pickTimeSec; }
        public void setPickTimeSec(int pickTimeSec) { this.pickTimeSec = pickTimeSec; }
        public int getPlaceTimeSec() { return placeTimeSec; }
        public void setPlaceTimeSec(int placeTimeSec) { this.placeTimeSec = placeTimeSec; }
        public int getRotateTimeSec() { return rotateTimeSec; }
        public void setRotateTimeSec(int rotateTimeSec) { this.rotateTimeSec = rotateTimeSec; }
        public Map<String, AtmOperationConfig> getOperations() { return operations; }
        public void setOperations(Map<String, AtmOperationConfig> operations) { this.operations = operations; }
    }

    public static class ChamberConfig {
        private String id;
        private String type;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
    }

    public static class PassthroughConfig {
        private String id;
        private int slots;
        private Integer coolingStationSlot;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public int getSlots() { return slots; }
        public void setSlots(int slots) { this.slots = slots; }
        public Integer getCoolingStationSlot() { return coolingStationSlot; }
        public void setCoolingStationSlot(Integer coolingStationSlot) { this.coolingStationSlot = coolingStationSlot; }
    }

    public String getEquipmentId() { return equipmentId; }
    public void setEquipmentId(String equipmentId) { this.equipmentId = equipmentId; }
    public String getEquipmentName() { return equipmentName; }
    public void setEquipmentName(String equipmentName) { this.equipmentName = equipmentName; }
    public FoupConfig getFoups() { return foups; }
    public void setFoups(FoupConfig foups) { this.foups = foups; }
    public EfemConfig getEfem() { return efem; }
    public void setEfem(EfemConfig efem) { this.efem = efem; }
    public List<LoadlockConfig> getLoadlocks() { return loadlocks; }
    public void setLoadlocks(List<LoadlockConfig> loadlocks) { this.loadlocks = loadlocks; }
    public List<TransferModuleConfig> getTransferModules() { return transferModules; }
    public void setTransferModules(List<TransferModuleConfig> transferModules) { this.transferModules = transferModules; }
    public List<ChamberConfig> getChambers() { return chambers; }
    public void setChambers(List<ChamberConfig> chambers) { this.chambers = chambers; }
    public List<PassthroughConfig> getPassthroughs() { return passthroughs; }
    public void setPassthroughs(List<PassthroughConfig> passthroughs) { this.passthroughs = passthroughs; }
}
