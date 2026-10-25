# core-service runtime image. Build the jar first (scripts/run-local.sh build). Build context = repo root.
# NOT built in the authoring environment (no Docker).
FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
COPY core-service/target/core-service-1.0.0.jar /app/app.jar
USER app
EXPOSE 8081
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
