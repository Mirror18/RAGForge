# 本地运行

本地要启动的是 `compose/compose.yaml` 里的 PostgreSQL、Qdrant、RabbitMQ、Valkey 和 MinIO。Server、Worker 和 Web 默认在本机用源码启动，不进容器。

```text
scripts/dev/start-local.bat
```

需要把应用也打进容器时，用 `scripts/dev/core.py --profile app`。镜像由 `docker/Dockerfile` 的 `server`、`worker`、`web` 三个 target 构建。Web 容器的反向代理在 `docker/nginx.conf`。
