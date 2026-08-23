# syntax=docker/dockerfile:1.7

FROM maven:3.9-eclipse-temurin-21 AS java-build
WORKDIR /workspace

COPY pom.xml .
COPY apps/server/pom.xml apps/server/pom.xml
COPY apps/ingestion-worker/pom.xml apps/ingestion-worker/pom.xml

COPY apps/server/src apps/server/src
COPY apps/ingestion-worker/src apps/ingestion-worker/src
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests package

FROM node:22-bookworm-slim AS web-build
WORKDIR /workspace/apps/web
COPY apps/web/package.json apps/web/package-lock.json ./
RUN npm ci
COPY apps/web ./
RUN npm run build

FROM eclipse-temurin:21-jre-jammy AS server
WORKDIR /app
RUN useradd --system --uid 10001 --no-create-home ragforge
COPY --from=java-build /workspace/apps/server/target/ragforge-server-*.jar /app/ragforge-server.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/ragforge-server.jar"]

FROM eclipse-temurin:21-jre-jammy AS worker
WORKDIR /app
RUN useradd --system --uid 10001 --no-create-home ragforge
COPY --from=java-build /workspace/apps/ingestion-worker/target/ragforge-ingestion-worker-*.jar /app/ragforge-ingestion-worker.jar
USER 10001
ENTRYPOINT ["java", "-jar", "/app/ragforge-ingestion-worker.jar"]

FROM nginx:1.27-alpine AS web
COPY --from=web-build /workspace/apps/web/dist /usr/share/nginx/html
COPY deploy/docker/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
