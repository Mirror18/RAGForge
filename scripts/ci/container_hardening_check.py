#!/usr/bin/env python3
"""Fail-closed Compose and runtime checks for the application containers."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[2]
COMPOSE_FILE = REPO_ROOT / "deploy" / "compose" / "compose.yaml"
APP_SERVICES = ("server", "worker", "web")


def _run(command: list[str]) -> subprocess.CompletedProcess[str]:
    try:
        return subprocess.run(command, cwd=REPO_ROOT, text=True, capture_output=True, check=False)
    except FileNotFoundError as exc:
        raise RuntimeError(f"找不到必需命令：{exc.filename}") from exc


def _has_no_new_privileges(values: Any) -> bool:
    return any(str(value).lower() == "no-new-privileges:true" for value in (values or []))


def _has_tmpfs(values: Any, path: str) -> bool:
    return any(str(value).split(":", 1)[0] == path for value in (values or []))


def _health_test(service: dict[str, Any]) -> str:
    healthcheck = service.get("healthcheck") or {}
    test = healthcheck.get("test") or []
    return " ".join(str(value) for value in test)


def validate_model(model: dict[str, Any]) -> list[str]:
    """Validate rendered Compose policy and return all failures."""

    failures: list[str] = []
    services = model.get("services")
    if not isinstance(services, dict):
        return ["rendered Compose model 缺少 services"]

    for name in APP_SERVICES:
        service = services.get(name)
        if not isinstance(service, dict):
            failures.append(f"{name}: 服务不存在")
            continue
        if "ALL" not in [str(value).upper() for value in service.get("cap_drop", [])]:
            failures.append(f"{name}: cap_drop 未包含 ALL")
        if not _has_no_new_privileges(service.get("security_opt")):
            failures.append(f"{name}: 未启用 no-new-privileges")
        if service.get("read_only") is not True:
            failures.append(f"{name}: read_only 未启用")
        if not _has_tmpfs(service.get("tmpfs"), "/tmp"):
            failures.append(f"{name}: 未声明 /tmp tmpfs")
        for field in ("cpus", "mem_limit", "pids_limit", "stop_grace_period", "logging"):
            if not service.get(field):
                failures.append(f"{name}: 缺少 {field} 限制")
        logging = service.get("logging") or {}
        options = logging.get("options") or {}
        if logging.get("driver") != "json-file" or options.get("max-size") != "10m" or options.get("max-file") != "3":
            failures.append(f"{name}: json-file 日志轮转配置不完整")
        if not (service.get("healthcheck") or {}).get("test"):
            failures.append(f"{name}: 缺少 healthcheck")

    server_test = _health_test(services.get("server", {}))
    if "/actuator/health" not in server_test or "/actuator/health/readiness" in server_test:
        failures.append("server: healthcheck 未验证实际 actuator health")
    worker_test = _health_test(services.get("worker", {}))
    if "kill -0 1" not in worker_test:
        failures.append("worker: healthcheck 未验证 worker PID 1")
    web_test = _health_test(services.get("web", {}))
    if "127.0.0.1:8080" not in web_test:
        failures.append("web: healthcheck 未验证非特权 8080 端口")

    for name in ("server", "worker"):
        depends_on = services.get(name, {}).get("depends_on") or {}
        if not isinstance(depends_on, dict) or not depends_on:
            failures.append(f"{name}: depends_on 缺失条件映射")
        elif any((value or {}).get("condition") != "service_healthy" for value in depends_on.values()):
            failures.append(f"{name}: depends_on 存在非 healthy 条件")
    web_depends = services.get("web", {}).get("depends_on") or {}
    if not isinstance(web_depends, dict) or (web_depends.get("server") or {}).get("condition") != "service_healthy":
        failures.append("web: server 依赖未使用 service_healthy")

    ports = services.get("web", {}).get("ports") or []
    if not any((port.get("target") == 8080 if isinstance(port, dict) else str(port).endswith(":8080")) for port in ports):
        failures.append("web: 对外端口未映射至非特权 8080")
    return failures


def _inspect_failures(service: str, inspected: dict[str, Any]) -> list[str]:
    failures: list[str] = []
    config = inspected.get("Config") or {}
    host = inspected.get("HostConfig") or {}
    health = (inspected.get("State") or {}).get("Health") or {}
    user = str(config.get("User", "")).split(":", 1)[0].lower()
    if not user or user in {"0", "root"}:
        failures.append(f"{service}: runtime User 为空或为 root")
    if "ALL" not in [str(value).upper() for value in host.get("CapDrop", [])]:
        failures.append(f"{service}: runtime CapDrop 未包含 ALL")
    if not _has_no_new_privileges(host.get("SecurityOpt")):
        failures.append(f"{service}: runtime 未启用 no-new-privileges")
    if host.get("ReadonlyRootfs") is not True:
        failures.append(f"{service}: runtime rootfs 非只读")
    tmpfs = host.get("Tmpfs") or {}
    if "/tmp" not in tmpfs:
        failures.append(f"{service}: runtime 缺少 /tmp tmpfs")
    if service == "web" and "/var/cache/nginx" not in tmpfs:
        failures.append("web: runtime 缺少 nginx cache tmpfs")
    if not (host.get("CpuQuota") or host.get("NanoCpus")) or not host.get("Memory") or not host.get("PidsLimit"):
        failures.append(f"{service}: runtime 资源上限不完整")
    log_config = host.get("LogConfig") or {}
    log_options = log_config.get("Config") or {}
    if log_config.get("Type") != "json-file" or log_options.get("max-size") != "10m" or log_options.get("max-file") != "3":
        failures.append(f"{service}: runtime 日志轮转配置不完整")
    if health.get("Status") != "healthy":
        failures.append(f"{service}: runtime health 状态不是 healthy")
    return failures


def _compose_base(project_name: str) -> list[str]:
    return ["docker", "compose", "--project-name", project_name, "--file", str(COMPOSE_FILE), "--profile", "app"]


def _write_evidence(path: Path, *, passed: bool, failures: list[str], services: dict[str, Any], docker_available: bool) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "version": "P7D-01.v1",
        "passed": passed,
        "docker_available": docker_available,
        "summary": {
            "total": len(APP_SERVICES),
            "passed": len(APP_SERVICES) if passed else 0,
            "failed": 0 if passed else len(failures),
            "failed_cases": failures,
        },
        "services": services,
        "failures": failures,
    }
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--project-name", default=os.environ.get("COMPOSE_PROJECT_NAME", "ragforge-p1"))
    args = parser.parse_args()
    failures: list[str] = []
    services: dict[str, Any] = {}
    docker_available = True
    try:
        rendered = _run(_compose_base(args.project_name) + ["config", "--format", "json"])
        if rendered.returncode != 0:
            raise RuntimeError(f"Compose config 失败：{rendered.stderr.strip()[-500:]}")
        model = json.loads(rendered.stdout)
        failures.extend(validate_model(model))
        for service in APP_SERVICES:
            container = _run(_compose_base(args.project_name) + ["ps", "-q", service])
            container_id = container.stdout.strip().splitlines()[0] if container.returncode == 0 and container.stdout.strip() else ""
            if not container_id:
                failures.append(f"{service}: 找不到运行中的容器")
                continue
            inspected = _run(["docker", "inspect", container_id])
            if inspected.returncode != 0:
                failures.append(f"{service}: docker inspect 失败")
                continue
            details = json.loads(inspected.stdout)[0]
            services[service] = {
                "container_id": container_id,
                "health": (details.get("State") or {}).get("Health", {}).get("Status"),
                "runtime": {
                    "user": (details.get("Config") or {}).get("User"),
                    "cap_drop": (details.get("HostConfig") or {}).get("CapDrop"),
                    "security_opt": (details.get("HostConfig") or {}).get("SecurityOpt"),
                    "read_only": (details.get("HostConfig") or {}).get("ReadonlyRootfs"),
                    "tmpfs": list(((details.get("HostConfig") or {}).get("Tmpfs") or {}).keys()),
                    "cpu_quota": (details.get("HostConfig") or {}).get("CpuQuota"),
                    "nano_cpus": (details.get("HostConfig") or {}).get("NanoCpus"),
                    "memory": (details.get("HostConfig") or {}).get("Memory"),
                    "pids_limit": (details.get("HostConfig") or {}).get("PidsLimit"),
                    "stop_timeout": (details.get("HostConfig") or {}).get("StopTimeout") or details.get("StopTimeout"),
                    "log_config": (details.get("HostConfig") or {}).get("LogConfig"),
                    "health_log": ((details.get("State") or {}).get("Health") or {}).get("Log", [])[-3:],
                },
            }
            failures.extend(_inspect_failures(service, details))
    except (RuntimeError, json.JSONDecodeError, IndexError, KeyError, TypeError) as exc:
        docker_available = False if "找不到必需命令" in str(exc) else docker_available
        failures.append(str(exc))

    passed = not failures and docker_available and len(services) == len(APP_SERVICES)
    _write_evidence(args.evidence, passed=passed, failures=failures, services=services, docker_available=docker_available)
    if passed:
        print("容器加固验收通过：Compose 策略、runtime inspect 与健康状态均符合要求。")
        return 0
    print("容器加固验收失败（fail closed）：" + "；".join(failures[:8]), file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
