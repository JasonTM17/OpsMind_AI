# syntax=docker/dockerfile:1.7
FROM maven:3.9.12-eclipse-temurin-21@sha256:c3c9d3ac4ce8431a3995c0318b8d390f448e693dd4fabc16e9b68d2e1f3d7b46 AS build
WORKDIR /workspace
COPY services/tool-gateway/pom.xml pom.xml
RUN --mount=type=cache,target=/root/.m2 mvn -q dependency:go-offline
COPY services/tool-gateway/src src
RUN --mount=type=cache,target=/root/.m2 mvn -q -DskipTests package

FROM eclipse-temurin:25-jre-noble@sha256:2f1da100788559b397bcf48c736169ea5b070bde84e55f203bbee8e83d87a175 AS runtime
WORKDIR /app
RUN apt-get update && \
    apt-get install --yes --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/* && \
    groupadd --system --gid 10001 opsmind && \
    useradd --system --uid 10001 --gid opsmind --no-create-home opsmind
USER opsmind
COPY --from=build --chown=opsmind:opsmind /workspace/target/tool-gateway.jar app.jar
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
