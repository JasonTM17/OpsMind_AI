# syntax=docker/dockerfile:1.7
FROM maven:3.9.15-eclipse-temurin-26@sha256:029a8e2838ae68238ffb8be407cddbb3f07d4d839c60c6f26c619a69fd184531 AS build
WORKDIR /workspace
COPY services/tool-gateway/pom.xml pom.xml
RUN --mount=type=cache,target=/root/.m2 mvn -q dependency:go-offline
COPY services/tool-gateway/src src
RUN --mount=type=cache,target=/root/.m2 mvn -q -DskipTests package

FROM eclipse-temurin:21-jre-noble@sha256:373787d1d45a87f084fda43e7de0e9acf5eedee049446efac738f13587ec4c64 AS runtime
WORKDIR /app
RUN apt-get update && \
    apt-get install --yes --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/* && \
    groupadd --system --gid 10001 opsmind && \
    useradd --system --uid 10001 --gid opsmind --no-create-home opsmind
USER opsmind
COPY --from=build --chown=opsmind:opsmind /workspace/target/tool-gateway.jar app.jar
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
