package com.epi.scheduler.service;

import com.epi.scheduler.model.DeviceConfig;
import com.epi.scheduler.model.ScheduleConfig;
import com.epi.scheduler.model.SequenceConfig;
import com.epi.scheduler.model.JobConfig;
import com.epi.scheduler.model.AmConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.Map;

@Service
public class ConfigService {

    @Value("${epi.config.base-path:../conf}")
    private String confBasePath;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private DeviceConfig deviceConfig;
    private ScheduleConfig scheduleConfig;
    private SequenceConfig sequenceConfig;
    private JobConfig jobConfig;
    private AmConfig amConfig;
    private String activeProfile;

    @PostConstruct
    public void loadConfigs() throws IOException {
        File ctxFile = new File(confBasePath, "context.json");
        activeProfile = "";
        if (ctxFile.exists()) {
            Map<String, String> ctx = objectMapper.readValue(ctxFile, Map.class);
            activeProfile = ctx.getOrDefault("activeProfile", "");
        }

        String profilePath = confBasePath + File.separator + activeProfile;

        deviceConfig = objectMapper.readValue(new File(profilePath, "device.json"), DeviceConfig.class);
        scheduleConfig = objectMapper.readValue(new File(profilePath, "schedule.json"), ScheduleConfig.class);
        File seqFile = new File(profilePath, "sequence.json");
        if (seqFile.exists()) sequenceConfig = objectMapper.readValue(seqFile, SequenceConfig.class);
        File jobFile = new File(profilePath, "job.json");
        if (jobFile.exists()) jobConfig = objectMapper.readValue(jobFile, JobConfig.class);
        File amFile = new File(profilePath, "am.json");
        if (amFile.exists()) amConfig = objectMapper.readValue(amFile, AmConfig.class);
    }

    public String getActiveProfile() { return activeProfile; }

    public DeviceConfig getDeviceConfig() { return deviceConfig; }

    public ScheduleConfig getScheduleConfig() { return scheduleConfig; }

    public SequenceConfig getSequenceConfig() { return sequenceConfig; }

    public JobConfig getJobConfig() { return jobConfig; }

    public AmConfig getAmConfig() { return amConfig; }

    public void reloadConfigs() throws IOException {
        loadConfigs();
    }
}
