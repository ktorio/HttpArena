# syntax=docker/dockerfile:1
# Fallback for the `m2` build context; build.sh overrides it with the host's
# ~/.m2/repository so locally published (SNAPSHOT) artifacts resolve.
FROM scratch AS m2

FROM gradle:9.5.1-jdk21-corretto AS build
WORKDIR /app
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY gradle/libs.versions.toml ./gradle/
# Busts the layer cache below whenever the mounted m2 content changes.
# build.sh sets this to a hash of the host's locally-published ktor
# artifacts -- without it, Docker sees unchanged COPY inputs above and
# reuses a stale cached layer (with an older resolved SNAPSHOT baked in)
# instead of re-running gradle against the freshly mounted repo.
ARG M2_HASH=""
RUN --mount=from=m2,target=/m2 \
    echo "m2-hash: $M2_HASH" && \
    gradle dependencies --no-daemon -q --refresh-dependencies -Dmaven.repo.local=/m2 2>/dev/null || true
COPY src ./src
RUN --mount=from=m2,target=/m2 \
    gradle buildFatJar --no-daemon -q --refresh-dependencies -Dmaven.repo.local=/m2

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/ktor-httparena.jar .
EXPOSE 8080 8081 8082 8443
# No explicit collector flag below on purpose: JDK 21 defaults to G1 anyway,
# and JAVA_TOOL_OPTIONS (see scripts/lib/framework.sh) only appends flags — a
# hardcoded -XX:+UseG1GC here would collide with e.g. -XX:+UseZGC passed via
# JAVA_TOOL_OPTIONS ("Multiple garbage collectors selected").
#
# -Xms/-Xmx below ARE hardcoded (fixed-size heap, no resizing pauses), for the
# evacuation-failure experiment: default ergonomics (25% of container memory)
# were capping the heap at ~1.9GB, well below what the box can actually spare.
# Unlike the collector flag, this is safe from a hard JVM-startup collision,
# but it does take precedence over any -Xmx a future JAVA_TOOL_OPTIONS sweep
# tries to pass (JVM uses the last-specified value, and these come after
# JAVA_TOOL_OPTIONS's on the effective command line) — remove/adjust this
# line first if resuming that kind of sweep.
ENTRYPOINT ["java", \
  "-server", \
  "-XX:+UseNUMA", \
  "-XX:+AlwaysPreTouch", \
  "-Xms8g", \
  "-Xmx8g", \
  "-XX:StartFlightRecording=filename=/profiles/profile.jfr,settings=profile,dumponexit=true,maxsize=2048m", \
  "-XX:FlightRecorderOptions=stackdepth=256", \
  "-Dio.netty.transport.noNative=false", \
  "-Dio.netty.buffer.checkBounds=false", \
  "-Dio.netty.buffer.checkAccessible=false", \
  "-Dio.netty.leakDetection.level=disabled", \
  "-Dio.netty.noKeySetOptimization=true", \
  "-jar", "ktor-httparena.jar"]
