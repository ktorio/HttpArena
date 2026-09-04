FROM gradle:9.5.1-jdk25-corretto AS build
WORKDIR /app
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY gradle/libs.versions.toml ./gradle/
RUN gradle dependencies --no-daemon -q 2>/dev/null || true
COPY src ./src
RUN gradle buildFatJar --no-daemon -q

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/build/libs/ktor-httparena.jar .
EXPOSE 8080 8081 8082 8443
ENTRYPOINT ["java", \
  "-server", \
  "-XX:+UseNUMA", \
  "-XX:+AlwaysPreTouch", \
  "-XX:StartFlightRecording=filename=/profiles/profile.jfr,settings=profile,dumponexit=true,maxsize=2048m", \
  "-XX:FlightRecorderOptions=stackdepth=256", \
  "-Dio.netty.transport.noNative=false", \
  "-Dio.netty.buffer.checkBounds=false", \
  "-Dio.netty.buffer.checkAccessible=false", \
  "-Dio.netty.leakDetection.level=disabled", \
  "-Dio.netty.noKeySetOptimization=true", \
  "-jar", "ktor-httparena.jar"]
