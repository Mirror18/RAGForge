import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts" / "ci"))

import rag_evaluation_gate as gate  # noqa: E402


class RagEvaluationGateTest(unittest.TestCase):
    def test_rag_path_detection_uses_path_boundaries_and_case_insensitive_words(self):
        self.assertTrue(gate.is_rag_path("apps/server/RetrievalEngine.java"))
        self.assertTrue(gate.is_rag_path("apps/web/prompts/AnswerPanel.vue"))
        self.assertFalse(gate.is_rag_path("docs/notretrieval/overview.md"))
        self.assertFalse(gate.is_rag_path("docs/reporter/overview.md"))

    def test_unmatched_changes_are_skipped_with_zero_exit(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            output = Path(temp_dir) / "phase7-evaluation-skip.v1.json"
            with patch.object(gate, "changed_paths", return_value=["docs/README.md"]), patch.object(gate, "_run_evaluation") as evaluate:
                self.assertEqual(gate.run_gate("base", "head", output), 0)
            evaluate.assert_not_called()
            report = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(report["status"], "skipped")
            self.assertFalse(report["triggered"])

    def test_evaluation_failure_blocks_and_is_recorded(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            output = Path(temp_dir) / "phase7-evaluation-failure.v1.json"
            with patch.object(gate, "changed_paths", return_value=["apps/server/retrieval/service.py"]), patch.object(
                gate, "_run_evaluation", return_value=(1, "threshold failure")
            ):
                self.assertNotEqual(gate.run_gate("base", "head", output), 0)
            report = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(report["status"], "failed")
            self.assertFalse(report["passed"])
            self.assertEqual(report["gate"]["runner_returncode"], 1)

    def test_synthetic_rag_change_invokes_phase6_and_preserves_report_metadata(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            output = Path(temp_dir) / "phase7-evaluation-synthetic.v1.json"
            with patch.object(gate, "changed_paths", return_value=["tests/fixtures/EmbeddingChange.py"]), patch.object(
                gate, "_run_evaluation", wraps=gate._run_evaluation
            ) as evaluate:
                self.assertEqual(gate.run_gate("base", "head", output), 0)
            evaluate.assert_called_once_with(output)
            report = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(report["status"], "passed")
            self.assertTrue(report["passed"])
            self.assertEqual(report["dataset_case_count"], 128)
            self.assertEqual(report["gate"]["expected_case_count"], 128)
            self.assertTrue(report["gate"]["config_sha256"])


if __name__ == "__main__":
    unittest.main()
