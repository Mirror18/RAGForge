import importlib.util
import json
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("capacity_benchmark.py")
SPEC = importlib.util.spec_from_file_location("capacity_benchmark", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(MODULE)


class CapacityBenchmarkTest(unittest.TestCase):
    def test_vector_has_live_dimension_and_is_not_eight_dimensional(self) -> None:
        vector = MODULE.vector_for(17, 768)
        self.assertEqual(len(vector), 768)
        self.assertNotEqual(len(vector), 8)
        self.assertAlmostEqual(sum(value * value for value in vector), 1.0, places=5)

    def test_percentiles_are_deterministic(self) -> None:
        self.assertEqual(MODULE.percentile([4.0, 1.0, 3.0, 2.0], .5), 2.0)
        self.assertEqual(MODULE.percentile([], .95), None)

    def test_blocked_online_result_is_explicit(self) -> None:
        result = MODULE.blocked_online_result(None)
        self.assertEqual(result["status"], "BLOCKED")
        self.assertEqual(result["reason"], "server_url_not_provided")
        self.assertIn("online_latency_probe.py", result["repro_command"])


if __name__ == "__main__":
    unittest.main()
