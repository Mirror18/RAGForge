import unittest

import retrieval_benchmark


class Phase4RetrievalBenchmarkTest(unittest.TestCase):
    def test_quality_benchmark_is_above_phase_thresholds_and_has_no_forbidden_leak(self):
        result = retrieval_benchmark.quality_benchmark()
        self.assertEqual(result["case_count"], 30)
        self.assertGreaterEqual(result["recall_at_10"], 0.90)
        self.assertGreaterEqual(result["mrr_at_10"], 0.75)
        self.assertEqual(result["forbidden_source_leaks"], 0)

    def test_scale_reference_is_space_filtered_and_recall_safe(self):
        result = retrieval_benchmark.scale_benchmark(child_count=10_000, query_count=20)
        self.assertEqual(result["child_count"], 10_000)
        self.assertEqual(result["recall_at_10"], 1.0)
        self.assertLess(result["p95_ms"], 1_500)


if __name__ == "__main__":
    unittest.main()
