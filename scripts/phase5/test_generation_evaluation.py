import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from generation_evaluation import DATASET, score


class GenerationEvaluationTest(unittest.TestCase):
    def test_candidate_meets_all_quality_thresholds_without_hiding_abstention_slice(self):
        dataset = json.loads(DATASET.read_text(encoding="utf-8"))
        result = score(dataset["cases"], "candidate")
        self.assertGreaterEqual(result["citation_precision"], 0.90)
        self.assertGreaterEqual(result["faithfulness"], 0.90)
        self.assertGreaterEqual(result["abstention_accuracy"], 0.90)
        self.assertEqual(result["slices"]["unanswerable"]["cases"], 3)
        self.assertEqual(result["slices"]["conflicting"]["cases"], 3)

    def test_baseline_is_reported_as_lower_than_candidate(self):
        dataset = json.loads(DATASET.read_text(encoding="utf-8"))
        candidate = score(dataset["cases"], "candidate")
        baseline = score(dataset["cases"], "baseline")
        self.assertGreater(candidate["citation_precision"], baseline["citation_precision"])
        self.assertGreater(candidate["faithfulness"], baseline["faithfulness"])
        self.assertGreater(candidate["abstention_accuracy"], baseline["abstention_accuracy"])


if __name__ == "__main__":
    unittest.main()
