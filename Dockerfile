# SourbyCraft 26.2 reference runtime image.
#
# Build the slim release jar first:
#   ./gradlew applyAllPatches :sourbycraft-server:compileJava assembleReleaseArtifacts
#   docker build --build-arg JAR=release/SourbyCraft-26.2-REL.jar -t sourbycraft:26.2 .
#
# First boot needs internet once (SourbyLoader fetches externalized libs into the
# paperclip cache); afterwards it runs offline. Persist /data to keep worlds, the
# lib cache, and the CDS archive across restarts.
FROM eclipse-temurin:25-jre AS runtime

LABEL org.opencontainers.image.title="SourbyCraft" \
      org.opencontainers.image.description="High-performance Paper 26.2 survival fork (200+ players, Auto-CDS, anti-xray)" \
      org.opencontainers.image.licenses="PolyForm-Noncommercial-1.0.0"

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

# MEMORY: heap in MiB (omit to auto-size from the container's memory limit).
# EULA:   set "true" to accept the Minecraft EULA. JVM_OPTS: extra flags.
ENV MEMORY="" JVM_OPTS="" EULA="false" SERVER_JAR=/app/server.jar DATA_DIR=/data
STOPSIGNAL SIGTERM

ENTRYPOINT ["/app/entrypoint.sh"]
