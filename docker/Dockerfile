# SourbyCraft 26.2 reference runtime image.
#
# Build the slim release jar first:
#   ./gradlew applyAllPatches slimServerJar
#   cp build/libs/SourbyCraft-slim.jar release/SourbyCraft-26.2-REL.jar
#   docker build -f docker/Dockerfile --build-arg JAR=release/SourbyCraft-26.2-REL.jar -t sourbycraft:26.2 .
#
# Run from the repository root: the build context is the root, and BuildKit applies
# docker/Dockerfile.dockerignore to it.
#
# First boot needs internet once (SourbyLoader fetches externalized libs into the
# paperclip cache); afterwards it runs offline. Persist /data to keep worlds, the
# lib cache, and the CDS archive across restarts.
# Base image pinned by digest (audit SEC-12). Tag: eclipse-temurin:25-jre, the multi-arch OCI
# index (linux/amd64, arm64, ppc64le, s390x; Temurin 25.0.4.1_1-jre). Digest read on 2026-10-20
# from `docker buildx imagetools inspect eclipse-temurin:25-jre` and confirmed against the
# Docker-Content-Digest header of registry-1.docker.io/v2/library/eclipse-temurin/manifests/25-jre.
# To move to a newer JRE: re-run that inspect, paste the new index digest, keep the tag comment.
FROM eclipse-temurin:25-jre@sha256:dbb43d81099eec91e0800226ed2296d03146ee7857165b95112372ad0d67e87f AS runtime

LABEL org.opencontainers.image.title="SourbyCraft" \
      org.opencontainers.image.description="SourbyCraft 26.2 region-threaded Minecraft server (Aurora runtime, Java 25)" \
      org.opencontainers.image.licenses="GPL-3.0-only"

ARG JAR=release/SourbyCraft-26.2-REL.jar

RUN groupadd -r sourby && useradd -r -g sourby -d /data sourby \
 && mkdir -p /app /data/cache \
 && chown -R sourby:sourby /app /data

COPY --chown=sourby:sourby ${JAR} /app/server.jar
COPY --chown=sourby:sourby docker/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

USER sourby
WORKDIR /data
VOLUME ["/data"]
EXPOSE 25565

# UTF-8 locale so the JVM's stdout.encoding resolves to UTF-8 (the jre base image sets none,
# which would default to US-ASCII and garble the branded box-drawing console output).
ENV LANG=C.UTF-8 LC_ALL=C.UTF-8

# MEMORY: heap in MiB (omit to auto-size from the container's memory limit).
# EULA:   set "true" to accept the Minecraft EULA. JVM_OPTS: extra flags.
ENV MEMORY="" JVM_OPTS="" EULA="false" SERVER_JAR=/app/server.jar DATA_DIR=/data
STOPSIGNAL SIGTERM

ENTRYPOINT ["/app/entrypoint.sh"]
