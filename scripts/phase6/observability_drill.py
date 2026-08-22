#!/usr/bin/env python3
"""Run a real-profile observability fault drill against local endpoints.

The drill injects only a synthetic OTLP counter. It fails unless Prometheus,
Grafana, Loki, Tempo and the Collector are actually reachable and the
provisioned dashboard can be read from Grafana.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
DASHBOARD_UID = "ragforge-phase6-oncall"


class DrillError(RuntimeError):
    pass


def request_json(url: str, *, method: str = "GET", payload: dict | None = None, headers: dict[str, str] | None = None) -> dict:
    body = None
    request_headers = {"Accept": "application/json", **(headers or {})}
    if payload is not None:
        body = json.dumps(payload).encode("utf-8")
        request_headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=body, headers=request_headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            raw = response.read().decode("utf-8")
            return json.loads(raw) if raw else {"status": response.status}
    except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, json.JSONDecodeError) as exc:
        raise DrillError(f"request failed for {url}: {exc}") from exc


def request_status(url: str) -> int:
    request = urllib.request.Request(url, headers={"Accept": "text/plain, application/json"})
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            response.read()
            return response.status
    except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError) as exc:
        raise DrillError(f"readiness request failed for {url}: {exc}") from exc


def wait_for(check, description: str, timeout: int = 120):
    deadline = time.monotonic() + timeout
    last_error = "not observed"
    while time.monotonic() < deadline:
        try:
            value = check()
            if value:
                return value
        except DrillError as exc:
            last_error = str(exc)
        time.sleep(3)
    raise DrillError(f"timed out waiting for {description}: {last_error}")


def query(prometheus_url: str, expression: str) -> list[dict]:
    query_url = f"{prometheus_url.rstrip('/')}/api/v1/query?{urllib.parse.urlencode({'query': expression})}"
    result = request_json(query_url)
    if result.get("status") != "success":
        raise DrillError(f"Prometheus query failed: {expression}")
    return result.get("data", {}).get("result", [])


def otlp_counter(metric_name: str, value: int) -> dict:
    now = str(time.time_ns())
    return {
        "resourceMetrics": [
            {
                "resource": {
                    "attributes": [
                        {"key": "service.name", "value": {"stringValue": "ragforge-phase6-drill"}},
                        {"key": "environment", "value": {"stringValue": "local-drill"}},
                    ]
                },
                "scopeMetrics": [
                    {
                        "scope": {"name": "ragforge-phase6-drill"},
                        "metrics": [
                            {
                                "name": metric_name,
                                "sum": {
                                    "dataPoints": [
                                        {
                                            "asInt": str(value),
                                            "timeUnixNano": now,
                                            "isMonotonic": True,
                                        }
                                    ],
                                    "aggregationTemporality": 2,
                                    "isMonotonic": True,
                                },
                            }
                        ],
                    }
                ],
            }
        ]
    }


def send_counter(otel_url: str, value: int) -> None:
    request_json(
        f"{otel_url.rstrip('/')}/v1/metrics",
        method="POST",
        payload=otlp_counter("ragforge_egress_denied_total", value),
    )


def send_otlp(otel_url: str, signal: str, payload: dict) -> None:
    request_json(f"{otel_url.rstrip('/')}/v1/{signal}", method="POST", payload=payload)


def trace_payload(trace_id: str, span_id: str) -> dict:
    now = time.time_ns()
    return {
        "resourceSpans": [
            {
                "resource": {
                    "attributes": [
                        {"key": "service.name", "value": {"stringValue": "ragforge-phase6-drill"}},
                        {"key": "environment", "value": {"stringValue": "local-drill"}},
                    ]
                },
                "scopeSpans": [
                    {
                        "scope": {"name": "ragforge-phase6-drill"},
                        "spans": [
                            {
                                "traceId": trace_id,
                                "spanId": span_id,
                                "name": "phase6-observability-fault-drill",
                                "kind": 1,
                                "startTimeUnixNano": str(now - 1_000_000),
                                "endTimeUnixNano": str(now),
                                "attributes": [
                                    {"key": "correlation.id", "value": {"stringValue": "corr-drill-opaque"}},
                                    {"key": "run.id", "value": {"stringValue": "run-drill-opaque"}},
                                    {"key": "space.id", "value": {"stringValue": "space-drill-opaque"}},
                                    {"key": "authorization", "value": {"stringValue": "SYNTHETIC_AUTHORIZATION_MUST_NOT_EXPORT"}},
                                    {"key": "http.request.body", "value": {"stringValue": "SYNTHETIC_BODY_MUST_NOT_EXPORT"}},
                                ],
                            }
                        ],
                    }
                ],
            }
        ]
    }


def logs_payload() -> dict:
    return {
        "resourceLogs": [
            {
                "resource": {
                    "attributes": [
                        {"key": "service.name", "value": {"stringValue": "ragforge-phase6-drill"}},
                        {"key": "environment", "value": {"stringValue": "local-drill"}},
                    ]
                },
                "scopeLogs": [
                    {
                        "scope": {"name": "ragforge-phase6-drill"},
                        "logRecords": [
                            {
                                "timeUnixNano": str(time.time_ns()),
                                "severityText": "ERROR",
                                "body": {"stringValue": "SYNTHETIC_LOG_BODY_MUST_NOT_EXPORT"},
                                "attributes": [
                                    {"key": "event", "value": {"stringValue": "provider_timeout"}},
                                    {"key": "correlation.id", "value": {"stringValue": "corr-log-opaque"}},
                                    {"key": "run.id", "value": {"stringValue": "run-log-opaque"}},
                                    {"key": "space.id", "value": {"stringValue": "space-log-opaque"}},
                                    {"key": "authorization", "value": {"stringValue": "SYNTHETIC_AUTHORIZATION_MUST_NOT_EXPORT"}},
                                    {"key": "prompt", "value": {"stringValue": "SYNTHETIC_PROMPT_MUST_NOT_EXPORT"}},
                                ],
                            }
                        ],
                    }
                ],
            }
        ]
    }


def response_contains(response: object, forbidden: tuple[str, ...]) -> bool:
    return any(marker in json.dumps(response, ensure_ascii=False) for marker in forbidden)


def query_loki(loki_url: str) -> dict:
    end = int(time.time() * 1_000_000_000)
    start = end - 120 * 1_000_000_000
    query_url = f"{loki_url.rstrip('/')}/loki/api/v1/query_range?{urllib.parse.urlencode({'query': '{service_name=\"ragforge-phase6-drill\"}', 'start': start, 'end': end, 'limit': 100})}"
    return request_json(query_url)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prometheus-url", required=True)
    parser.add_argument("--otel-url", required=True)
    parser.add_argument("--grafana-url", required=True)
    parser.add_argument("--loki-url", required=True)
    parser.add_argument("--tempo-url", required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()
    started = datetime.now(timezone.utc)
    try:
        # All checks are runtime checks. No endpoint is replaced by a static fixture.
        prometheus_ready = wait_for(
            lambda: request_status(f"{args.prometheus_url.rstrip('/')}/-/ready") == 200,
            "Prometheus readiness",
        )
        grafana_health = wait_for(
            lambda: request_json(f"{args.grafana_url.rstrip('/')}/api/health").get("database") == "ok",
            "Grafana health",
        )
        loki_ready = wait_for(
            lambda: request_status(f"{args.loki_url.rstrip('/')}/ready") == 200,
            "Loki readiness",
        )
        tempo_ready = wait_for(
            lambda: request_status(f"{args.tempo_url.rstrip('/')}/status") == 200,
            "Tempo status",
        )
        if not all((prometheus_ready, grafana_health, loki_ready, tempo_ready)):
            raise DrillError("one or more readiness endpoints did not become ready")

        username = os.environ.get("GRAFANA_ADMIN_USER", "admin")
        password = os.environ.get("GRAFANA_ADMIN_PASSWORD")
        if not password:
            raise DrillError("GRAFANA_ADMIN_PASSWORD must be supplied through the environment")
        basic = base64.b64encode(f"{username}:{password}".encode("utf-8")).decode("ascii")
        dashboard = request_json(
            f"{args.grafana_url.rstrip('/')}/api/dashboards/uid/{DASHBOARD_UID}",
            headers={"Authorization": f"Basic {basic}"},
        )
        if dashboard.get("dashboard", {}).get("uid") != DASHBOARD_UID:
            raise DrillError("Grafana returned a different dashboard UID")

        # First sample establishes a cumulative counter; second sample creates a
        # real increase that Prometheus can evaluate against the P1 egress rule.
        send_counter(args.otel_url, 0)
        wait_for(lambda: query(args.prometheus_url, "ragforge_egress_denied_total"), "initial OTLP metric scrape")
        send_counter(args.otel_url, 1)
        observed = wait_for(
            lambda: query(args.prometheus_url, "ragforge_egress_denied_total > 0"),
            "injected egress-denied metric",
        )
        alert = wait_for(
            lambda: query(args.prometheus_url, 'ALERTS{alertname="RAGForgeUnauthorizedEgress",alertstate="firing"}'),
            "RAGForgeUnauthorizedEgress alert",
        )
        if not alert:
            raise DrillError("unauthorized egress alert did not fire")

        trace_id = uuid.uuid4().hex
        span_id = uuid.uuid4().hex[:16]
        send_otlp(args.otel_url, "traces", trace_payload(trace_id, span_id))
        trace = wait_for(
            lambda: request_json(f"{args.tempo_url.rstrip('/')}/api/traces/{trace_id}"),
            "OTLP trace in Tempo",
        )
        if response_contains(trace, ("SYNTHETIC_AUTHORIZATION_MUST_NOT_EXPORT", "SYNTHETIC_BODY_MUST_NOT_EXPORT")):
            raise DrillError("Tempo response contains a redacted trace attribute")

        send_otlp(args.otel_url, "logs", logs_payload())
        logs = wait_for(
            lambda: query_loki(args.loki_url).get("data", {}).get("result"),
            "OTLP log in Loki",
        )
        if response_contains(
            logs,
            (
                "SYNTHETIC_AUTHORIZATION_MUST_NOT_EXPORT",
                "SYNTHETIC_PROMPT_MUST_NOT_EXPORT",
                "SYNTHETIC_LOG_BODY_MUST_NOT_EXPORT",
            ),
        ):
            raise DrillError("Loki response contains a redacted log field")

        result = {
            "status": "PASSED",
            "verification_scope": "runtime_profile_and_fault_drill",
            "started_at": started.isoformat(),
            "completed_at": datetime.now(timezone.utc).isoformat(),
            "project": "ragforge-p6-observability-a1",
            "dashboard": {"uid": DASHBOARD_UID, "runtime_verified": True},
            "endpoints": {
                "prometheus_ready": True,
                "grafana_health": True,
                "loki_ready": True,
                "tempo_ready": True,
                "otel_http_metrics_ingest": True,
                "otel_trace_ingest_and_redaction": True,
                "otel_log_ingest_and_redaction": True,
            },
            "fault": {
                "type": "unauthorized_egress_attempt",
                "injected_metric": "ragforge_egress_denied_total",
                "synthetic_counter_value": 1,
                "observed_prometheus_series": len(observed),
                "observed_alert_series": len(alert),
                "cloud_call_performed": False,
                "raw_request_or_prompt_recorded": False,
                "trace_sensitive_attributes_exported": False,
                "log_sensitive_attributes_exported": False,
            },
            "diagnosis_path": {
                "dashboard": "RAGForge Phase 6 On-call",
                "alert": "RAGForgeUnauthorizedEgress",
                "runbook": "docs/05-operations/runbooks/unauthorized-egress.md",
                "safe_fields": ["trace_id", "correlation_id", "run_id", "space_id", "decision", "route_class", "error_code"],
            },
            "code_commit": subprocess_sha(),
            "note": "The dashboard was read from a running Grafana API; this is not a static asset-only claim.",
        }
    except DrillError as exc:
        print(f"Observability fault drill failed: {exc}", file=sys.stderr)
        return 1
    target = args.evidence if args.evidence.is_absolute() else ROOT / args.evidence
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("Observability fault drill passed: runtime endpoints, provisioned dashboard and egress alert verified.")
    return 0


def subprocess_sha() -> str:
    import subprocess

    result = subprocess.run(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True, capture_output=True, check=False)
    return result.stdout.strip() if result.returncode == 0 else "UNKNOWN"


if __name__ == "__main__":
    raise SystemExit(main())
