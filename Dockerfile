FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/ gradle/
COPY kotlin-js-store/ kotlin-js-store/
COPY config/ config/
COPY src/ src/
RUN chmod +x gradlew && ./gradlew --no-daemon fatJar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*-all.jar /app/app.jar
COPY --from=build /app/build/dist/js/productionExecutable/ /app/build/dist/js/productionExecutable/
RUN mkdir -p /app/data
ENV PORT=8080 \
    SCOREBOARD_FILE=/app/data/scoreboard.json
EXPOSE 8080
CMD ["java", "-jar", "/app/app.jar"]
