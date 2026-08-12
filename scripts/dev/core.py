#!/usr/bin/env python3
"""统一管理 RAGForge Phase 1 core Compose 的本地入口。"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
from pathlib import Path

from compose_isolation import isolated_environment


REPO_ROOT = Path(__file__).resolve().parents[2]
COMPOSE_FILE = REPO_ROOT / "deploy" / "compose" / "compose.yaml"
HEALTH_PROBE = REPO_ROOT / "scripts" / "ops" / "health_probe.py"
BACKUP_SMOKE = REPO_ROOT / "scripts" / "ops" / "backup_smoke.py"
DEFAULT_PROJECT = "ragforge-p1"


def compose_command(args: argparse.Namespace) -> list[str]:
    command = ["docker", "compose"]
    if args.project_name:
        command.extend(["--project-name", args.project_name])
    if args.env_file:
        command.extend(["--env-file", str(args.env_file.resolve())])
    command.extend(["--file", str(COMPOSE_FILE)])
    for profile in args.profile:
        command.extend(["--profile", profile])
    return command


def run(command: list[str], environment: dict[str, str] | None = None) -> int:
    print("$ " + " ".join(command), flush=True)
    try:
        result = subprocess.run(command, cwd=REPO_ROOT, env=environment, check=False)
    except FileNotFoundError as exc:
        print(f"错误：找不到可执行文件 {exc.filename!r}。请先安装 Docker Desktop/Engine。", file=sys.stderr)
        return 127
    return result.returncode


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--project-name",
        default=os.environ.get("RAGFORGE_COMPOSE_PROJECT", DEFAULT_PROJECT),
        help=f"独立 Compose project name（默认：{DEFAULT_PROJECT}）",
    )
    parser.add_argument("--env-file", type=Path, help="可选的 Compose 环境文件")
    parser.add_argument("--profile", action="append", default=[], help="启用 Compose profile，可重复指定")
    subparsers = parser.add_subparsers(dest="command", required=True)
    subparsers.add_parser("config", help="渲染并校验 Compose 配置")
    subparsers.add_parser("ps", help="查看 core 容器状态")
    up = subparsers.add_parser("up", help="启动 core 容器")
    up.add_argument("--foreground", action="store_true", help="前台运行，不使用 -d")
    down = subparsers.add_parser("down", help="停止 core 容器并保留数据卷")
    down.add_argument("--volumes", action="store_true", help="显式删除本项目数据卷")
    subparsers.add_parser("health", help="执行基础设施健康探针")
    backup = subparsers.add_parser("backup-smoke", help="执行 PostgreSQL 备份 smoke")
    backup.add_argument("--dry-run", action="store_true", help="只打印脱敏后的执行计划")
    backup.add_argument("--output", type=Path, help="备份输出路径，默认 tmp/backups 下的时间戳文件")
    return parser


def main() -> int:
    args = build_parser().parse_args()
    if not COMPOSE_FILE.is_file():
        print(f"错误：Compose 文件不存在：{COMPOSE_FILE}", file=sys.stderr)
        return 2

    try:
        environment = isolated_environment(args.project_name, args.env_file)
    except ValueError as exc:
        print(f"配置错误：{exc}", file=sys.stderr)
        return 2

    if args.command == "config":
        return run(compose_command(args) + ["config"], environment)
    if args.command == "ps":
        return run(compose_command(args) + ["ps"], environment)
    if args.command == "up":
        command = compose_command(args) + ["up"]
        if not args.foreground:
            command.append("--detach")
        return run(command, environment)
    if args.command == "down":
        command = compose_command(args) + ["down", "--remove-orphans"]
        if args.volumes:
            command.append("--volumes")
        return run(command, environment)
    if args.command == "health":
        return run([sys.executable, str(HEALTH_PROBE)], environment)
    if args.command == "backup-smoke":
        command = [sys.executable, str(BACKUP_SMOKE), "--project-name", args.project_name]
        if args.env_file:
            command.extend(["--env-file", str(args.env_file.resolve())])
        if args.dry_run:
            command.append("--dry-run")
        if args.output:
            command.extend(["--output", str(args.output.resolve())])
        return run(command, environment)
    print(f"错误：未处理的命令 {args.command!r}", file=sys.stderr)
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
