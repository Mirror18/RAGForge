# syntax=docker/dockerfile:1.7

FROM maven:3.9-eclipse-temurin-21 AS java-build
WORKDIR /workspace

COPY pom.xml .
COPY apps/server/pom.xml apps/server/pom.xml
COPY apps/ingestion-worker/pom.xml apps/ingestion-worker/pom.xml

COPY apps/server/src apps/server/src
COPY apps/ingestion-worker/src apps/ingestion-worker/src
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests package
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests -pl apps/ingestion-worker package spring-boot:repackage

FROM node:22-bookworm-slim AS web-build
WORKDIR /workspace/apps/web
COPY apps/web/package.json apps/web/package-lock.json ./
RUN npm ci
COPY apps/web ./
RUN npm run build

FROM eclipse-temurin:21-jre-jammy AS java-runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 ragforge \
    && useradd --system --uid 10001 --gid 10001 --no-create-home ragforge

FROM java-runtime AS server
WORKDIR /app
COPY --from=java-build /workspace/apps/server/target/ragforge-server-*.jar /app/ragforge-server.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/ragforge-server.jar"]

FROM java-runtime AS worker
WORKDIR /app
COPY --from=java-build /workspace/apps/ingestion-worker/target/ragforge-ingestion-worker-*.jar /app/ragforge-ingestion-worker.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/ragforge-ingestion-worker.jar"]

FROM nginx:1.27-alpine AS web
COPY --from=web-build /workspace/apps/web/dist /usr/share/nginx/html
COPY deploy/docker/nginx.conf /etc/nginx/conf.d/default.conf
RUN sed -i -e 's#/var/run/nginx.pid#/tmp/nginx.pid#' \
    -e 's#/run/nginx.pid#/tmp/nginx.pid#' \
    -e 's#/var/log/nginx/error.log#/dev/stderr#' \
    -e 's#/var/log/nginx/access.log#/dev/stdout#' /etc/nginx/nginx.conf \
    && mkdir -p /var/cache/nginx /var/run \
    && chown -R 101:101 /etc/nginx /var/cache/nginx /var/run /usr/share/nginx/html
USER 101:101
EXPOSE 8080
ENTRYPOINT ["nginx"]
CMD ["-g", "daemon off;"]
