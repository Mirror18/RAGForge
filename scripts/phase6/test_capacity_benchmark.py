import importlib.util
import json
import unittest
from pathlib import Path
from unittest.mock import patch


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

    def test_upload_retries_same_idempotent_batch(self) -> None:
        calls = []

        def qdrant_put(*args, **kwargs):
            calls.append((args, kwargs))
            if len(calls) == 1:
                raise MODULE.urllib.error.URLError("simulated connection timeout")
            return {}

        with patch.object(MODULE, "qdrant_json", side_effect=qdrant_put), patch.object(MODULE, "resource_diagnostics", return_value={}), patch.object(MODULE.time, "sleep"):
            result = MODULE.upload("http://127.0.0.1:26347", 768, 2, 2, 1, 5)

        self.assertEqual(result["batch_count"], 1)
        self.assertEqual(result["retry_count"], 1)
        self.assertEqual(len(calls), 2)
        self.assertEqual(calls[0][0], calls[1][0])
        self.assertEqual(calls[0][1], calls[1][1])


if __name__ == "__main__":
    unittest.main()
