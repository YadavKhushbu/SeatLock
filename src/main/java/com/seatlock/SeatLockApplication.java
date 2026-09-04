package com.seatlock;

import com.seatlock.config.SeatLockProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(SeatLockProperties.class)
public class SeatLockApplication {

    public static void main(String[] args) {
        SpringApplication.run(SeatLockApplication.class, args);
    }
}
