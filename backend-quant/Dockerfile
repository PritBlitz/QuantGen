# syntax=docker/dockerfile:1.7
# QuantGen API: multi-stage build (ARCHITECTURE §11).
#   build:   Temurin 25 JDK + Maven wrapper -> executable jar -> Spring Boot layered extraction
#   runtime: Temurin 25 JRE (Alpine), non-root, only the extracted layers
# Tests are not run here; CI runs `./mvnw verify` (Testcontainers) before building the image.

FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /workspace

# Dependencies first, so source edits don't invalidate the dependency layer.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -q dependency:go-offline

COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -q package -DskipTests \
 && java -Djarmode=tools -jar target/backend-quant-*.jar extract --layers --launcher --destination /extracted

FROM eclipse-temurin:25-jre-alpine AS runtime
# Unprivileged user with a fixed uid so volume and security policies can refer to it.
RUN addgroup -S -g 10001 quantgen && adduser -S -u 10001 -G quantgen -h /app quantgen
WORKDIR /app

# Least- to most-frequently changing layers.
COPY --from=build --chown=quantgen:quantgen /extracted/dependencies/ ./
COPY --from=build --chown=quantgen:quantgen /extracted/spring-boot-loader/ ./
COPY --from=build --chown=quantgen:quantgen /extracted/snapshot-dependencies/ ./
COPY --from=build --chown=quantgen:quantgen /extracted/application/ ./

USER 10001:10001
EXPOSE 8080
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# BusyBox wget ships with Alpine; root docker-compose.yml uses the same probe.
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=10 \
  CMD wget -qO- http://127.0.0.1:8080/actuator/health | grep -q UP || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
