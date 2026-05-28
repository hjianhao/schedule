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

@Service
public class ConfigService {

    @Value("${epi.config.device-path:../conf/device.json}")
    private String deviceConfigPath;

    @Value("${epi.config.schedule-path:../conf/schedule.json}")
    private String scheduleConfigPath;

    @Value("${epi.config.sequence-path:../conf/sequence.json}")
    private String sequenceConfigPath;

    @Value("${epi.config.job-path:../conf/job.json}")
    private String jobConfigPath;

    @Value("${epi.config.am-path:../conf/am.json}")
    private String amConfigPath;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private DeviceConfig deviceConfig;
    private ScheduleConfig scheduleConfig;
    private SequenceConfig sequenceConfig;
    private JobConfig jobConfig;
    private AmConfig amConfig;

    @PostConstruct
    public void loadConfigs() throws IOException {
        deviceConfig = objectMapper.readValue(new File(deviceConfigPath), DeviceConfig.class);
        scheduleConfig = objectMapper.readValue(new File(scheduleConfigPath), ScheduleConfig.class);
        File seqFile = new File(sequenceConfigPath);
        if (seqFile.exists()) sequenceConfig = objectMapper.readValue(seqFile, SequenceConfig.class);
        File jobFile = new File(jobConfigPath);
        if (jobFile.exists()) jobConfig = objectMapper.readValue(jobFile, JobConfig.class);
        File amFile = new File(amConfigPath);
        if (amFile.exists()) amConfig = objectMapper.readValue(amFile, AmConfig.class);
    }

    public DeviceConfig getDeviceConfig() {
        return deviceConfig;
    }

    public ScheduleConfig getScheduleConfig() {
        return scheduleConfig;
    }

    public SequenceConfig getSequenceConfig() {
        return sequenceConfig;
    }

    public JobConfig getJobConfig() {
        return jobConfig;
    }

    public AmConfig getAmConfig() { return amConfig; }

    public void reloadConfigs() throws IOException {
        loadConfigs();
    }
}
