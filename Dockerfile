# Stage 1: Build
FROM gradle:8.11-jdk21 AS build
WORKDIR /app
COPY build.gradle.kts settings.gradle.kts ./
COPY gradle/ gradle/
COPY gradlew ./
COPY src/ src/
RUN gradle jar --no-daemon

# Stage 2: Run
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/MineNova-DiscordBot-1.0.0.jar bot.jar
CMD ["java", "-jar", "bot.jar"]
