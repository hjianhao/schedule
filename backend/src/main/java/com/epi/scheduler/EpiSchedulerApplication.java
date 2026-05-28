package com.epi.scheduler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class EpiSchedulerApplication {
    public static void main(String[] args) {
        SpringApplication.run(EpiSchedulerApplication.class, args);
    }
}
