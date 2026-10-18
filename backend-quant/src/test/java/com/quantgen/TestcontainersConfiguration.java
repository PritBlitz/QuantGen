package com.quantgen;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.quantgen.support.FakeRazorpayApi;

/** A throwaway Postgres 17 per test context, matching the dev and prod image. */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));
    }

    /** Razorpay's REST API replaced by an in-memory fake; signatures are still checked with the real code. */
    @Bean
    @Primary
    FakeRazorpayApi fakeRazorpayApi() {
        return new FakeRazorpayApi();
    }
}
