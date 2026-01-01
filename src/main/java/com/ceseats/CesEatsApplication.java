package com.ceseats;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CesEatsApplication {
    public static void main(String[] args) {
        SpringApplication.run(CesEatsApplication.class, args);
    }
}

