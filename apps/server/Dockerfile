FROM eclipse-temurin:21-jre

WORKDIR /app
RUN useradd --system --uid 10001 ragforge
COPY target/ragforge-server-*.jar /app/ragforge-server.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/ragforge-server.jar"]
