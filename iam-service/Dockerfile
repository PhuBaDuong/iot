# syntax=docker/dockerfile:1
# =============================================================================
# IAM Service - multi-stage build
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
RUN mvn -B -q -pl iam-service -am -DskipTests dependency:go-offline

# Copy sources and build the executable jar.
COPY iot-common/src iot-common/src
COPY iam-service/src iam-service/src
RUN mvn -B -q -pl iam-service -am -DskipTests package

# ---- Runtime stage: minimal JRE 25 ----
FROM eclipse-temurin:25-jre
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -r -u 1001 spring
COPY --from=build /workspace/iam-service/target/iam-service-*.jar app.jar
USER spring
EXPOSE 9000
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
