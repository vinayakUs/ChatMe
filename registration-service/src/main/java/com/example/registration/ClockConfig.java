package com.example.registration;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    @Bean
    public Clock getLocalClock() {
        return Clock.systemDefaultZone();
    }
}
