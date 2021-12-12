# syntax=docker/dockerfile:1

FROM gradle:jdk11 AS builder
WORKDIR /app/
COPY src/ ./src/
COPY build.gradle.kts gradle.properties ./
RUN gradle uberJar

FROM openjdk:11
WORKDIR /app/
COPY --from=builder /app/build/libs/app-1.0.0-uber.jar ./GermanRapHausmeisterUber.jar
ENTRYPOINT ["java", "--add-opens", "java.base/java.lang=com.google.guice,ALL-UNNAMED","-jar", "GermanRapHausmeisterUber.jar"]
CMD ["restart=true", "configPath=config/config.yml"]


