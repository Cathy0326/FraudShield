package com.fraudshield;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FraudshieldApplication {

    public static void main(String[] args) {
        SpringApplication.run(FraudshieldApplication.class, args);
    }
}
