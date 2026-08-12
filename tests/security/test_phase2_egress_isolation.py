"""Phase 2 cloud egress isolation and no-silent-failover acceptance tests."""

from __future__ import annotations

import json
import threading
import unittest
from dataclasses import dataclass
from typing import Callable

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


class EgressDeniedError(PermissionError):
    """Raised before any provider call when a route violates space policy."""


class RouteExhaustedError(RuntimeError):
    """Raised when an explicit same-egress route has no successful candidate."""


@dataclass(frozen=True)
class RouteCandidate:
    space_id: str
    provider_name: str
    egress_class: str


class EgressPolicyFixture:
    """Small executable model of the Phase 2 egress boundary."""

    def validate_candidates(
        self, requested_space_id: str, decision: str, candidates: list[RouteCandidate]
    ) -> list[RouteCandidate]:
        if not candidates:
            raise EgressDeniedError("route has no candidates")
        for candidate in candidates:
            if candidate.space_id != requested_space_id:
                raise EgressDeniedError("route candidate belongs to another space")
            if decision == "LOCAL_ONLY" and candidate.egress_class == "CLOUD":
                raise EgressDeniedError("cloud candidate is not authorized for local-only request")
            if candidate.egress_class not in {"LOCAL", "CLOUD"}:
                raise EgressDeniedError("unknown egress class")
        return sorted(candidates, key=lambda candidate: candidate.provider_name)

    def execute_route(
        self,
        requested_space_id: str,
        decision: str,
        candidates: list[RouteCandidate],
        call_candidate: Callable[[RouteCandidate], object],
    ) -> object:
        validated = self.validate_candidates(requested_space_id, decision, candidates)
        last_error: Exception | None = None
        for candidate in validated:
            try:
                return call_candidate(candidate)
            except Exception as error:  # retry only an explicit, same-egress candidate
                last_error = error
        raise RouteExhaustedError("explicit route candidates were exhausted") from last_error


class Phase2CloudEgressIsolationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.provider = MockOpenAIProvider()
        self.provider_thread = threading.Thread(target=self.provider.serve_forever, daemon=True)
        self.provider_thread.start()
        self.policy = EgressPolicyFixture()

    def tearDown(self) -> None:
        self.provider.shutdown()
        self.provider.server_close()
        self.provider_thread.join(timeout=2)

    def test_local_only_rejects_cloud_before_any_provider_request(self) -> None:
        identity = make_chain_identity(30)
        self.provider.set_behavior(identity.request_id, MockBehavior.success())
        candidate = RouteCandidate(identity.space_id, "cloud-primary", "CLOUD")
        calls: list[str] = []

        with self.assertRaises(EgressDeniedError):
            self.policy.execute_route(
                identity.space_id,
                "LOCAL_ONLY",
                [candidate],
                lambda selected: calls.append(selected.provider_name),
            )

        self.assertEqual(calls, [])
        self.assertEqual(self.provider.records(), [])

    def test_cross_space_candidate_is_rejected_before_cloud_request(self) -> None:
        requested_space = make_chain_identity(31)
        other_space = make_chain_identity(32)
        candidate = RouteCandidate(other_space.space_id, "other-space-cloud", "CLOUD")

        with self.assertRaises(EgressDeniedError):
            self.policy.execute_route(
                requested_space.space_id,
                "CLOUD_ALLOWED",
                [candidate],
                lambda selected: call_openai_compatible(
                    self.provider, requested_space, full_prompt=FULL_PROMPT, secret=TEST_SECRET
                ),
            )

        self.assertEqual(self.provider.records(), [])

    def test_local_failure_does_not_silently_fall_back_to_cloud(self) -> None:
        identity = make_chain_identity(33)
        local = RouteCandidate(identity.space_id, "local-primary", "LOCAL")
        calls = {"LOCAL": 0, "CLOUD": 0}

        def call_candidate(selected: RouteCandidate) -> object:
            calls[selected.egress_class] += 1
            if selected.egress_class == "CLOUD":
                return call_openai_compatible(
                    self.provider, identity, full_prompt=FULL_PROMPT, secret=TEST_SECRET
                )
            raise ProviderCallError("UNAVAILABLE")

        with self.assertRaises(RouteExhaustedError):
            # There is no implicit cloud candidate in this explicit LOCAL_ONLY route.
            self.policy.execute_route(identity.space_id, "LOCAL_ONLY", [local], call_candidate)

        self.assertEqual(calls, {"LOCAL": 1, "CLOUD": 0})
        self.assertEqual(self.provider.records(), [])

    def test_cloud_route_requires_explicit_space_authorization(self) -> None:
        identity = make_chain_identity(34)
        cloud = RouteCandidate(identity.space_id, "cloud-authorized", "CLOUD")
        with self.assertRaises(EgressDeniedError):
            self._validate_cloud_binding(identity.space_id, "CLOUD_ALLOWED", enabled=False, authorization=None)

        self._validate_cloud_binding(
            identity.space_id,
            "CLOUD_ALLOWED",
            enabled=True,
            authorization={"approval_id": "approval-fixture", "space_id": identity.space_id},
        )
        self.provider.set_behavior(identity.request_id, MockBehavior.success())
        response = self.policy.execute_route(
            identity.space_id,
            "CLOUD_ALLOWED",
            [cloud],
            lambda selected: call_openai_compatible(
                self.provider, identity, full_prompt=FULL_PROMPT, secret=TEST_SECRET
            ),
        )
        self.assertEqual(response.content, "fixture answer")
        self.assertEqual(len(self.provider.records()), 1)

    def test_cloud_request_body_is_redacted_and_evidence_fixture_has_no_secret(self) -> None:
        identity = make_chain_identity(35)
        self.provider.set_behavior(identity.request_id, MockBehavior.success())
        call_openai_compatible(
            self.provider,
            identity,
            full_prompt=FULL_PROMPT,
            secret=TEST_SECRET,
        )
        record = self.provider.records()[0]
        self.assertNotIn(TEST_SECRET, record["body"])
        self.assertNotIn(FULL_PROMPT, record["body"])
        body = json.loads(record["body"])
        self.assertTrue(body["messages"][1]["content"].startswith("[redacted-probe:"))

        from pathlib import Path

        evidence_path = Path(__file__).resolve().parents[1] / "evidence" / "phase2-cloud-concurrency.json"
        evidence_text = evidence_path.read_text(encoding="utf-8")
        self.assertNotIn(TEST_SECRET, evidence_text)
        self.assertNotIn(FULL_PROMPT, evidence_text)

    @staticmethod
    def _validate_cloud_binding(
        space_id: str,
        decision: str,
        *,
        enabled: bool,
        authorization: dict[str, str] | None,
    ) -> None:
        if decision != "CLOUD_ALLOWED" or not enabled:
            raise EgressDeniedError("cloud egress is disabled")
        if not authorization or authorization.get("space_id") != space_id:
            raise EgressDeniedError("explicit cloud authorization is missing or scoped incorrectly")


if __name__ == "__main__":
    unittest.main()
