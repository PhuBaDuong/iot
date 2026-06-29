# syntax=docker/dockerfile:1
# =============================================================================
# Device Registry Service - multi-stage build
# =============================================================================
# Build context is the repository root (see docker-compose.yml) so the build
# stage can compile the iot-common dependency alongside this module.
# =============================================================================

# ---- Build stage: JDK 25 + Maven ----
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace

# Copy the reactor POMs first so dependency resolution is cached across
# source-only changes.
COPY pom.xml ./
COPY iot-common/pom.xml iot-common/
COPY gateway-service/pom.xml gateway-service/
COPY processing-service/pom.xml processing-service/
COPY sensor-simulator-service/pom.xml sensor-simulator-service/
COPY device-registry-service/pom.xml device-registry-service/
COPY history-service/pom.xml history-service/
COPY iam-service/pom.xml iam-service/
COPY notification-service/pom.xml notification-service/
COPY api-gateway/pom.xml api-gateway/
RUN mvn -B -q -pl device-registry-service -am -DskipTests dependency:go-offline

# Copy sources and build the executable jar.
COPY iot-common/src iot-common/src
COPY device-registry-service/src device-registry-service/src
RUN mvn -B -q -pl device-registry-service -am -DskipTests package

# ---- Runtime stage: minimal JRE 25 ----
FROM eclipse-temurin:25-jre
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -r -u 1001 spring
COPY --from=build /workspace/device-registry-service/target/device-registry-service-*.jar app.jar
USER spring
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
