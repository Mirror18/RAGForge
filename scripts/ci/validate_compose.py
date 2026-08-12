#!/usr/bin/env python3
"""Validate the core Compose file and its required service inventory."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "dev"))
from compose_isolation import PORT_MAX, PORT_MIN, isolated_environment, port_offset, project_ports


REPO_ROOT = Path(__file__).resolve().parents[2]
COMPOSE_FILE = REPO_ROOT / "deploy" / "compose" / "compose.yaml"
REQUIRED_SERVICES = {"postgres", "qdrant", "rabbitmq", "valkey", "minio"}


def compose_base(project_name: str, env_file: Path | None = None, profile: str | None = None) -> list[str]:
    command = ["docker", "compose", "--project-name", project_name, "--file", str(COMPOSE_FILE)]
    if env_file:
        command[4:4] = ["--env-file", str(env_file.resolve())]
    if profile:
        command.extend(["--profile", profile])
    return command


def run(command: list[str], environment: dict[str, str]) -> subprocess.CompletedProcess[str]:
    try:
        return subprocess.run(command, cwd=REPO_ROOT, env=environment, text=True, capture_output=True, check=False)
    except FileNotFoundError as exc:
        print(f"Compose 校验失败：找不到 {exc.filename!r}，请安装 Docker Compose。", file=sys.stderr)
        raise SystemExit(127) from exc


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-name", default="ragforge-p1")
    parser.add_argument("--env-file", type=Path)
    args = parser.parse_args()
    if not COMPOSE_FILE.is_file():
        print(f"Compose 校验失败：文件不存在：{COMPOSE_FILE}", file=sys.stderr)
        return 2

    try:
        expected_ports = project_ports(args.project_name)
        environment = isolated_environment(args.project_name, args.env_file)
    except ValueError as exc:
        print(f"Compose 校验失败：隔离配置无效：{exc}", file=sys.stderr)
        return 2
    quiet = run(compose_base(args.project_name, args.env_file) + ["config", "--quiet"], environment)
    if quiet.returncode != 0:
        print("Compose 校验失败：config --quiet 未通过。", file=sys.stderr)
        print(quiet.stderr.strip(), file=sys.stderr)
        return quiet.returncode or 1

    services = run(compose_base(args.project_name, args.env_file) + ["config", "--services"], environment)
    if services.returncode != 0:
        print("Compose 校验失败：无法读取服务清单。", file=sys.stderr)
        print(services.stderr.strip(), file=sys.stderr)
        return services.returncode or 1
    actual = {line.strip() for line in services.stdout.splitlines() if line.strip()}
    missing = sorted(REQUIRED_SERVICES - actual)
    if missing:
        print(f"Compose 校验失败：缺少必需服务：{', '.join(missing)}", file=sys.stderr)
        return 1

    with_ollama = run(compose_base(args.project_name, args.env_file, "ollama") + ["config", "--services"], environment)
    if with_ollama.returncode != 0 or "ollama" not in with_ollama.stdout.split():
        print("Compose 校验失败：ollama profile 未暴露 ollama 服务。", file=sys.stderr)
        print(with_ollama.stderr.strip(), file=sys.stderr)
        return 1

    source = COMPOSE_FILE.read_text(encoding="utf-8")
    if "gs-" in source or "gs_" in source:
        print("Compose 校验失败：core Compose 不得引用 gs-* 项目或资源。", file=sys.stderr)
        return 1
    required_tokens = ("OLLAMA_BASE_URL", "healthcheck:", "networks:", "volumes:")
    missing_tokens = [token for token in required_tokens if token not in source]
    if missing_tokens:
        print(f"Compose 校验失败：缺少连接/健康/隔离声明：{missing_tokens}", file=sys.stderr)
        return 1
    rendered = run(compose_base(args.project_name, args.env_file) + ["config", "--format", "json"], environment)
    if rendered.returncode != 0:
        print("Compose 校验失败：无法读取渲染后的隔离资源清单。", file=sys.stderr)
        print(rendered.stderr.strip(), file=sys.stderr)
        return rendered.returncode or 1
    try:
        model = json.loads(rendered.stdout)
        network_name = model["networks"]["core"]["name"]
        volume_names = [volume["name"] for volume in model["volumes"].values()]
    except (KeyError, TypeError, json.JSONDecodeError) as exc:
        print(f"Compose 校验失败：无法解析网络/volume 清单：{exc}", file=sys.stderr)
        return 1
    expected_network = f"{args.project_name}-core"
    expected_prefix = f"{args.project_name}_"
    if network_name != expected_network or not volume_names or any(not name.startswith(expected_prefix) for name in volume_names):
        print(
            "Compose 校验失败：project isolation mismatch; "
            f"expected network={expected_network!r}, volume prefix={expected_prefix!r}; "
            f"actual network={network_name!r}, volumes={volume_names!r}",
            file=sys.stderr,
        )
        return 1
    service_ports = {
        port["published"]
        for service in model["services"].values()
        for port in service.get("ports", [])
        if "published" in port
    }
    actual_ports = {int(port) for port in service_ports}
    expected_port_values = {port for name, port in expected_ports.items() if name != "OLLAMA_PORT"}
    if actual_ports != expected_port_values or any(port < PORT_MIN or port >= PORT_MAX for port in actual_ports):
        print(
            "Compose 校验失败：host port isolation mismatch; "
            f"expected={sorted(expected_port_values)!r}, actual={sorted(actual_ports)!r}, "
            f"safe_range=[{PORT_MIN}, {PORT_MAX})",
            file=sys.stderr,
        )
        return 1
    profile_rendered = run(
        compose_base(args.project_name, args.env_file, "ollama") + ["config", "--format", "json"],
        environment,
    )
    if profile_rendered.returncode != 0:
        print("Compose 校验失败：无法读取 ollama profile 端口清单。", file=sys.stderr)
        print(profile_rendered.stderr.strip(), file=sys.stderr)
        return profile_rendered.returncode or 1
    try:
        profile_model = json.loads(profile_rendered.stdout)
        profile_ports = {
            int(port["published"])
            for service in profile_model["services"].values()
            for port in service.get("ports", [])
            if "published" in port
        }
    except (KeyError, TypeError, ValueError, json.JSONDecodeError) as exc:
        print(f"Compose 校验失败：无法解析 ollama profile 端口清单：{exc}", file=sys.stderr)
        return 1
    if profile_ports != set(expected_ports.values()):
        print(
            "Compose 校验失败：ollama profile host port mismatch; "
            f"expected={sorted(expected_ports.values())!r}, actual={sorted(profile_ports)!r}",
            file=sys.stderr,
        )
        return 1
    print(
        f"Compose 校验通过：{len(actual)} 个默认服务，ollama profile 可用；"
        f"隔离资源 network={network_name}，volume_prefix={expected_prefix}，"
        f"port_offset={port_offset(args.project_name)}。"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
