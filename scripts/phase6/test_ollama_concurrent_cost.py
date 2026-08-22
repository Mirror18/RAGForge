import json
import unittest
from unittest.mock import patch

from ollama_concurrent_cost import _assert_local_only, run_concurrent


class OllamaConcurrentCostTest(unittest.TestCase):
    def test_concurrent_report_aggregates_usage_without_raw_text(self):
        def fake_measure(_endpoint, _model, prompt, _timeout, *, route):
            return {
                "ttft_ms": 10.0,
                "stream_wall_time_ms": 20.0,
                "provider_total_duration_ms": 19.0,
                "tokens_per_second": 5.0,
                "usage": {"input_tokens": 3, "output_tokens": 2, "total_tokens": 5,
                          "provider_usage_source": "PROVIDER_REPORTED"},
                "output": {"char_count": 4, "sha256": "a" * 64},
            }

        with patch("ollama_concurrent_cost.measure_stream", side_effect=fake_measure):
            result = run_concurrent("http://127.0.0.1:11434/api/chat", "test-model", "LOCAL_ONLY",
                                    concurrency=2, requests=4, timeout=1)

        self.assertEqual(result["status"], "PASSED")
        self.assertEqual(result["completed_call_count"], 4)
        self.assertEqual(result["usage"]["total_tokens"], 20)
        self.assertEqual(result["provider_call_count"], 4)
        self.assertGreaterEqual(result["max_in_flight_observed"], 1)
        self.assertNotIn("Public synthetic concurrent benchmark case", json.dumps(result))

    def test_non_local_route_is_rejected(self):
        with self.assertRaises(ValueError):
            _assert_local_only("http://127.0.0.1:11434/api/chat", "CLOUD_OPT_IN")

    def test_invalid_load_is_rejected(self):
        with self.assertRaises(ValueError):
            run_concurrent("http://127.0.0.1:11434/api/chat", "test-model", "LOCAL_ONLY",
                           concurrency=0, requests=1, timeout=1)


if __name__ == "__main__":
    unittest.main()
