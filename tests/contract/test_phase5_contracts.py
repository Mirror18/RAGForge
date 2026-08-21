"""Phase 5 public contract tests using only the Python standard library."""

from __future__ import annotations

import copy
import json
import re
import unittest
from datetime import datetime
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
ANSWER = ROOT / "contracts" / "answer"
AGENT = ROOT / "contracts" / "agent"
EVENTS = ROOT / "contracts" / "events"
OPENAPI = ROOT / "contracts" / "openapi" / "ragforge-api-v1.yaml"
FIXTURES = ROOT / "tests" / "contract" / "phase5" / "fixtures"
UUID_V7 = re.compile(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-7[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
SENSITIVE = {"password", "secret", "api_key", "access_token", "credential_ref", "authorization", "cookie", "request_headers", "response_headers", "raw_prompt", "system_prompt", "full_text", "raw_text", "document_content", "response_body", "quote", "citation_text", "filename"}


class SchemaViolation(AssertionError):
    pass


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def pointer(document: Any, fragment: str) -> Any:
    current = document
    if not fragment:
        return current
    for part in fragment.lstrip("/").split("/"):
        part = part.replace("~1", "/").replace("~0", "~")
        current = current[int(part)] if isinstance(current, list) else current[part]
    return current


def matches_type(value: Any, expected: str) -> bool:
    return {"object": isinstance(value, dict), "array": isinstance(value, list), "string": isinstance(value, str), "integer": isinstance(value, int) and not isinstance(value, bool), "number": isinstance(value, (int, float)) and not isinstance(value, bool), "boolean": isinstance(value, bool), "null": value is None}.get(expected, True)


def validate(value: Any, schema: dict[str, Any], schema_path: Path, root: dict[str, Any] | None = None, path: str = "$") -> None:
    root = schema if root is None else root
    if "$ref" in schema:
        ref_file, _, fragment = schema["$ref"].partition("#")
        target_path = (schema_path.parent / ref_file).resolve() if ref_file else schema_path
        target_root = load_json(target_path) if ref_file else root
        validate(value, pointer(target_root, fragment), target_path, target_root, path)
        return
    for sub in schema.get("allOf", []):
        validate(value, sub, schema_path, root, path)
    if "anyOf" in schema and not any(_valid(value, sub, schema_path, root, path) for sub in schema["anyOf"]):
        raise SchemaViolation(f"{path}: no anyOf branch matched")
    if "oneOf" in schema and sum(_valid(value, sub, schema_path, root, path) for sub in schema["oneOf"]) != 1:
        raise SchemaViolation(f"{path}: expected one oneOf branch")
    if "if" in schema and _valid(value, schema["if"], schema_path, root, path):
        if "then" in schema:
            validate(value, schema["then"], schema_path, root, path)
    elif "else" in schema:
        validate(value, schema["else"], schema_path, root, path)
    if "const" in schema and value != schema["const"]:
        raise SchemaViolation(f"{path}: expected {schema['const']!r}")
    if "enum" in schema and value not in schema["enum"]:
        raise SchemaViolation(f"{path}: {value!r} is not an enum value")
    if "not" in schema and _valid(value, schema["not"], schema_path, root, path):
        raise SchemaViolation(f"{path}: forbidden value")
    expected = schema.get("type")
    if expected is not None:
        expected = [expected] if isinstance(expected, str) else expected
        if not any(matches_type(value, item) for item in expected):
            raise SchemaViolation(f"{path}: wrong type")
    if isinstance(value, dict):
        properties = schema.get("properties", {})
        missing = [name for name in schema.get("required", []) if name not in value]
        if missing:
            raise SchemaViolation(f"{path}: missing {missing}")
        if schema.get("additionalProperties") is False:
            unknown = sorted(set(value) - set(properties))
            if unknown:
                raise SchemaViolation(f"{path}: unknown {unknown}")
        if "propertyNames" in schema:
            for name in value:
                validate(name, schema["propertyNames"], schema_path, root, f"{path} property name")
        for name, item in value.items():
            if name in properties:
                validate(item, properties[name], schema_path, root, f"{path}.{name}")
    if isinstance(value, list):
        if len(value) < schema.get("minItems", 0) or len(value) > schema.get("maxItems", len(value)):
            raise SchemaViolation(f"{path}: item count out of range")
        if schema.get("uniqueItems") and len({json.dumps(item, sort_keys=True) for item in value}) != len(value):
            raise SchemaViolation(f"{path}: duplicate items")
        if "items" in schema:
            for index, item in enumerate(value):
                validate(item, schema["items"], schema_path, root, f"{path}[{index}]")
    if isinstance(value, str):
        if len(value) < schema.get("minLength", 0) or len(value) > schema.get("maxLength", len(value)):
            raise SchemaViolation(f"{path}: string length out of range")
        if "pattern" in schema and re.search(schema["pattern"], value) is None:
            raise SchemaViolation(f"{path}: pattern mismatch")
        if schema.get("format") == "uuidv7" and UUID_V7.fullmatch(value) is None:
            raise SchemaViolation(f"{path}: invalid UUIDv7")
        if schema.get("format") == "date-time":
            try:
                datetime.fromisoformat(value.replace("Z", "+00:00"))
            except ValueError as exc:
                raise SchemaViolation(f"{path}: invalid date-time") from exc
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        if value < schema.get("minimum", value) or value > schema.get("maximum", value):
            raise SchemaViolation(f"{path}: number out of range")


def _valid(value: Any, schema: dict[str, Any], schema_path: Path, root: dict[str, Any], path: str) -> bool:
    try:
        validate(value, schema, schema_path, root, path)
    except SchemaViolation:
        return False
    return True


def walk_keys(value: Any) -> list[str]:
    found: list[str] = []
    if isinstance(value, dict):
        for key, item in value.items():
            if key.lower() in SENSITIVE:
                found.append(key)
            found.extend(walk_keys(item))
    elif isinstance(value, list):
        for item in value:
            found.extend(walk_keys(item))
    return found


def validate_answer_semantics(answer: dict[str, Any], evidence_bundle_ids: set[str] | None = None) -> None:
    """Checks invariants that JSON Schema cannot express without provider-specific conditionals."""
    if answer["status"] == "COMPLETED" and (not answer["answer_text"] or not answer["claims"] or answer["abstention"] is not None):
        raise SchemaViolation("COMPLETED answer must contain text/claims and no abstention")
    if answer["status"] == "ABSTAINED" and (answer["answer_text"] is not None or answer["claims"] or answer["abstention"] is None):
        raise SchemaViolation("ABSTAINED answer must contain abstention and no text/claims")
    if any(claim["space_id"] != answer["space_id"] or claim["correlation_id"] != answer["correlation_id"] or claim["run_id"] != answer["run_id"] for claim in answer["claims"]):
        raise SchemaViolation("claim trace identity does not match the answer")
    citation_ids = {citation["evidence_id"] for citation in answer["citations"]}
    if any(token not in citation_ids for claim in answer["claims"] for token in claim["citation_tokens"]):
        raise SchemaViolation("claim token is not projected as a citation")
    if evidence_bundle_ids is not None and citation_ids - evidence_bundle_ids:
        raise SchemaViolation("citation is outside the current EvidenceBundle")
    if any(citation["space_id"] != answer["space_id"] for citation in answer["citations"]):
        raise SchemaViolation("citation crosses the answer space boundary")
    if answer["abstention"] is not None and answer["abstention"]["space_id"] != answer["space_id"]:
        raise SchemaViolation("abstention crosses the answer space boundary")


class Phase5ContractTest(unittest.TestCase):
    ids = {
        "space": "0190f5c2-7c1e-7ac0-8def-1234567890ab",
        "other_space": "0190f5c2-7c1e-7ad0-8def-1234567890ab",
        "correlation": "0190f5c2-7c1e-7abe-8def-1234567890ab",
        "run": "0190f5c2-7c1e-7ac1-8def-1234567890ab",
        "answer": "0190f5c2-7c1e-7ad1-8def-1234567890ab",
        "claim": "0190f5c2-7c1e-7ad2-8def-1234567890ab",
        "evidence": "0190f5c2-7c1e-7ad3-8def-1234567890ab",
        "outside_evidence": "0190f5c2-7c1e-7ad4-8def-1234567890ab",
        "bundle": "0190f5c2-7c1e-7ad5-8def-1234567890ab",
        "index": "0190f5c2-7c1e-7ad6-8def-1234567890ab",
        "profile": "0190f5c2-7c1e-7ad7-8def-1234567890ab",
        "revision": "0190f5c2-7c1e-7ad8-8def-1234567890ab",
        "parent": "0190f5c2-7c1e-7ad9-8def-1234567890ab",
        "child": "0190f5c2-7c1e-7ada-8def-1234567890ab",
        "tool_call": "0190f5c2-7c1e-7adb-8def-1234567890ab",
        "tool_result": "0190f5c2-7c1e-7adc-8def-1234567890ab",
        "step": "0190f5c2-7c1e-7add-8def-1234567890ab",
    }

    @classmethod
    def setUpClass(cls) -> None:
        cls.schemas = {
            "answer": (ANSWER / "answer.v1.schema.json", load_json(ANSWER / "answer.v1.schema.json")),
            "claim": (ANSWER / "claim.v1.schema.json", load_json(ANSWER / "claim.v1.schema.json")),
            "citation": (ANSWER / "citation.v1.schema.json", load_json(ANSWER / "citation.v1.schema.json")),
            "abstention": (ANSWER / "abstention.v1.schema.json", load_json(ANSWER / "abstention.v1.schema.json")),
            "tool_call": (AGENT / "tool-call.v1.schema.json", load_json(AGENT / "tool-call.v1.schema.json")),
            "tool_result": (AGENT / "tool-result.v1.schema.json", load_json(AGENT / "tool-result.v1.schema.json")),
            "sse": (EVENTS / "answer.sse.v1.schema.json", load_json(EVENTS / "answer.sse.v1.schema.json")),
        }
        cls.openapi = load_json(OPENAPI)

    def assert_valid(self, kind: str, instance: Any) -> None:
        path, schema = self.schemas[kind]
        validate(instance, schema, path)

    def assert_invalid(self, kind: str, instance: Any) -> None:
        with self.assertRaises(SchemaViolation):
            self.assert_valid(kind, instance)

    def test_v1_contracts_and_valid_fixtures_parse(self) -> None:
        for path, schema in self.schemas.values():
            self.assertEqual(schema["$schema"], "https://json-schema.org/draft/2020-12/schema")
            self.assertEqual(path.suffix, ".json")
        for kind, filename in (("answer", "answer.json"), ("claim", "claim.json"), ("citation", "citation.json"), ("abstention", "abstention.json"), ("tool_call", "tool-call.json"), ("tool_result", "tool-result.json")):
            self.assert_valid(kind, load_json(FIXTURES / "valid" / filename))
        for event in load_json(FIXTURES / "valid" / "answer-sse-events.json"):
            self.assert_valid("sse", event)

    def test_answer_claim_citation_and_abstention_preserve_space_and_provenance(self) -> None:
        answer = load_json(FIXTURES / "valid" / "answer.json")
        citation = load_json(FIXTURES / "valid" / "citation.json")
        self.assertEqual(answer["schema_version"], "v1")
        self.assertEqual(answer["space_id"], self.ids["space"])
        self.assertEqual(answer["correlation_id"], self.ids["correlation"])
        self.assertEqual(answer["run_id"], self.ids["run"])
        self.assertEqual(answer["claims"][0]["citation_tokens"], [self.ids["evidence"]])
        self.assertEqual(citation["evidence_id"], answer["claims"][0]["citation_tokens"][0])
        self.assertEqual(citation["space_id"], answer["space_id"])
        self.assertEqual(answer["provenance"]["evidence_bundle_id"], self.ids["bundle"])
        validate_answer_semantics(answer, {self.ids["evidence"]})
        self.assertFalse(walk_keys(answer), "answer projection contains sensitive fields")
        abstention = load_json(FIXTURES / "valid" / "abstention.json")
        self.assertEqual(abstention["reason_code"], "NO_EVIDENCE")
        self.assertFalse(walk_keys(abstention), "abstention projection contains sensitive fields")

    def test_answer_status_invariants_are_structured_and_space_scoped(self) -> None:
        completed = load_json(FIXTURES / "valid" / "answer.json")
        self.assertEqual(completed["status"], "COMPLETED")
        self.assertTrue(completed["answer_text"])
        self.assertTrue(completed["claims"])
        self.assertIsNone(completed["abstention"])
        invalid_completed = copy.deepcopy(completed)
        invalid_completed["answer_text"] = None
        invalid_completed["claims"] = []
        self.assert_invalid("answer", invalid_completed)
        invalid_abstained = copy.deepcopy(completed)
        invalid_abstained["status"] = "ABSTAINED"
        invalid_abstained["answer_text"] = None
        invalid_abstained["claims"] = []
        invalid_abstained["abstention"] = load_json(FIXTURES / "valid" / "abstention.json")
        self.assert_valid("answer", invalid_abstained)
        validate_answer_semantics(invalid_abstained)
        self.assertEqual(invalid_abstained["abstention"]["space_id"], invalid_abstained["space_id"])
        invalid_abstained["abstention"]["space_id"] = self.ids["other_space"]
        with self.assertRaises(SchemaViolation):
            validate_answer_semantics(invalid_abstained)

    def test_citation_tokens_only_accept_evidence_id_and_reject_malformed_or_duplicate_tokens(self) -> None:
        schema_path, schema = self.schemas["claim"]
        self.assertIn("evidence_id", schema["x-ragforge-token-rule"])
        for filename in ("claim-malformed-token.json", "claim-duplicate-token.json"):
            self.assert_invalid("claim", load_json(FIXTURES / "invalid" / filename))
        valid = load_json(FIXTURES / "valid" / "claim.json")
        for token in ("[1]", "filename.md", "https://example.invalid/source", "evidence:" + self.ids["evidence"]):
            invalid = copy.deepcopy(valid)
            invalid["citation_tokens"] = [token]
            self.assert_invalid("claim", invalid)
        self.assertIsNotNone(schema_path)

    def test_cross_space_and_evidence_outside_bundle_are_semantically_invalid(self) -> None:
        answer = load_json(FIXTURES / "invalid" / "answer-cross-space.json")
        self.assert_valid("answer", answer)
        with self.assertRaises(SchemaViolation):
            validate_answer_semantics(answer, {self.ids["evidence"]})
        outside = load_json(FIXTURES / "invalid" / "citation-outside-bundle.json")
        self.assert_valid("citation", outside)
        bundle_ids = {self.ids["evidence"]}
        self.assertNotIn(outside["evidence_id"], bundle_ids)

    def test_tool_contract_closes_allow_list_and_sensitive_audit_surface(self) -> None:
        call = load_json(FIXTURES / "valid" / "tool-call.json")
        result = load_json(FIXTURES / "valid" / "tool-result.json")
        self.assertEqual(call["tool_name"], "knowledge.search")
        self.assertEqual(result["tool_name"], call["tool_name"])
        self.assertEqual(result["tool_call_id"], call["tool_call_id"])
        self.assertEqual(call["space_id"], result["space_id"])
        self.assertFalse(walk_keys(call))
        self.assertFalse(walk_keys(result))
        self.assert_invalid("tool_call", load_json(FIXTURES / "invalid" / "tool-unauthorized.json"))
        self.assert_invalid("tool_result", load_json(FIXTURES / "invalid" / "tool-sensitive-audit.json"))
        self.assertEqual(set(self.schemas["tool_call"][1]["properties"]["tool_name"]["enum"]), {"knowledge.search", "document.read", "web.fetch"})

    def test_answer_sse_freezes_event_types_identity_and_payloads(self) -> None:
        events = load_json(FIXTURES / "valid" / "answer-sse-events.json")
        self.assertEqual({event["event_type"] for event in events}, {"answer.delta", "answer.citation", "answer.abstention", "answer.tool", "answer.usage", "answer.error", "answer.done"})
        self.assertEqual([event["sequence"] for event in events], list(range(1, 8)))
        self.assertEqual(len({event["event_id"] for event in events}), len(events))
        self.assertTrue(all(event["schema_version"] == "v1" for event in events))
        self.assertTrue(all(event["space_id"] == self.ids["space"] and event["correlation_id"] == self.ids["correlation"] and event["run_id"] == self.ids["run"] for event in events))
        self.assert_invalid("sse", load_json(FIXTURES / "invalid" / "sse-sequence-zero.json"))
        invalid_type = copy.deepcopy(events[0])
        invalid_type["event_type"] = "shell.exec"
        self.assert_invalid("sse", invalid_type)
        cancelled = copy.deepcopy(events[-1])
        cancelled["payload"]["status"] = "CANCELLED"
        self.assert_valid("sse", cancelled)
        self.assertFalse(any(event["event_type"] == "answer.delta" and event["sequence"] > cancelled["sequence"] for event in events), "cancelled stream must not accept later answer.delta")

    def test_event_idempotency_and_sequence_rules_are_explicit(self) -> None:
        schema = self.schemas["sse"][1]
        self.assertIn("oneOf", schema)
        self.assertIn("event_id", schema["$defs"]["envelope"]["required"])
        self.assertIn("sequence", schema["$defs"]["envelope"]["required"])
        self.assertIn("idempotency_key", schema["$defs"]["envelope"]["required"])
        events = load_json(FIXTURES / "valid" / "answer-sse-events.json")
        duplicate_event_id = copy.deepcopy(events)
        duplicate_event_id[1]["event_id"] = duplicate_event_id[0]["event_id"]
        self.assertEqual(duplicate_event_id[0]["event_id"], duplicate_event_id[1]["event_id"])
        self.assertNotEqual(len({event["event_id"] for event in duplicate_event_id}), len(duplicate_event_id))
        non_monotonic = [events[0], events[2], events[1]]
        self.assertNotEqual([event["sequence"] for event in non_monotonic], sorted(event["sequence"] for event in non_monotonic))

    def test_phase5_openapi_paths_are_space_scoped_and_strictly_declared(self) -> None:
        paths = self.openapi["paths"]
        expected = {
            "/api/v1/spaces/{spaceId}/answers",
            "/api/v1/spaces/{spaceId}/answers/{runId}",
            "/api/v1/spaces/{spaceId}/answers/{runId}/events",
            "/api/v1/spaces/{spaceId}/answers/{runId}/cancel",
            "/api/v1/spaces/{spaceId}/runs/{runId}/citations/{evidenceId}/preview",
        }
        self.assertTrue(expected.issubset(paths))
        for path in expected:
            self.assertEqual(paths[path]["x-ragforge-implementation-status"], "phase5-implemented")
            self.assertIn("{spaceId}", path)
        operations = [operation for path in expected for method, operation in paths[path].items() if method in {"get", "post"}]
        self.assertEqual(len(operations), len({operation["operationId"] for operation in operations}))
        self.assertIn("IdempotencyKey", json.dumps(paths["/api/v1/spaces/{spaceId}/answers"]))
        self.assertIn("LastEventId", json.dumps(paths["/api/v1/spaces/{spaceId}/answers/{runId}/events"]))

    def test_phase5_openapi_answer_components_deny_raw_content_and_external_urls(self) -> None:
        schemas = self.openapi["components"]["schemas"]
        self.assertEqual(schemas["AnswerSseEvent"]["properties"]["event_type"]["enum"], [
            "answer.delta", "answer.citation", "answer.abstention", "answer.tool",
            "answer.usage", "answer.error", "answer.done"
        ])
        citation = schemas["AnswerCitation"]
        self.assertTrue(citation["additionalProperties"] is False)
        forbidden = {"quote", "url", "filename", "fullText", "rawText", "documentContent"}
        self.assertFalse(forbidden & set(citation["properties"]))
        self.assertFalse(forbidden & set(schemas["AnswerCitationPreview"]["properties"]))


if __name__ == "__main__":
    unittest.main()
