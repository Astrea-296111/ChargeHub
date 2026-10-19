# syntax=docker/dockerfile:1.7
# All base-image identities are fixed. No host-built target/ output is required.
FROM maven:3.9.16-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS build
WORKDIR /src
COPY . .
ARG BUILD_TIMESTAMP=2026-10-18T00:00:00Z
RUN --mount=type=cache,id=chargehub-maven,target=/root/.m2 \
    --mount=type=secret,id=maven_settings,target=/root/.m2/settings.xml,required=false \
    --mount=type=secret,id=maven_ca,target=/run/secrets/maven_ca.crt,required=false \
    --mount=type=tmpfs,target=/tmp/chargehub-trust \
    set -eu; \
    if [ -s /run/secrets/maven_ca.crt ]; then \
      cp "$JAVA_HOME/lib/security/cacerts" /tmp/chargehub-trust/cacerts; \
      keytool -importcert -noprompt -alias chargehub-build-proxy -file /run/secrets/maven_ca.crt -keystore /tmp/chargehub-trust/cacerts -storepass changeit; \
      export MAVEN_OPTS="${MAVEN_OPTS:-} -Djavax.net.ssl.trustStore=/tmp/chargehub-trust/cacerts -Djavax.net.ssl.trustStorePassword=changeit"; \
    fi; \
    mvn -B -ntp -C -DskipTests -Dproject.build.outputTimestamp="${BUILD_TIMESTAMP}" package \
    && javac --release 21 -d /probe scripts/container/Healthcheck.java

FROM eclipse-temurin:21-jre@sha256:227118764cccd3c3294617061aaa93a08309c63a395eefa83e6b35032992ea99 AS runtime
WORKDIR /app
ARG VCS_REF=unknown
LABEL org.opencontainers.image.title="ChargeHub" \
      org.opencontainers.image.source="https://github.com/Astrea-296111/ChargeHub" \
      org.opencontainers.image.revision="${VCS_REF}"
COPY --from=build --chmod=0444 /src/chargehub-app/target/chargehub-app-1.0.0-SNAPSHOT.jar /app/chargehub.jar
COPY --from=build --chmod=0444 /probe/Healthcheck.class /app/probe/Healthcheck.class
ENV MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED=true
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=20s --timeout=5s --start-period=60s --retries=3 \
    CMD ["java", "-Xms8m", "-Xmx32m", "-cp", "/app/probe", "Healthcheck"]
ENTRYPOINT ["java","-XX:MaxRAMPercentage=65","-Duser.timezone=UTC","-jar","/app/chargehub.jar"]
