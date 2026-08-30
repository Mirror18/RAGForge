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
COPY deploy/docker/HttpHealthcheck.java /tmp/HttpHealthcheck.java
RUN mkdir -p /tmp/healthcheck-classes \
    && javac --release 21 -d /tmp/healthcheck-classes /tmp/HttpHealthcheck.java \
    && jar --create --file /tmp/ragforge-healthcheck.jar -C /tmp/healthcheck-classes HttpHealthcheck.class

FROM node:22-bookworm-slim@sha256:83f487e0a63425e5b4d146fb5e5be574bcbe1b7b843d3ebafdd95eaf7767a7e5 AS web-build
WORKDIR /workspace/apps/web
COPY apps/web/package.json apps/web/package-lock.json ./
RUN npm ci
COPY apps/web ./
RUN npm run build

FROM cgr.dev/chainguard/jre:latest@sha256:8442d5863e82c43320f20d40dd6169afa4173debae9433a597b0e2df3805e3b0 AS java-runtime

FROM java-runtime AS server
WORKDIR /app
COPY --from=java-build /workspace/apps/server/target/ragforge-server-*.jar /app/ragforge-server.jar
COPY --from=java-build /tmp/ragforge-healthcheck.jar /opt/ragforge-healthcheck.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/ragforge-server.jar"]

FROM java-runtime AS worker
WORKDIR /app
COPY --from=java-build /workspace/apps/ingestion-worker/target/ragforge-ingestion-worker-*.jar /app/ragforge-ingestion-worker.jar
COPY --from=java-build /tmp/ragforge-healthcheck.jar /opt/ragforge-healthcheck.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/ragforge-ingestion-worker.jar"]

FROM alpine:3.22@sha256:14358309a308569c32bdc37e2e0e9694be33a9d99e68afb0f5ff33cc1f695dce AS web-healthcheck-build
COPY deploy/docker/http-healthcheck.c /tmp/http-healthcheck.c
RUN apk add --no-cache build-base \
    && cc -static -O2 -s -o /tmp/http-healthcheck /tmp/http-healthcheck.c

FROM cgr.dev/chainguard/nginx:latest@sha256:f801aa4ac908f6c3847efe81b577e4f34e1f237b5556dfbe698127ad464db269 AS web
COPY --from=web-build /workspace/apps/web/dist /usr/share/nginx/html
COPY deploy/docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY deploy/docker/nginx-main.conf /etc/nginx/nginx.conf
COPY --from=web-healthcheck-build /tmp/http-healthcheck /usr/local/bin/http-healthcheck
USER 65532:65532
EXPOSE 8080
ENTRYPOINT ["/usr/sbin/nginx"]
CMD ["-c", "/etc/nginx/nginx.conf", "-e", "/dev/stderr", "-g", "daemon off;"]
