"""Phase 2 deterministic 20-chain cloud protocol concurrency evidence."""

from __future__ import annotations

import json
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any

try:
    from tests.integration.test_phase2_cloud_protocol import (
        FULL_PROMPT,
        TEST_SECRET,
        MockBehavior,
        MockOpenAIProvider,
        ProviderCallError,
        RequestIdentity,
        call_openai_compatible,
        make_chain_identity,
    )
except ModuleNotFoundError:
    from integration.test_phase2_cloud_protocol import (
        FULL_PROMPT,
        TEST_SECRET,
        MockBehavior,
        MockOpenAIProvider,
        ProviderCallError,
        RequestIdentity,
        call_openai_compatible,
        make_chain_identity,
    )


ROOT = Path(__file__).resolve().parents[2]
EVIDENCE_PATH = ROOT / "tests" / "evidence" / "phase2-cloud-concurrency.json"
CHAIN_COUNT = 20
SUCCESS_INDICES = frozenset((*range(12), 18, 19))
BEHAVIOR_PLAN = {
    **{index: MockBehavior.success() for index in SUCCESS_INDICES},
    12: MockBehavior.http(401, {"error": {"message": "bad auth"}}),
    13: MockBehavior.http(404, {"error": {"code": "model_not_found"}}),
    14: MockBehavior.http(429, {"error": {"code": "rate_limit"}}),
    15: MockBehavior.http(500, {"error": {"message": "upstream unavailable"}}),
    16: MockBehavior.timeout(delay_seconds=0.75),
    17: MockBehavior.invalid_response(),
}


def usage_dedupe_key(identity: RequestIdentity, index: int) -> str:
    return f"{identity.space_id}|{identity.invocation_id}|PROVIDER_REPORTED|response-{index}"


