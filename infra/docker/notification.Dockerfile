# notification-service runtime image (standalone mode, RabbitMQ consumer). Build context = repo root.
# NOT built in the authoring environment (no Docker).
FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
COPY notification-service/target/notification-service-1.0.0-exec.jar /app/app.jar
USER app
EXPOSE 8082
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
