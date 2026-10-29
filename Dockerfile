# The image the tests run in: JDK 25 with the FFmpeg 7.1 shared libraries, which sonaudio loads into the JVM and whose
# struct layouts it is written for. Debian 13 ships FFmpeg 7.1. The ffmpeg program is only there to make the fixtures
# (see src/test/resources/README.md).
FROM eclipse-temurin:25-jdk AS jdk

FROM debian:trixie-slim
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg ca-certificates \
    && rm -rf /var/lib/apt/lists/*
COPY --from=jdk /opt/java/openjdk /opt/java
ENV JAVA_HOME=/opt/java
ENV PATH=/opt/java/bin:$PATH
WORKDIR /workspace
