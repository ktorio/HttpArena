# syntax=docker/dockerfile:1
#
# Host Maven repository (requires BuildKit). Defaults to an empty stage;
# supply the host directory with a named build context:
#   docker build --build-context m2repo=$HOME/.m2/repository -t ktor-tuned .
# Use any other directory to override, e.g. --build-context m2repo=/opt/m2
# Optional JFR profiling (see ENABLE_JFR below):
#   docker build --build-arg ENABLE_JFR=true ...
#   docker run -v $PWD/jfr:/jfr ...   # recording exported to /jfr/profile.jfr on exit
FROM scratch AS m2repo

FROM gradle:9.5.1-jdk25-corretto AS build
WORKDIR /app
# Gradle reads the mounted host repo (read-only) via maven.repo.local / mavenLocal()
# Override the Ktor catalog version with --build-arg KTOR_VERSION=x.y.z
ARG KTOR_VERSION=
ENV GRADLE_OPTS="-Dmaven.repo.local=/m2repo"
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY gradle/libs.versions.toml ./gradle/
RUN --mount=type=bind,from=m2repo,target=/m2repo \
    gradle ${KTOR_VERSION:+-PktorVersion=$KTOR_VERSION} dependencies --no-daemon -q 2>/dev/null || true
COPY src ./src
RUN --mount=type=bind,from=m2repo,target=/m2repo \
    gradle ${KTOR_VERSION:+-PktorVersion=$KTOR_VERSION} buildFatJar --no-daemon -q

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/build/libs/ktor-tuned-httparena.jar .
# Build-time switch: true enables JFR; the recording is dumped on exit to /jfr/profile.jfr
ARG ENABLE_JFR=false
RUN if [ "$ENABLE_JFR" = "true" ]; then \
      mkdir -p /jfr && echo "-XX:StartFlightRecording=filename=/jfr/profile.jfr,dumponexit=true,settings=profile" > /jfr.opts; \
    else : > /jfr.opts; fi
EXPOSE 8080 8081 8082 8443
ENTRYPOINT ["sh", "-c", "exec java $(cat /jfr.opts) \"$@\"", "java", \
  "-server", \
  "-XX:+UseG1GC", \
  "-XX:+UseNUMA", \
  "-XX:+AlwaysPreTouch", \
  "-Dio.netty.transport.noNative=false", \
  "-Dio.netty.buffer.checkBounds=false", \
  "-Dio.netty.buffer.checkAccessible=false", \
  "-Dio.netty.leakDetection.level=disabled", \
  "-jar", "ktor-tuned-httparena.jar"]
