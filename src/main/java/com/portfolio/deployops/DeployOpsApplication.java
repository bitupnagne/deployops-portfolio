package com.portfolio.deployops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class DeployOpsApplication {
    public static void main(String[] args) {
        SpringApplication.run(DeployOpsApplication.class, args);
    }
}
