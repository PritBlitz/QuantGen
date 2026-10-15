package com.quantgen;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class QuantGenApplication {

    public static void main(String[] args) {
        SpringApplication.run(QuantGenApplication.class, args);
    }
}
