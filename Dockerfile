FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY src ./src
RUN chmod +x gradlew && ./gradlew --no-daemon test bootJar

FROM eclipse-temurin:17-jre-jammy AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates ffmpeg tini wget \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system frontline \
    && useradd --system --gid frontline --home-dir /app --shell /usr/sbin/nologin frontline \
    && mkdir -p /var/cache/frontline-replays \
    && chown frontline:frontline /var/cache/frontline-replays
WORKDIR /app
COPY --from=build /workspace/build/libs/frontline-nations-0.1.0.jar app.jar
USER frontline
EXPOSE 8080
ENTRYPOINT ["/usr/bin/tini", "-s", "--", "java", "-jar", "/app/app.jar"]
