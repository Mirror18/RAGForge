from __future__ import annotations

import importlib.util
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[2] / "scripts" / "ci" / "container_hardening_check.py"
SPEC = importlib.util.spec_from_file_location("container_hardening_check", SCRIPT)
assert SPEC and SPEC.loader
CHECK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECK)


def _model() -> dict:
    hardening = {
        "cap_drop": ["ALL"],
        "security_opt": ["no-new-privileges:true"],
        "read_only": True,
        "tmpfs": ["/tmp:rw,noexec,nosuid,nodev,size=64m"],
        "cpus": 1.0,
        "mem_limit": "768m",
        "pids_limit": 256,
        "stop_grace_period": "30s",
        "logging": {"driver": "json-file", "options": {"max-size": "10m", "max-file": "3"}},
    }
    services = {
        name: {**hardening, "healthcheck": {"test": ["CMD-SHELL", "true"]}, "depends_on": {"postgres": {"condition": "service_healthy"}}}
        for name in ("server", "worker")
    }
    services["server"]["healthcheck"] = {"test": ["CMD-SHELL", "curl /actuator/health"]}
    services["worker"]["healthcheck"] = {"test": ["CMD-SHELL", "kill -0 1"]}
    services["web"] = {
        **hardening,
        "tmpfs": [*hardening["tmpfs"], "/var/cache/nginx:rw"],
        "healthcheck": {"test": ["CMD-SHELL", "wget http://127.0.0.1:8080/"]},
        "depends_on": {"server": {"condition": "service_healthy"}},
        "ports": ["25174:8080"],
    }
    return {"services": services}


def test_validate_model_accepts_complete_hardening_policy() -> None:
    assert CHECK.validate_model(_model()) == []


def test_validate_model_fails_closed_when_read_only_is_removed() -> None:
    model = _model()
    model["services"]["server"].pop("read_only")
    failures = CHECK.validate_model(model)
    assert "server: read_only 未启用" in failures


def test_validate_model_requires_healthy_dependency_condition() -> None:
    model = _model()
    model["services"]["web"]["depends_on"]["server"]["condition"] = "service_started"
    failures = CHECK.validate_model(model)
    assert "web: server 依赖未使用 service_healthy" in failures


def test_runtime_check_fails_closed_for_root_and_unhealthy_container() -> None:
    failures = CHECK._inspect_failures(
        "server",
        {
            "Config": {"User": "0"},
            "HostConfig": {},
            "State": {"Health": {"Status": "starting"}},
        },
    )
    assert any("runtime User" in failure for failure in failures)
    assert any("runtime health" in failure for failure in failures)
