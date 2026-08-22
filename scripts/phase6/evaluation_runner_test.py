import copy
import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts" / "phase6"))

from evaluation_runner import (  # noqa: E402
    CATEGORY_COUNTS,
    DEFAULT_CONFIG,
    DEFAULT_DATASET,
    DEFAULT_SEED,
    build_promptfoo_adapter,
    evaluate,
    fixture_result,
    generate_dataset,
    load_config,
    validate_dataset,
    validate_result,
)


class Phase6EvaluationRunnerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.dataset = json.loads(DEFAULT_DATASET.read_text(encoding="utf-8"))
        cls.config = load_config(DEFAULT_CONFIG)

    def test_committed_dataset_has_frozen_distribution_and_required_fields(self):
        self.assertEqual(validate_dataset(self.dataset), [])
        self.assertGreaterEqual(len(self.dataset["cases"]), 120)
        counts = {category: sum(case["category"] == category for case in self.dataset["cases"]) for category in CATEGORY_COUNTS}
        self.assertEqual(counts, CATEGORY_COUNTS)
        for case in self.dataset["cases"]:
            for field in ("expected_claims", "required_citations", "forbidden_citations", "answerability", "manual_review"):
                self.assertIn(field, case)
            self.assertTrue(case["manual_review"]["required"])

    def test_generation_is_reproducible_for_same_seed(self):
        self.assertEqual(generate_dataset(DEFAULT_SEED), generate_dataset(DEFAULT_SEED))

    def test_validator_rejects_required_forbidden_overlap(self):
        invalid = copy.deepcopy(self.dataset)
        invalid["cases"][0]["forbidden_citations"].append(invalid["cases"][0]["required_citations"][0])
        errors = validate_dataset(invalid)
        self.assertTrue(any("overlap" in error for error in errors))

    def test_candidate_passes_and_baseline_is_lower_with_slice_evidence(self):
        candidate = fixture_result(self.dataset, "candidate-fixture-v1")
        baseline = fixture_result(self.dataset, "baseline-fixture-v1")
        self.assertEqual(validate_result(self.dataset, candidate), [])
        self.assertEqual(validate_result(self.dataset, baseline), [])
        candidate_metrics = evaluate(self.dataset, candidate, self.config["seed"], self.config["bootstrap_samples"])
        baseline_metrics = evaluate(self.dataset, baseline, self.config["seed"], self.config["bootstrap_samples"])
        self.assertGreaterEqual(candidate_metrics["retrieval"]["recall_at_10"]["value"], 0.90)
        self.assertGreaterEqual(candidate_metrics["retrieval"]["mrr_at_10"]["value"], 0.75)
        self.assertGreaterEqual(candidate_metrics["generation"]["citation_precision"]["value"], 0.90)
        self.assertGreaterEqual(candidate_metrics["generation"]["claim_faithfulness"]["value"], 0.90)
        self.assertGreaterEqual(candidate_metrics["generation"]["abstention_accuracy"]["value"], 0.90)
        self.assertLess(baseline_metrics["generation"]["abstention_accuracy"]["value"], candidate_metrics["generation"]["abstention_accuracy"]["value"])
        self.assertEqual(set(candidate_metrics["slices"]), set(CATEGORY_COUNTS))
        self.assertEqual(candidate_metrics["security"]["cross_space_leakage"], 0)
        self.assertEqual(candidate_metrics["security"]["evidence_outside_bundle"], 0)
        self.assertIn("ci95", candidate_metrics["retrieval"]["mrr_at_10"])

    def test_security_violation_is_counted_for_external_result(self):
        candidate = fixture_result(self.dataset, "candidate-fixture-v1")
        candidate["cases"][0]["retrieved_evidence"].append("ev-foreign-space-test")
        self.assertEqual(validate_result(self.dataset, candidate), [])
        metrics = evaluate(self.dataset, candidate, self.config["seed"], self.config["bootstrap_samples"])
        self.assertGreater(metrics["security"]["evidence_outside_bundle"], 0)

    def test_untrusted_document_tool_use_is_counted(self):
        candidate = fixture_result(self.dataset, "candidate-fixture-v1")
        injection = next(item for item in candidate["cases"] if item["case_id"].startswith("p6-promp-"))
        injection["tool_calls"] = [{"name": "web.fetch"}]
        metrics = evaluate(self.dataset, candidate, self.config["seed"], self.config["bootstrap_samples"])
        self.assertEqual(metrics["security"]["prompt_injection_tool_violations"], 1)

    def test_promptfoo_adapter_is_optional_and_keeps_ragforge_as_authority(self):
        adapter = build_promptfoo_adapter(self.dataset, self.config)
        self.assertEqual(adapter["status"], "optional-not-run")
        self.assertFalse(adapter["tool_dependency_added"])
        self.assertEqual(adapter["core_truth_source"], "RAGForge Evaluation Run report; this adapter cannot establish or replace quality history")
        self.assertEqual(len(adapter["tests"]), len(self.dataset["cases"]))


if __name__ == "__main__":
    unittest.main()
