# ---- build stage: compile both modules ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Copy poms first so dependency resolution is cached across source changes.
COPY pom.xml .
COPY target-app/pom.xml target-app/pom.xml
COPY agent/pom.xml agent/pom.xml
RUN mvn -q -B -f pom.xml dependency:go-offline || true

# Copy sources and build the fat jar (target-app) and the agent jar.
COPY target-app/src target-app/src
COPY agent/src agent/src
RUN mvn -q -B -DskipTests package

# ---- runtime stage: full JDK is required for the jdk.jdi module used by the agent ----
FROM eclipse-temurin:21-jdk
WORKDIR /app

COPY --from=build /build/target-app/target/target-app.jar /app/target-app.jar
COPY --from=build /build/agent/target/agent.jar /app/agent.jar
COPY entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh && mkdir -p /app/captures

# Breakpoint and addresses can be overridden at `docker run` with -e.
ENV BREAKPOINT=com.hyperprobe.target.AdditionEngine.add
ENV JDWP_ADDRESS=localhost:5005
ENV CAPTURE_DIR=/app/captures

EXPOSE 8080
ENTRYPOINT ["/app/entrypoint.sh"]
