package com.quantgen;

import org.springframework.boot.SpringApplication;

/** Runs the app locally against a Testcontainers Postgres (no docker compose needed). */
public class TestQuantGenApplication {

    public static void main(String[] args) {
        SpringApplication.from(QuantGenApplication::main)
                .with(TestcontainersConfiguration.class)
                .withAdditionalProfiles("test")
                .run(args);
    }
}
