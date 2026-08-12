#!/usr/bin/env python3
"""Deterministic project isolation values shared by local Compose helpers."""

from __future__ import annotations

import hashlib
import os
from pathlib import Path


DEFAULT_PROJECT = "ragforge-p1"
BASE_PORTS = {
    "POSTGRES_PORT": 25432,
    "QDRANT_PORT": 26333,
    "QDRANT_GRPC_PORT": 26334,
    "RABBITMQ_PORT": 25672,
    "RABBITMQ_MANAGEMENT_PORT": 25673,
    "VALKEY_PORT": 26379,
    "S3_PORT": 29000,
    "S3_CONSOLE_PORT": 29001,
    "OLLAMA_PORT": 21434,
}
PORT_MIN = 20_000
PORT_MAX = 50_000
PORT_BLOCK_STEP = 20
PORT_SLOT_COUNT = 997


def port_offset(project_name: str) -> int:
    """Return a stable, bounded offset; no random state or machine state is used."""
    if project_name == DEFAULT_PROJECT:
        return 0
    digest = hashlib.sha256(project_name.encode("utf-8")).digest()
    slot = int.from_bytes(digest[:4], "big") % PORT_SLOT_COUNT
    return slot * PORT_BLOCK_STEP


def project_ports(project_name: str) -> dict[str, int]:
    offset = port_offset(project_name)
    ports = {name: port + offset for name, port in BASE_PORTS.items()}
    if any(port < PORT_MIN or port >= PORT_MAX for port in ports.values()):
        raise ValueError(f"project {project_name!r} maps outside safe port range: {ports}")
    if len(ports.values()) != len(set(ports.values())):
        raise ValueError(f"project {project_name!r} maps duplicate host ports: {ports}")
    return ports


def isolated_environment(project_name: str, env_file: Path | None = None) -> dict[str, str]:
    environment = os.environ.copy()
    if env_file:
        try:
            lines = env_file.resolve().read_text(encoding="utf-8").splitlines()
        except OSError as exc:
            raise ValueError(f"无法读取 env-file {env_file}: {exc}") from exc
        for line in lines:
            stripped = line.strip()
            if not stripped or stripped.startswith("#") or "=" not in stripped:
                continue
            key, value = stripped.split("=", 1)
            environment.setdefault(key.strip(), value.strip().strip("\"'"))

    # Explicit project identity owns all local isolation dimensions. These
    # process values take precedence over Compose --env-file interpolation.
    environment["COMPOSE_PROJECT_NAME"] = project_name
    environment["RAGFORGE_NETWORK_NAME"] = f"{project_name}-core"
    environment["RAGFORGE_VOLUME_PREFIX"] = project_name
    for name, port in project_ports(project_name).items():
        environment[name] = str(port)
    return environment
