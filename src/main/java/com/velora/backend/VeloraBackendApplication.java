package com.velora.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class VeloraBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(VeloraBackendApplication.class, args);
    }
}
