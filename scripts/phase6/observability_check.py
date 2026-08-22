#!/usr/bin/env python3
"""Validate the Phase 6 observability assets without starting containers."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
OVERLAY = ROOT / "deploy" / "compose" / "observability.yaml"
OBS_DIR = ROOT / "deploy" / "compose" / "observability"
DASHBOARD = OBS_DIR / "grafana" / "dashboards" / "ragforge-phase6-oncall.json"
ALERTS = OBS_DIR / "prometheus-rules.yml"
OTEL = OBS_DIR / "otel-collector.yaml"
RUNBOOK_DIR = ROOT / "docs" / "05-operations" / "runbooks"

REQUIRED_FILES = (
    OVERLAY,
    OBS_DIR / "prometheus.yml",
    ALERTS,
    OTEL,
    OBS_DIR / "loki.yaml",
    OBS_DIR / "tempo.yaml",
    OBS_DIR / "grafana" / "provisioning" / "datasources" / "datasources.yaml",
    OBS_DIR / "grafana" / "provisioning" / "dashboards" / "dashboards.yaml",
    DASHBOARD,
)
REQUIRED_SERVICES = {"otel-collector", "prometheus", "grafana", "loki", "tempo"}
REQUIRED_PANEL_TITLES = {
    "登录 5xx 错误率",
    "问答错误率与拒答",
    "Retrieval p50/p95",
    "Generation latency 与 Provider timeout",
    "SSE first event 与断连",
    "队列深度、最老消息与 DLQ",
    "摄取失败、重试与 active index",
    "数据库、对象存储与 Qdrant 容量",
    "最近备份时间与恢复点年龄",
}
REQUIRED_ALERTS = {
    "RAGForgeLoginErrorRateHigh": "provider-outage.md",
    "RAGForgeAnswerErrorRateHigh": "provider-outage.md",
    "RAGForgeActiveIndexUnavailable": "ingestion-backlog.md",
    "RAGForgeUnauthorizedEgress": "unauthorized-egress.md",
    "RAGForgeProviderTimeoutRateHigh": "provider-outage.md",
    "RAGForgeQueueAgeHigh": "ingestion-backlog.md",
    "RAGForgeDeadLetterQueueNotEmpty": "ingestion-backlog.md",
    "RAGForgeDatabaseCapacityHigh": "database-capacity.md",
    "RAGForgeRecoveryPointStale": "database-capacity.md",
}
FORBIDDEN_SECRET_PATTERNS = (
    re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
    re.compile(r"\bgh[pousr]_[A-Za-z0-9]{20,}\b"),
    re.compile(r"\bsk-[A-Za-z0-9]{20,}\b"),
)


def fail(message: str) -> None:
    raise ValueError(message)


def read(path: Path) -> str:
    if not path.is_file():
        fail(f"missing required asset: {path.relative_to(ROOT)}")
    return path.read_text(encoding="utf-8")


def git_sha() -> str:
    result = subprocess.run(
        ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True, capture_output=True, check=False
    )
    return result.stdout.strip() if result.returncode == 0 else "UNKNOWN"


def validate() -> dict[str, object]:
    texts = {path: read(path) for path in REQUIRED_FILES}
    for path, content in texts.items():
        for pattern in FORBIDDEN_SECRET_PATTERNS:
            if pattern.search(content):
                fail(f"secret-like value found in {path.relative_to(ROOT)}")

    overlay = texts[OVERLAY]
    for service in REQUIRED_SERVICES:
        if f"  {service}:" not in overlay:
            fail(f"observability overlay missing service: {service}")
    if "profiles:\n      - observability" not in overlay:
        fail("observability services must be behind the observability profile")
    if "GRAFANA_ADMIN_PASSWORD:?" not in overlay:
        fail("Grafana password must be an explicit external environment value")

    dashboard = json.loads(texts[DASHBOARD])
    panel_titles = {
        panel.get("title")
        for panel in dashboard.get("panels", [])
        if isinstance(panel, dict) and panel.get("title")
    }
    missing_panels = sorted(REQUIRED_PANEL_TITLES - panel_titles)
    if missing_panels:
        fail(f"dashboard missing panels: {missing_panels}")
    if dashboard.get("uid") != "ragforge-phase6-oncall":
        fail("dashboard UID is not stable")
    for forbidden_label in ("user_id", "document_id", "prompt", "Authorization", "Cookie"):
        if forbidden_label in texts[DASHBOARD]:
            fail(f"dashboard contains forbidden high-risk field: {forbidden_label}")

    alerts_text = texts[ALERTS]
    for alert, runbook in REQUIRED_ALERTS.items():
        if f"alert: {alert}" not in alerts_text:
            fail(f"missing alert: {alert}")
        if f"runbook: docs/05-operations/runbooks/{runbook}" not in alerts_text:
            fail(f"alert {alert} is not linked to {runbook}")
    if alerts_text.count("severity: P1") < 5:
        fail("P1 alert coverage is unexpectedly small")

    otel_text = texts[OTEL]
    for safe_key in (
        "http.request.header.authorization",
        "http.request.header.cookie",
        "http.request.body",
        "http.response.body",
        "db.statement",
        "gen_ai.prompt",
        "gen_ai.completion",
        "document.content",
        "set(body, \"\")",
    ):
        if safe_key not in otel_text:
            fail(f"OTel safe projection missing deletion: {safe_key}")
    for identity_key in ("trace_id", "correlation_id", "run_id", "space_id"):
        if identity_key not in otel_text:
            fail(f"OTel identity projection missing: {identity_key}")

    runbooks = {
        path.name: read(path)
        for path in sorted(RUNBOOK_DIR.glob("*.md"))
        if path.name in {f for f in ("provider-outage.md", "ingestion-backlog.md", "unauthorized-egress.md", "database-capacity.md")}
    }
    required_headings = (
        "1. 症状与用户影响",
        "2. 安全边界和禁止动作",
        "3. Dashboard、查询和只读诊断",
        "4. 缓解步骤",
        "5. 恢复与验证",
        "6. 回滚",
        "7. 升级联系人/SLA",
        "8. 证据和复盘记录位置",
    )
    for name, content in runbooks.items():
        for heading in required_headings:
            if heading not in content:
                fail(f"runbook {name} missing fixed heading: {heading}")
    if set(runbooks) != {"provider-outage.md", "ingestion-backlog.md", "unauthorized-egress.md", "database-capacity.md"}:
        fail("required Phase 6 runbook set is incomplete")

    return {
        "status": "PASSED",
        "verification_scope": "static_asset_only",
        "runtime_dashboard_verified": False,
        "code_commit": git_sha(),
        "checked_at": datetime.now(timezone.utc).isoformat(),
        "profile": "observability",
        "services": sorted(REQUIRED_SERVICES),
        "dashboard_uid": dashboard["uid"],
        "dashboard_panels": sorted(REQUIRED_PANEL_TITLES),
        "alerts": sorted(REQUIRED_ALERTS),
        "runbooks": sorted(runbooks),
        "secret_scan": {"private_key": 0, "cloud_key_pattern": 0, "raw_customer_content": 0},
        "note": "Compose and runtime dashboard verification require the separate observability_drill.py command.",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path)
    args = parser.parse_args()
    try:
        result = validate()
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"Observability asset check failed: {exc}", file=sys.stderr)
        return 1
    if args.evidence:
        target = args.evidence if args.evidence.is_absolute() else ROOT / args.evidence
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        f"Observability asset check passed: {len(result['services'])} services, "
        f"{len(result['alerts'])} alerts, {len(result['runbooks'])} runbooks; static-only scope."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
