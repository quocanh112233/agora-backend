# syntax=docker/dockerfile:1
FROM eclipse-temurin:25-jdk AS build
RUN apt-get update && apt-get install -y --no-install-recommends unzip && rm -rf /var/lib/apt/lists/*
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY contracts/proto/ contracts/proto/
COPY libraries/ libraries/
COPY services/ services/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -DskipTests package

FROM eclipse-temurin:25-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /app/target/upload-tmp && chown -R 10001:10001 /app/target
WORKDIR /app
ARG SERVICE=identity-service
COPY --from=build --chown=10001:10001 /workspace/services/${SERVICE}/target/${SERVICE}-*.jar app.jar
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-Xmx192m -XX:ActiveProcessorCount=2"
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=12 CMD curl --fail --silent http://127.0.0.1:${HEALTH_PORT}/actuator/health > /dev/null || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
