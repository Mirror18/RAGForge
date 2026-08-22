#!/usr/bin/env python3
"""Unit tests for the Phase 6 observability asset contract."""

from __future__ import annotations

import json
import unittest
from pathlib import Path

import observability_check


class ObservabilityAssetTest(unittest.TestCase):
    def test_static_assets_are_complete_and_safe(self) -> None:
        result = observability_check.validate()
        self.assertEqual(result["status"], "PASSED")
        self.assertFalse(result["runtime_dashboard_verified"])
        self.assertEqual(len(result["services"]), 5)
        self.assertGreaterEqual(len(result["alerts"]), 9)
        self.assertEqual(len(result["runbooks"]), 4)

    def test_dashboard_is_valid_json_with_stable_uid(self) -> None:
        dashboard_path = observability_check.DASHBOARD
        dashboard = json.loads(dashboard_path.read_text(encoding="utf-8"))
        self.assertEqual(dashboard["uid"], "ragforge-phase6-oncall")
        self.assertGreaterEqual(len(dashboard["panels"]), 10)

    def test_required_files_stay_inside_owned_observability_scope(self) -> None:
        for path in observability_check.REQUIRED_FILES:
            self.assertTrue(path.is_relative_to(observability_check.ROOT))
            self.assertTrue(
                path.is_relative_to(observability_check.ROOT / "deploy" / "compose")
                or path.is_relative_to(observability_check.ROOT / "docs" / "05-operations")
            )


if __name__ == "__main__":
    unittest.main()
