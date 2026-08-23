# Deployment Assets

- `compose/`：MVP 唯一正式部署资产；`compose.yaml` 默认提供 core，`app` profile 提供 Server、Worker 和 Web，`ollama`/`observability` 为显式可选 profile。
- `docker/Dockerfile`：应用统一多目标构建入口，targets 为 `server`、`worker`、`web`。
- `docker/nginx.conf`：Web 容器的 SPA fallback 与 `/api`、`/actuator` 反向代理配置。
- `kubernetes/`：未来预留，当前不维护双套生产配置。

部署规则见 [DEPLOYMENT.md](../docs/05-operations/DEPLOYMENT.md)。生成/复制第三方 Compose 片段同样要经过许可证和 Secret 检查。

常用入口：

```text
python scripts/dev/core.py --profile app build
python scripts/dev/core.py --profile app up --build
python scripts/dev/core.py --profile app ps
python scripts/dev/core.py --profile app down
```

Windows 本地源码启动入口见 [`scripts/dev/start-local.bat`](../scripts/dev/start-local.bat)；它与容器化 `app` profile 是两种互斥的运行模式，不应同时占用同一组 Server/Web 端口。
