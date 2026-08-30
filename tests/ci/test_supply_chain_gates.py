from __future__ import annotations

import importlib.util
import re
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[2] / "scripts" / "ci" / "secret_audit.py"
SPEC = importlib.util.spec_from_file_location("secret_audit", SCRIPT)
assert SPEC and SPEC.loader
AUDIT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(AUDIT)

ROOT = Path(__file__).resolve().parents[2]
COMPOSE = ROOT / "deploy" / "compose" / "compose.yaml"
DOCKERFILE = ROOT / "deploy" / "docker" / "Dockerfile"


class SupplyChainGateTests(unittest.TestCase):
    def test_rendered_compose_allows_only_declared_synthetic_secrets(self) -> None:
        model = {"services": {"server": {"environment": {"POSTGRES_PASSWORD": AUDIT.SYNTHETIC_ENV["POSTGRES_PASSWORD"]}}}}
        self.assertEqual(AUDIT.scan_rendered_compose(model), [])

    def test_rendered_compose_rejects_placeholder_secret(self) -> None:
        model = {"services": {"server": {"environment": {"POSTGRES_PASSWORD": "change-me"}}}}
        self.assertIn("placeholder secret", AUDIT.scan_rendered_compose(model)[0])

    def test_rendered_compose_rejects_unresolved_interpolation(self) -> None:
        model = {"services": {"server": {"environment": {"POSTGRES_PASSWORD": "${POSTGRES_PASSWORD}"}}}}
        self.assertIn("unresolved interpolation", AUDIT.scan_rendered_compose(model)[0])

    def test_image_scan_rejects_embedded_secret(self) -> None:
        image = {"Id": "sha256:" + "a" * 64, "Config": {"Env": ["POSTGRES_PASSWORD=embedded-value"]}}
        findings = AUDIT.scan_image_inspect("example@sha256:" + "a" * 64, image)
        self.assertTrue(any("embedded in image" in finding for finding in findings))

    def test_image_scan_requires_immutable_id(self) -> None:
        image = {"Id": "example:local", "Config": {"Env": []}}
        findings = AUDIT.scan_image_inspect("example:local", image)
        self.assertTrue(any("immutable image ID" in finding for finding in findings))

    def test_compose_infrastructure_images_are_digest_pinned(self) -> None:
        image_lines = [line.strip() for line in COMPOSE.read_text(encoding="utf-8").splitlines() if line.strip().startswith("image:")]
        infrastructure = [line for line in image_lines if "RAGFORGE_" not in line]
        self.assertEqual(len(infrastructure), 6)
        for line in infrastructure:
            self.assertRegex(line, r"@sha256:[0-9a-f]{64}")

    def test_dockerfile_external_images_are_digest_pinned(self) -> None:
        external = [line for line in DOCKERFILE.read_text(encoding="utf-8").splitlines() if line.startswith("FROM ") and " AS " in line]
        external = [line for line in external if not re.match(r"FROM [a-z0-9._-]+ AS ", line)]
        for line in external:
            self.assertRegex(line, r"@[a-z0-9_-]+:[0-9a-f]{64}")
