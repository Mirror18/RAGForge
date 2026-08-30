# RAGForge 镜像固定清单

本清单对应 `compose.yaml` 与 `deploy/docker/Dockerfile` 的当前审核基线（2026-08-30）。公共基础设施镜像和 Dockerfile 的所有外部 `FROM` 均使用 manifest digest；更新版本时必须重新解析 digest，并在同一变更中更新本清单。

## Compose 基础设施镜像

| 服务 | 固定引用 |
| --- | --- |
| postgres | `postgres:16.4-alpine@sha256:5660c2cbfea50c7a9127d17dc4e48543eedd3d7a41a595a2dfa572471e37e64c` |
| qdrant | `qdrant/qdrant:v1.11.5@sha256:7a4788934788a7ed9cbf6b8cc3ca1ee880dcd969cf8c6639dc7d0e446cbd4b47` |
| rabbitmq | `rabbitmq:3.13-management-alpine@sha256:606d8c0d6b3c18d1da9afc53bc7cdb2a8d5486df91b5a9830e9e07626c9ae281` |
| valkey | `valkey/valkey:8.0.1-alpine@sha256:75010b6854cb5ba6a0b1540d1bd3238541a31e3f8018cd31f9e5b92bb3192fa6` |
| minio | `minio/minio:RELEASE.2024-12-18T13-15-44Z@sha256:1dce27c494a16bae114774f1cec295493f3613142713130c2d22dd5696be6ad3` |
| ollama (optional) | `ollama/ollama:0.5.4@sha256:18bfb1d605604fd53dcad20d0556df4c781e560ebebcd923454d627c994a0e37` |

## Dockerfile 外部基础镜像

| 阶段 | 固定引用 |
| --- | --- |
| Dockerfile frontend | `docker/dockerfile:1.7@sha256:a57df69d0ea827fb7266491f2813635de6f17269be881f696fbfdf2d83dda33e` |
| java-build | `maven:3.9-eclipse-temurin-21@sha256:8f6ac126f7810bb5549c4cd122d2bf0e9cda5bdeb0838aa928f09e779fd8bef8` |
| web-build | `node:22-bookworm-slim@sha256:83f487e0a63425e5b4d146fb5e5be574bcbe1b7b843d3ebafdd95eaf7767a7e5` |
| java-runtime | `eclipse-temurin:21-jre-jammy@sha256:eebd356ad7358b7094758e5787a6726f332917cfd56feab6457c56dab895cdbf` |
| web | `nginx:1.27-alpine@sha256:65645c7bb6a0661892a8b03b89d0743208a18dd2f3f17a54ef4b76fb8e2f2a10` |

## 应用镜像证据

应用服务保留 `:local` 构建别名是因为 Docker BuildKit 拒绝将 digest 引用作为构建输出标签。CI 仅以这些别名完成本地构建，随后立刻通过 `docker image inspect` 取得不可变 image ID，并以该 `sha256` ID 作为 SBOM、Grype 和 Secret 审计目标；发布输入必须通过 `RAGFORGE_*_IMAGE` 传入 `name@sha256:<digest>`，不得将 `:local` 推送或用于生产。

每次 CI 运行上传 `ragforge-p7d-02-supply-chain-<run_id>` artifact，记录 server、worker、web 的 immutable reference、image ID、构建引用和扫描证据名称。该 artifact 是目标镜像证据的唯一关联索引，不以源码目录 SBOM 代替。

本次本地合成构建的 image ID（仅用于复核构建与扫描链路，不是生产发布输入）如下：

| 服务 | 构建别名 | image ID |
| --- | --- | --- |
| server | `ragforge/server:local` | `sha256:b42a579db9c159dd90e99d38f9c12e7e4ec2d8158d540fab22c83123b33d044d` |
| worker | `ragforge/ingestion-worker:local` | `sha256:df5f78ec2dc4799a4d1c80ec99a056398782cc2e4583424c2b5c6ef3a2f4ad73` |
| web | `ragforge/web:local` | `sha256:f70e12a53764e297569ffa01f52297a5ae8871e70cf26467d5c849cf0822bd2b` |
