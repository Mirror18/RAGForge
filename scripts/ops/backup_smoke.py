#!/usr/bin/env python3
"""Create a local PostgreSQL backup smoke artifact without exposing secrets."""

from __future__ import annotations

import argparse
import hashlib
import os
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "dev"))
from compose_isolation import isolated_environment


REPO_ROOT = Path(__file__).resolve().parents[2]
COMPOSE_FILE = REPO_ROOT / "deploy" / "compose" / "compose.yaml"


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-name", default=os.environ.get("RAGFORGE_COMPOSE_PROJECT", "ragforge-p1"))
    parser.add_argument("--env-file", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--dry-run", action="store_true")
    return parser


def main() -> int:
    args = build_parser().parse_args()
    if not COMPOSE_FILE.is_file():
        print(f"错误：Compose 文件不存在：{COMPOSE_FILE}", file=sys.stderr)
        return 2
    compose = ["docker", "compose", "--project-name", args.project_name]
    if args.env_file:
        compose.extend(["--env-file", str(args.env_file.resolve())])
    compose.extend(["--file", str(COMPOSE_FILE)])
    dump = compose + [
        "exec",
        "--no-TTY",
        "postgres",
        "pg_dump",
        "--username",
        os.environ.get("POSTGRES_USER", "ragforge"),
        "--dbname",
        os.environ.get("POSTGRES_DB", "ragforge"),
        "--no-owner",
        "--no-privileges",
    ]
    if args.dry_run:
        print("备份 smoke dry-run：将执行 docker compose exec postgres pg_dump（凭据不在命令行输出）。")
        return 0

    output = args.output or (
        REPO_ROOT
        / "tmp"
        / "backups"
        / f"postgres-{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}.sql"
    )
    output = output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    try:
        environment = isolated_environment(args.project_name, args.env_file)
    except ValueError as exc:
        print(f"配置错误：{exc}", file=sys.stderr)
        return 2
    try:
        result = subprocess.run(
            dump,
            cwd=REPO_ROOT,
            env=environment,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
    except FileNotFoundError as exc:
        print(f"错误：找不到可执行文件 {exc.filename!r}。请先启动 Docker。", file=sys.stderr)
        return 127
    if result.returncode != 0:
        detail = result.stderr.decode("utf-8", errors="replace").strip()
        print(f"备份 smoke 失败：pg_dump 退出码 {result.returncode}。{detail}", file=sys.stderr)
        return 1
    if len(result.stdout) < 32:
        print("备份 smoke 失败：pg_dump 输出为空或异常过短。", file=sys.stderr)
        return 1
    output.write_bytes(result.stdout)
    digest = hashlib.sha256(result.stdout).hexdigest()
    print(f"备份 smoke 通过：{output} ({len(result.stdout)} bytes, sha256={digest})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
