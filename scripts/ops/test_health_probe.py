from __future__ import annotations

import sys
import unittest
from pathlib import Path
from unittest.mock import patch


OPS_DIR = Path(__file__).resolve().parent
if str(OPS_DIR) not in sys.path:
    sys.path.insert(0, str(OPS_DIR))

import health_probe  # noqa: E402


def successful_result(name: str, *_args, **_kwargs) -> health_probe.Result:
    return health_probe.Result(name, "test", True, "ok")


class HealthProbeTests(unittest.TestCase):
    @patch.object(health_probe, "valkey_probe", return_value=health_probe.Result("valkey", "test", True, "ok"))
    @patch.object(health_probe, "http_probe", side_effect=successful_result)
    @patch.object(health_probe, "tcp_probe", side_effect=successful_result)
    def test_ollama_is_not_required_by_default(self, _tcp_probe, _http_probe, _valkey_probe):
        results = health_probe.build_results(0.1)

        self.assertEqual(
            [result.name for result in results],
            ["postgres", "qdrant", "rabbitmq", "valkey", "minio"],
        )

    @patch.object(health_probe, "valkey_probe", return_value=health_probe.Result("valkey", "test", True, "ok"))
    @patch.object(health_probe, "http_probe", side_effect=successful_result)
    @patch.object(health_probe, "tcp_probe", side_effect=successful_result)
    def test_ollama_can_be_checked_explicitly(self, _tcp_probe, http_probe, _valkey_probe):
        results = health_probe.build_results(0.1, check_ollama=True)

        self.assertEqual(
            [result.name for result in results],
            ["postgres", "qdrant", "rabbitmq", "valkey", "minio", "ollama"],
        )
        self.assertEqual(http_probe.call_count, 4)


if __name__ == "__main__":
    unittest.main()
