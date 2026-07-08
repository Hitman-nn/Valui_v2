FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Добавляем curl для healthcheck
RUN apk add --no-cache curl

COPY valui-app/target/valui-app-*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-XX:+HeapDumpOnOutOfMemoryError", \
  "-XX:HeapDumpPath=/tmp/heap-dump.hprof", \
  "-jar", "app.jar"]
