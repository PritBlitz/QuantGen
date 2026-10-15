package com.quantgen.common.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Timestamps are UTC; business days (limits, EMI due dates, the EMI job) follow IST. */
@Configuration(proxyBeanMethods = false)
public class TimeConfig {

    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
