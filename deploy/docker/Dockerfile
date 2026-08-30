# syntax=docker/dockerfile:1.7@sha256:a57df69d0ea827fb7266491f2813635de6f17269be881f696fbfdf2d83dda33e

FROM maven:3.9-eclipse-temurin-21@sha256:8f6ac126f7810bb5549c4cd122d2bf0e9cda5bdeb0838aa928f09e779fd8bef8 AS java-build
WORKDIR /workspace

COPY pom.xml .
COPY apps/server/pom.xml apps/server/pom.xml
COPY apps/ingestion-worker/pom.xml apps/ingestion-worker/pom.xml

COPY apps/server/src apps/server/src
COPY apps/ingestion-worker/src apps/ingestion-worker/src
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests package
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests -pl apps/ingestion-worker package spring-boot:repackage

FROM node:22-bookworm-slim@sha256:83f487e0a63425e5b4d146fb5e5be574bcbe1b7b843d3ebafdd95eaf7767a7e5 AS web-build
WORKDIR /workspace/apps/web
COPY apps/web/package.json apps/web/package-lock.json ./
RUN npm ci
COPY apps/web ./
RUN npm run build

FROM eclipse-temurin:21-jre-jammy@sha256:eebd356ad7358b7094758e5787a6726f332917cfd56feab6457c56dab895cdbf AS java-runtime
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

FROM nginx:1.27-alpine@sha256:65645c7bb6a0661892a8b03b89d0743208a18dd2f3f17a54ef4b76fb8e2f2a10 AS web
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
