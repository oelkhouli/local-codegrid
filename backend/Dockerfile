FROM docker.io/library/maven:3.9.11-eclipse-temurin-21@sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237 AS build
ENV MAVEN_OPTS="-Xmx256m"
WORKDIR /build
COPY backend/ backend/
RUN mvn --batch-mode --no-transfer-progress -f backend/pom.xml -DskipTests package

FROM build AS tests
CMD ["mvn", "--batch-mode", "--no-transfer-progress", "-f", "backend/pom.xml", "test"]

FROM docker.io/library/eclipse-temurin:21-jre-jammy@sha256:eebd356ad7358b7094758e5787a6726f332917cfd56feab6457c56dab895cdbf AS api
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /build/backend/api/target/api-0.1.0-SNAPSHOT.jar app.jar
USER 10001:10001
ENTRYPOINT ["java", "-Xmx192m", "-XX:ActiveProcessorCount=2", "-jar", "/app/app.jar"]

FROM docker.io/library/docker:28-cli@sha256:625d9431a9f54c5a2bc90f24f0e1c3d55b1349fd857dd85035f98c2c9acbdd4d AS docker-cli
FROM docker.io/library/eclipse-temurin:21-jre-jammy@sha256:eebd356ad7358b7094758e5787a6726f332917cfd56feab6457c56dab895cdbf AS worker
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
COPY --from=docker-cli /usr/local/bin/docker /usr/local/bin/docker
WORKDIR /app
COPY --from=build /build/backend/worker/target/worker-0.1.0-SNAPSHOT-all.jar app.jar
ENTRYPOINT ["java", "-Xmx96m", "-XX:ActiveProcessorCount=1", "-jar", "/app/app.jar"]
