#!/usr/bin/env python3
"""Probe the Phase 1 core dependencies and fail with actionable diagnostics."""

from __future__ import annotations

import argparse
import base64
import json
import os
import socket
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass


@dataclass(frozen=True)
class Result:
    name: str
    target: str
    ok: bool
    detail: str


def env_int(name: str, default: int) -> int:
    raw = os.environ.get(name, str(default))
    try:
        value = int(raw)
    except ValueError as exc:
        raise ValueError(f"{name} 必须是整数，收到 {raw!r}") from exc
    if not 1 <= value <= 65535:
        raise ValueError(f"{name} 必须在 1..65535 范围内，收到 {value}")
    return value


def tcp_probe(name: str, host: str, port: int, timeout: float) -> Result:
    target = f"{host}:{port}"
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return Result(name, target, True, "TCP connection accepted")
    except OSError as exc:
        return Result(name, target, False, f"TCP connection failed: {exc}")


def http_probe(
    name: str,
    url: str,
    timeout: float,
    *,
    username: str | None = None,
    password: str | None = None,
) -> Result:
    request = urllib.request.Request(url, headers={"User-Agent": "RAGForge-health-probe/1"})
    if username is not None:
        token = base64.b64encode(f"{username}:{password or ''}".encode()).decode()
        request.add_header("Authorization", f"Basic {token}")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            status = response.status
            if 200 <= status < 300:
                return Result(name, url, True, f"HTTP {status}")
            return Result(name, url, False, f"unexpected HTTP {status}")
    except urllib.error.HTTPError as exc:
        return Result(name, url, False, f"HTTP {exc.code}")
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        return Result(name, url, False, f"HTTP request failed: {exc}")


def valkey_probe(host: str, port: int, password: str, timeout: float) -> Result:
    target = f"{host}:{port}"

    def resp_command(sock: socket.socket, *parts: str) -> bytes:
        payload = f"*{len(parts)}\r\n".encode()
        for part in parts:
            encoded = part.encode()
            payload += f"${len(encoded)}\r\n".encode() + encoded + b"\r\n"
        sock.sendall(payload)
        return sock.recv(1024)

    try:
        with socket.create_connection((host, port), timeout=timeout) as sock:
            if password:
                auth = resp_command(sock, "AUTH", password)
                if not auth.startswith(b"+OK"):
                    return Result("valkey", target, False, f"AUTH failed: {auth[:120]!r}")
            response = resp_command(sock, "PING")
            if response.startswith(b"+PONG"):
                return Result("valkey", target, True, "RESP PONG")
            return Result("valkey", target, False, f"unexpected RESP response: {response[:120]!r}")
    except OSError as exc:
        return Result("valkey", target, False, f"RESP connection failed: {exc}")


def host_probe_url(base_url: str) -> str:
    explicit = os.environ.get("OLLAMA_HEALTH_URL")
    if explicit:
        return explicit.rstrip("/") + "/api/tags"
    parsed = urllib.parse.urlparse(base_url)
    host = "localhost" if parsed.hostname == "host.docker.internal" else parsed.hostname
    netloc = host or "localhost"
    if parsed.port:
        netloc += f":{parsed.port}"
    return urllib.parse.urlunparse((parsed.scheme or "http", netloc, "/api/tags", "", "", ""))


def build_results(timeout: float) -> list[Result]:
    rabbit_user = os.environ.get("RABBITMQ_DEFAULT_USER", "ragforge")
    rabbit_password = os.environ.get("RABBITMQ_DEFAULT_PASS", "change-me")
    ollama_base = os.environ.get("OLLAMA_BASE_URL", "http://host.docker.internal:11434")
    return [
        tcp_probe("postgres", "localhost", env_int("POSTGRES_PORT", 25432), timeout),
        http_probe("qdrant", f"http://localhost:{env_int('QDRANT_PORT', 26333)}/readyz", timeout),
        http_probe(
            "rabbitmq",
            f"http://localhost:{env_int('RABBITMQ_MANAGEMENT_PORT', 25673)}/api/health/checks/alarms",
            timeout,
            username=rabbit_user,
            password=rabbit_password,
        ),
        valkey_probe("localhost", env_int("VALKEY_PORT", 26379), os.environ.get("VALKEY_PASSWORD", "change-me"), timeout),
        http_probe("minio", f"http://localhost:{env_int('S3_PORT', 29000)}/minio/health/ready", timeout),
        http_probe("ollama", host_probe_url(ollama_base), timeout),
    ]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--timeout", type=float, default=3.0, help="每个 probe 的超时时间（秒）")
    parser.add_argument("--retries", type=int, default=10, help="启动窗口内的检查次数")
    parser.add_argument("--retry-delay", type=float, default=2.0, help="检查失败后的等待秒数")
    args = parser.parse_args()
    if args.timeout <= 0:
        parser.error("--timeout 必须大于 0")
    if args.retries < 1:
        parser.error("--retries 必须至少为 1")
    if args.retry_delay < 0:
        parser.error("--retry-delay 不能为负数")
    try:
        results = []
        for attempt in range(1, args.retries + 1):
            results = build_results(args.timeout)
            failures = [result for result in results if not result.ok]
            if not failures:
                print(json.dumps([result.__dict__ for result in results], ensure_ascii=False, indent=2))
                print("健康检查通过：所有 core 依赖可达。")
                return 0
            if attempt < args.retries:
                print(
                    f"健康检查第 {attempt}/{args.retries} 次未通过："
                    + ", ".join(result.name for result in failures)
                    + f"；{args.retry_delay:g}s 后重试。",
                    file=sys.stderr,
                )
                time.sleep(args.retry_delay)
    except ValueError as exc:
        print(f"配置错误：{exc}", file=sys.stderr)
        return 2
    print(json.dumps([result.__dict__ for result in results], ensure_ascii=False, indent=2))
    failures = [result for result in results if not result.ok]
    if failures:
        print("健康检查失败：" + ", ".join(result.name for result in failures), file=sys.stderr)
        return 1
    print("健康检查通过：所有 core 依赖可达。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