class Phase2CloudConcurrencyTest(unittest.TestCase):
    def setUp(self) -> None:
        self.provider = MockOpenAIProvider()
        self.provider_thread = threading.Thread(target=self.provider.serve_forever, daemon=True)
        self.provider_thread.start()

    def tearDown(self) -> None:
        self.provider.shutdown()
        self.provider.server_close()
        self.provider_thread.join(timeout=2)

    def test_twenty_concurrent_chains_preserve_identity_and_classification(self) -> None:
        identities = {index: make_chain_identity(index) for index in range(CHAIN_COUNT)}
        for index, identity in identities.items():
            self.provider.set_behavior(identity.request_id, BEHAVIOR_PLAN[index])

        start_gate = threading.Barrier(CHAIN_COUNT)
        usage_counts: dict[str, int] = {}
        usage_lock = threading.Lock()

        def record_usage(key: str) -> None:
            with usage_lock:
                usage_counts[key] = usage_counts.get(key, 0) + 1

        def run_chain(index: int) -> dict[str, Any]:
            identity = identities[index]
            start_gate.wait(timeout=5)
            try:
                response = call_openai_compatible(
                    self.provider,
                    identity,
                    full_prompt=f"{FULL_PROMPT};chain={index}",
                    secret=f"{TEST_SECRET};chain={index}",
                    timeout_seconds=0.4 if index == 16 else 2.0,
                )
                dedupe_key = usage_dedupe_key(identity, index)
                # Simulate a retry/provider replay: both reports must charge one key.
                record_usage(dedupe_key)
                record_usage(dedupe_key)
                return {
                    "index": index,
                    "request_id": identity.request_id,
                    "correlation_id": identity.correlation_id,
                    "space_id": identity.space_id,
                    "invocation_id": identity.invocation_id,
                    "outcome": "success",
                    "status_code": 200,
                    "error_class": None,
                    "usage_dedupe_key": dedupe_key,
                    "response_id": response.response_id,
                }
            except ProviderCallError as error:
                return {
                    "index": index,
                    "request_id": identity.request_id,
                    "correlation_id": identity.correlation_id,
                    "space_id": identity.space_id,
                    "invocation_id": identity.invocation_id,
                    "outcome": "failure",
                    "status_code": error.status_code,
                    "error_class": error.error_class,
                    "usage_dedupe_key": None,
                    "response_id": None,
                }

        with ThreadPoolExecutor(max_workers=CHAIN_COUNT, thread_name_prefix="phase2-cloud") as executor:
            results = sorted(executor.map(run_chain, range(CHAIN_COUNT)), key=lambda item: item["index"])

        self.assertEqual(len(results), CHAIN_COUNT)
        self.assertEqual({item["index"] for item in results}, set(range(CHAIN_COUNT)))
        self.assertEqual(sum(item["outcome"] == "success" for item in results), 14)
        self.assertEqual(sum(item["outcome"] == "failure" for item in results), 6)
        self.assertEqual(
            {item["error_class"] for item in results if item["outcome"] == "failure"},
            {"AUTHENTICATION", "MODEL_NOT_FOUND", "RATE_LIMIT", "UNAVAILABLE", "TIMEOUT", "INVALID_RESPONSE"},
        )

        request_ids = {item["request_id"] for item in results}
        correlation_ids = {item["correlation_id"] for item in results}
        space_ids = {item["space_id"] for item in results}
        self.assertEqual(len(request_ids), CHAIN_COUNT)
        self.assertEqual(len(correlation_ids), CHAIN_COUNT)
        self.assertEqual(len(space_ids), CHAIN_COUNT)
        self.assertEqual(len(usage_counts), 14)
        self.assertTrue(all(count == 2 for count in usage_counts.values()))

        records = self.provider.records()
        self.assertEqual(len(records), CHAIN_COUNT)
        records_by_request = {
            record["headers"]["x-ragforge-request-id"]: record for record in records
        }
        self.assertEqual(set(records_by_request), request_ids)
        for index, identity in identities.items():
            record = records_by_request[identity.request_id]
            headers = record["headers"]
            self.assertEqual(headers["x-ragforge-correlation-id"], identity.correlation_id)
            self.assertEqual(headers["x-ragforge-space-id"], identity.space_id)
            self.assertEqual(headers["idempotency-key"], f"invoke-{identity.invocation_id}")
            self.assertNotIn(f"{TEST_SECRET};chain={index}", record["body"])
            self.assertNotIn(f"{FULL_PROMPT};chain={index}", record["body"])

        self._assert_static_evidence_fixture(results, usage_counts)

    def _assert_static_evidence_fixture(
        self, results: list[dict[str, Any]], usage_counts: dict[str, int]
    ) -> None:
        evidence = json.loads(EVIDENCE_PATH.read_text(encoding="utf-8"))
        self.assertEqual(evidence["schema_version"], "phase2-cloud-concurrency.v1")
        self.assertEqual(evidence["environment"]["provider"], "ThreadingHTTPServer")
        self.assertEqual(evidence["environment"]["network"], "loopback-only")
        self.assertFalse(evidence["environment"]["external_network"])
        summary = evidence["concurrency"]
        self.assertEqual(summary["chain_count"], CHAIN_COUNT)
        self.assertEqual(summary["success_count"], 14)
        self.assertEqual(summary["failure_count"], 6)
        self.assertTrue(summary["all_request_ids_unique"])
        self.assertTrue(summary["all_correlation_ids_unique"])
        self.assertTrue(summary["all_space_ids_unique"])
        self.assertTrue(summary["request_bodies_exclude_secret_and_full_prompt"])
        self.assertTrue(summary["no_silent_failover_observed"])
        self.assertEqual(summary["classification_counts"], {
            "AUTHENTICATION": 1,
            "MODEL_NOT_FOUND": 1,
            "RATE_LIMIT": 1,
            "UNAVAILABLE": 1,
            "TIMEOUT": 1,
            "INVALID_RESPONSE": 1,
        })
        dedupe = evidence["usage_dedupe"]
        self.assertEqual(dedupe["reported_success_chains"], 14)
        self.assertEqual(dedupe["provider_reports_received"], 28)
        self.assertEqual(dedupe["unique_ledger_keys"], 14)
        self.assertEqual(dedupe["duplicate_reports_suppressed"], 14)
        self.assertTrue(dedupe["all_keys_unique"])
        self.assertTrue(dedupe["duplicate_charge_prevented"])
        self.assertEqual(len(evidence["chains"]), CHAIN_COUNT)

        expected_results = {item["index"]: item for item in results}
        for chain in evidence["chains"]:
            actual = expected_results[chain["index"]]
            for field in (
                "request_id",
                "correlation_id",
                "space_id",
                "invocation_id",
                "outcome",
                "status_code",
                "error_class",
                "usage_dedupe_key",
            ):
                self.assertEqual(chain[field], actual[field], field)
            self.assertNotIn(TEST_SECRET, json.dumps(chain))
            self.assertNotIn(FULL_PROMPT, json.dumps(chain))


if __name__ == "__main__":
    unittest.main()
