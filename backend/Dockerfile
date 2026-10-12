# Production image of the backend. Tests are not run here: the deploy workflow only builds an image after the
# full CI (build, tests, architecture and OpenAPI checks) has passed on the same commit.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon --quiet dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon bootJar && cp build/libs/*.jar app.jar

FROM eclipse-temurin:25-jre
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
