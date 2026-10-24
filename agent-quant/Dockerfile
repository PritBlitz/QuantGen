# syntax=docker/dockerfile:1.7
# QuantGen agent service (D-044, D-045): multi-stage build like backend-quant. Stateless: no volume, no database.
# Tests are not run here; CI runs `./mvnw test` before building the image.

FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -q dependency:go-offline
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -q package -DskipTests \
 && java -Djarmode=tools -jar target/agent-quant-*.jar extract --layers --launcher --destination /extracted

FROM eclipse-temurin:25-jre-alpine AS runtime
RUN addgroup -S -g 10001 quantgen && adduser -S -u 10001 -G quantgen -h /app quantgen
WORKDIR /app
COPY --from=build --chown=quantgen:quantgen /extracted/dependencies/ ./
COPY --from=build --chown=quantgen:quantgen /extracted/spring-boot-loader/ ./
COPY --from=build --chown=quantgen:quantgen /extracted/snapshot-dependencies/ ./
COPY --from=build --chown=quantgen:quantgen /extracted/application/ ./
USER 10001:10001
EXPOSE 8090
# In a container it listens on the private network (never published); the core reaches it as http://agent:8090.
ENV QG_AGENT_BIND=0.0.0.0 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=10 \
  CMD wget -qO- http://127.0.0.1:8090/actuator/health | grep -q UP || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
