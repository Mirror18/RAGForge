from __future__ import annotations

import importlib.util
import json
import sys
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT_PATH = REPO_ROOT / "scripts" / "ci" / "contract_coverage.py"
SPEC = importlib.util.spec_from_file_location("contract_coverage", SCRIPT_PATH)
assert SPEC and SPEC.loader
coverage = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = coverage
SPEC.loader.exec_module(coverage)


class ContractCoverageTest(unittest.TestCase):
    def test_current_contract_and_controllers_are_bidirectionally_covered(self) -> None:
        document = json.loads((REPO_ROOT / "contracts/openapi/ragforge-api-v1.yaml").read_text(encoding="utf-8"))
        result = coverage.evaluate(document, coverage.load_controller_sources(REPO_ROOT))
        self.assertTrue(result["passed"], result)
        self.assertEqual([], result["contract"]["missing_controller"])
        self.assertEqual([], result["implementation"]["missing_contract"])

    def test_deleted_controller_mapping_is_a_failure(self) -> None:
        document = {
            "paths": {
                "/api/v1/fixture": {
                    "get": {"operationId": "getFixture"},
                }
            }
        }
        sources = {"FixtureController.java": """
            @RestController
            @RequestMapping("/api/v1")
            class FixtureController {
                @PostMapping("/other")
                public String other() { return "ok"; }
            }
        """}
        result = coverage.evaluate(document, sources)
        self.assertFalse(result["passed"])
        self.assertEqual("getFixture", result["contract"]["missing_controller"][0]["operation_id"])
        self.assertEqual("/api/v1/fixture", result["contract"]["missing_controller"][0]["path"])

    def test_deleted_contract_operation_is_a_failure(self) -> None:
        document = {"paths": {}}
        sources = {"FixtureController.java": """
            @RestController
            @RequestMapping("/api/v1")
            class FixtureController {
                @GetMapping("/fixture")
                public String fixture() { return "ok"; }
            }
        """}
        result = coverage.evaluate(document, sources)
        self.assertFalse(result["passed"])
        self.assertEqual("GET", result["implementation"]["missing_contract"][0]["method"].upper())
        self.assertEqual("/api/v1/fixture", result["implementation"]["missing_contract"][0]["path"])
        self.assertEqual("FixtureController.java", result["implementation"]["missing_contract"][0]["controller"])


if __name__ == "__main__":
    unittest.main()
