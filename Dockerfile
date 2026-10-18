FROM eclipse-temurin:21-jre@sha256:227118764cccd3c3294617061aaa93a08309c63a395eefa83e6b35032992ea99
WORKDIR /app
COPY --chmod=0444 chargehub-app/target/chargehub-app-1.0.0-SNAPSHOT.jar /app/chargehub.jar
USER 10001:10001
ENTRYPOINT ["java","-XX:MaxRAMPercentage=65","-Duser.timezone=UTC","-jar","/app/chargehub.jar"]
