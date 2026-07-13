FROM eclipse-temurin:21-jre AS builder
WORKDIR /app
COPY build/libs/*.jar app.jar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /app/app.jar app.jar
EXPOSE 8086
ENTRYPOINT ["java", "-jar", "app.jar"]
