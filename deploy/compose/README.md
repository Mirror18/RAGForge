# 本地 Compose

`compose.yaml` 启动本地依赖：PostgreSQL、Qdrant、RabbitMQ、Valkey、MinIO。应用容器在 `app` profile 里，默认不启动。

镜像文件在 [`deploy/docker/Dockerfile`](../docker/Dockerfile)。

```text
python scripts/dev/core.py up
python scripts/dev/core.py health
python scripts/dev/core.py down
```

Windows 上直接跑源码用 [`scripts/dev/start-local.bat`](../../scripts/dev/start-local.bat)。不要和 `app` profile 同时占用 Server、Web 端口。

默认 Web 是 `http://localhost:25174`，Server 是 `http://localhost:25082`。数据库端口是 `25432`。
