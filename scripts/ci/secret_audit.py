#!/usr/bin/env python3
"""Fail-closed audit for rendered Compose configuration and built image metadata."""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[2]
COMPOSE_FILE = REPO_ROOT / "deploy" / "compose" / "compose.yaml"
SENSITIVE_KEY = re.compile(r"(?i)(password|passwd|secret|api[_-]?key|access[_-]?key|token|private[_-]?key)")
SECRET_VALUE = re.compile(
    r"-----BEGIN [A-Z ]+PRIVATE KEY-----|\bAKIA[0-9A-Z]{16}\b|"
    r"\b(?:gh[pousr]|github_pat)_[A-Za-z0-9_]{20,}\b|\bxox[baprs]-[A-Za-z0-9-]{20,}\b|"
    r"\b(?:sk|rk)-[A-Za-z0-9]{20,}\b"
)
PLACEHOLDERS = {"change-me", "change-me-minio-secret", "placeholder", "example", "test", "none", "null"}
SYNTHETIC_VALUES = {
    "p7d02-local-postgres-password",
    "p7d02-local-qdrant-api-key",
    "p7d02-local-rabbitmq-password",
    "p7d02-local-valkey-password",
    "p7d02-local-s3-secret",
}
SYNTHETIC_ENV = {
    "POSTGRES_PASSWORD": "p7d02-local-postgres-password",
    "QDRANT_API_KEY": "p7d02-local-qdrant-api-key",
    "RABBITMQ_DEFAULT_PASS": "p7d02-local-rabbitmq-password",
    "VALKEY_PASSWORD": "p7d02-local-valkey-password",
    "S3_SECRET_KEY": "p7d02-local-s3-secret",
}


def _strings(value: Any, path: str = "") -> list[tuple[str, str]]:
    if isinstance(value, dict):
        return [(child_path, child_value) for key, child in value.items() for child_path, child_value in _strings(child, f"{path}.{key}")]
    if isinstance(value, list):
        return [(child_path, child_value) for index, child in enumerate(value) for child_path, child_value in _strings(child, f"{path}[{index}]")]
    if isinstance(value, str):
        return [(path, value)]
    return []


def scan_rendered_compose(model: dict[str, Any]) -> list[str]:
    findings: list[str] = []
    for path, value in _strings(model):
        key = path.rsplit(".", 1)[-1].split("[", 1)[0]
        lower = value.lower()
        if "${" in value and ".environment." in path:
            findings.append(f"compose:{path}: unresolved interpolation")
        if SENSITIVE_KEY.search(key):
            if not value or lower in PLACEHOLDERS:
                findings.append(f"compose:{path}: empty or placeholder secret")
            elif value not in SYNTHETIC_VALUES and SECRET_VALUE.search(value):
                findings.append(f"compose:{path}: credential-shaped secret")
    return findings


def scan_image_inspect(image_ref: str, image: dict[str, Any]) -> list[str]:
    findings: list[str] = []
    image_id = image.get("Id", "")
    if not isinstance(image_id, str) or not re.fullmatch(r"sha256:[0-9a-f]{64}", image_id):
        findings.append(f"image:{image_ref}: missing immutable image ID")
    config = image.get("Config")
    if not isinstance(config, dict):
        return findings + [f"image:{image_ref}: missing image Config metadata"]
    for key in ("Env", "Cmd", "Entrypoint", "Labels", "WorkingDir"):
        values = config.get(key, [])
        for path, value in _strings(values, f"Config.{key}"):
            if SECRET_VALUE.search(value):
                findings.append(f"image:{image_ref}:{path}: credential-shaped value")
            if key == "Env" and "=" in value:
                env_key, env_value = value.split("=", 1)
                if SENSITIVE_KEY.search(env_key) and env_value:
                    findings.append(f"image:{image_ref}:Config.Env.{env_key}: secret embedded in image")
    return findings


def _run(command: list[str], environment: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
    try:
        return subprocess.run(command, cwd=REPO_ROOT, env=environment, text=True, capture_output=True, check=False)
    except FileNotFoundError as exc:
        return subprocess.CompletedProcess(command, 127, "", f"missing executable: {exc.filename}")


def _synthetic_environment() -> dict[str, str]:
    environment = os.environ.copy()
    environment.update(SYNTHETIC_ENV)
    environment["COMPOSE_PROJECT_NAME"] = "ragforge-p7d02-secret-audit"
    return environment


def audit(compose_file: Path, images: list[str], profile: str) -> dict[str, Any]:
    findings: list[str] = []
    compose_command = ["docker", "compose", "--project-name", "ragforge-p7d02-secret-audit", "--file", str(compose_file), "--profile", profile, "config", "--format", "json"]
    rendered = _run(compose_command, _synthetic_environment())
    if rendered.returncode != 0:
        findings.append("compose: unable to render expanded configuration")
    else:
        try:
            model = json.loads(rendered.stdout)
        except json.JSONDecodeError:
            findings.append("compose: rendered configuration is not valid JSON")
        else:
            findings.extend(scan_rendered_compose(model))

    for image_ref in images:
        inspected = _run(["docker", "image", "inspect", image_ref])
        if inspected.returncode != 0:
            findings.append(f"image:{image_ref}: inspect failed")
            continue
        try:
            image_model = json.loads(inspected.stdout)[0]
        except (IndexError, json.JSONDecodeError, TypeError):
            findings.append(f"image:{image_ref}: inspect returned invalid JSON")
            continue
        findings.extend(scan_image_inspect(image_ref, image_model))
    return {"status": "passed" if not findings else "failed", "compose_file": str(compose_file), "images": images, "findings": findings}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compose-file", type=Path, default=COMPOSE_FILE)
    parser.add_argument("--profile", default="app")
    parser.add_argument("--image", action="append", dest="images", required=True)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    report = audit(args.compose_file.resolve(), args.images, args.profile)
    if args.output:
        args.output.resolve().write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["status"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
