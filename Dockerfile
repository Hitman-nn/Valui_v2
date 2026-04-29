FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Добавляем curl для healthcheck
RUN apk add --no-cache curl

COPY valui-app/target/valui-app-*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "app.jar"]
