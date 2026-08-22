import json
import unittest
from unittest.mock import patch

from ollama_stream_metrics import _assert_local_only, measure_stream


class FakeResponse:
    status = 200

    def __init__(self, chunks):
        self.chunks = iter(chunks)

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    def readline(self):
        value = next(self.chunks, None)
        return b"" if value is None else (json.dumps(value).encode("utf-8") + b"\n")


class OllamaStreamMetricsTest(unittest.TestCase):
    def test_loopback_stream_records_ttft_usage_and_does_not_return_text(self):
        response = FakeResponse([
            {"message": {"role": "assistant", "content": "Citation "}, "done": False},
            {"message": {"role": "assistant", "content": "supports evidence."}, "done": False},
            {"done": True, "prompt_eval_count": 11, "eval_count": 4, "total_duration": 2_000_000_000, "prompt_eval_duration": 200_000_000, "eval_duration": 800_000_000},
        ])
        with patch("urllib.request.urlopen", return_value=response):
            result = measure_stream("http://127.0.0.1:11434/api/chat", "test-model", "synthetic", 1)

        self.assertGreaterEqual(result["ttft_ms"], 0)
        self.assertEqual(result["usage"]["total_tokens"], 15)
        self.assertEqual(result["tokens_per_second"], 5.0)
        self.assertEqual(result["output"]["char_count"], len("Citation supports evidence."))
        self.assertNotIn("Citation supports evidence.", json.dumps(result))

    def test_non_loopback_endpoint_is_rejected_before_network(self):
        with self.assertRaises(ValueError):
            _assert_local_only("https://api.example.test/api/chat", "LOCAL_ONLY")

    def test_cloud_route_is_rejected_even_for_loopback_endpoint(self):
        with self.assertRaises(ValueError):
            _assert_local_only("http://127.0.0.1:11434/api/chat", "CLOUD_OPT_IN")


if __name__ == "__main__":
    unittest.main()
