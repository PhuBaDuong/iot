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
COPY notification-service/pom.xml notification-service/
COPY api-gateway/pom.xml api-gateway/
RUN mvn -B -q -pl iam-service -am -DskipTests dependency:go-offline

# Copy sources and build the executable jar.
COPY iot-common/src iot-common/src
COPY iam-service/src iam-service/src
RUN mvn -B -q -pl iam-service -am -DskipTests package

# ---- Extract stage: Spring Boot layer tools ----
FROM eclipse-temurin:25-jre AS extract
WORKDIR /extract
COPY --from=build /workspace/iam-service/target/iam-service-*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

# ---- Runtime stage: layered JRE 25 Alpine ----
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
RUN addgroup -S spring && adduser -S -G spring -u 1001 spring
COPY --from=extract /extract/extracted/dependencies/ ./
COPY --from=extract /extract/extracted/spring-boot-loader/ ./
COPY --from=extract /extract/extracted/snapshot-dependencies/ ./
COPY --from=extract /extract/extracted/application/ ./
USER spring
EXPOSE 9000
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
