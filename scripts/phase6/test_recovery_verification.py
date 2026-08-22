#!/usr/bin/env python3
"""Unit checks for the Phase 6 recovery harness safety and replay rules."""

from __future__ import annotations

import importlib.util
import json
import sys
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("recovery_verification.py")
SPEC = importlib.util.spec_from_file_location("recovery_verification", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
sys.modules["recovery_verification"] = MODULE
SPEC.loader.exec_module(MODULE)


class RecoveryVerificationTest(unittest.TestCase):
    def test_production_looking_project_is_rejected(self) -> None:
        for project in ("ragforge-p1", "ragforge-p6-recovery-production", "ragforge-p6-recovery-main"):
            with self.subTest(project=project):
                with self.assertRaises(MODULE.RecoveryError):
                    MODULE.validate_project_name(project)

    def test_only_local_fixture_is_accepted(self) -> None:
        fixture = json.loads(MODULE.FIXTURE_FILE.read_text(encoding="utf-8"))
        MODULE.validate_fixture(fixture)
        self.assertTrue(fixture["synthetic_only"])
        self.assertEqual(fixture["fixture_version"], "phase6-recovery-fixture.v1")

    def test_fixture_ids_are_deterministic_and_distinct(self) -> None:
        first = MODULE.fixture_ids("run-1")
        second = MODULE.fixture_ids("run-1")
        self.assertEqual(first, second)
        self.assertEqual(len(first), len(set(first.values())))

    def test_sql_helpers_quote_and_hash_without_raw_secret_projection(self) -> None:
        self.assertEqual(MODULE.sql_string("a'b"), "'a''b'")
        self.assertEqual(len(MODULE.sha256_bytes(b"fixture")), 64)
        self.assertNotIn("change-me", MODULE.json_sql({"synthetic": True}))

    def test_migration_manifest_is_ordered_and_current(self) -> None:
        _, manifest = MODULE.migration_sql()
        self.assertEqual(manifest[-1]["file"], MODULE.SCHEMA_VERSION)
        self.assertEqual(len(manifest), 13)


if __name__ == "__main__":
    unittest.main()
