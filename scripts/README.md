# Scripts

- `dev/core.py`：启动和停止本地 Compose。
- `dev/start-local.bat`：Windows 上启动数据库依赖，再在本机跑 Server、Worker 和 Web。
- `ci/`：架构、链接、接口定义和密钥扫描。
- `ops/health_probe.py`：检查 PostgreSQL、Qdrant、RabbitMQ、Valkey、MinIO 是否就绪。
合成样本和离线评估在 `tests/`，不在这里。

应用镜像只有 [`../deploy/docker/Dockerfile`](../deploy/docker/Dockerfile)。
