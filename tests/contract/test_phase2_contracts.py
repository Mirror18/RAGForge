"""Executable Phase 2 contract checks using only the Python standard library."""

from __future__ import annotations

import copy
import json
import re
import unittest
from datetime import datetime
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
OPENAPI_PATH = ROOT / "contracts" / "openapi" / "ragforge-api-v1.yaml"
EVENTS_DIR = ROOT / "contracts" / "events"
UUID_V7 = re.compile(
    r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-7[0-9a-fA-F]{3}-"
    r"[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"
)


class SchemaViolation(AssertionError):
    """Raised when a checked contract fixture violates its JSON Schema subset."""


def load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def json_pointer(document: Any, pointer: str) -> Any:
    if pointer in ("", "/"):
        return document
    current = document
    for raw_part in pointer.lstrip("/").split("/"):
        part = raw_part.replace("~1", "/").replace("~0", "~")
        current = current[int(part)] if isinstance(current, list) else current[part]
    return current


def validate_instance(
    instance: Any,
    schema: dict[str, Any],
    schema_path: Path,
    root_schema: dict[str, Any] | None = None,
    path: str = "$",
) -> None:
    """Validate the JSON Schema keywords used by the checked-in contracts."""

    root_schema = schema if root_schema is None else root_schema
    if "$ref" in schema:
        ref_file, _, fragment = schema["$ref"].partition("#")
        if ref_file:
            referenced_path = (schema_path.parent / ref_file).resolve()
            referenced = load_json(referenced_path)
            validate_instance(
                instance,
                referenced if not fragment else json_pointer(referenced, fragment),
                referenced_path,
                referenced,
                path,
            )
        else:
            validate_instance(instance, json_pointer(root_schema, fragment), schema_path, root_schema, path)
        return

    for sub_schema in schema.get("allOf", []):
        validate_instance(instance, sub_schema, schema_path, root_schema, path)

    if "oneOf" in schema:
        matches = 0
        for sub_schema in schema["oneOf"]:
            try:
                validate_instance(instance, sub_schema, schema_path, root_schema, path)
            except SchemaViolation:
                continue
            matches += 1
        if matches != 1:
            raise SchemaViolation(f"{path}: expected exactly one oneOf branch, matched {matches}")

    if "const" in schema and instance != schema["const"]:
        raise SchemaViolation(f"{path}: expected const {schema['const']!r}, got {instance!r}")
    if "enum" in schema and instance not in schema["enum"]:
        raise SchemaViolation(f"{path}: {instance!r} is not in enum")
    if "not" in schema:
        try:
            validate_instance(instance, schema["not"], schema_path, root_schema, path)
        except SchemaViolation:
            pass
        else:
            raise SchemaViolation(f"{path}: instance matches forbidden schema")

    expected_types = schema.get("type")
    if expected_types is not None:
        expected_types = [expected_types] if isinstance(expected_types, str) else expected_types
        if not any(_matches_type(instance, expected_type) for expected_type in expected_types):
            raise SchemaViolation(f"{path}: expected {expected_types}, got {type(instance).__name__}")

    if isinstance(instance, dict):
        if "propertyNames" in schema:
            for name in instance:
                validate_instance(name, schema["propertyNames"], schema_path, root_schema, f"{path} property name")
        required = schema.get("required", [])
        missing = [name for name in required if name not in instance]
        if missing:
            raise SchemaViolation(f"{path}: missing required fields {missing}")
        properties = schema.get("properties", {})
        if schema.get("additionalProperties") is False:
            unknown = sorted(set(instance) - set(properties))
            if unknown:
                raise SchemaViolation(f"{path}: unknown fields {unknown}")
        for name, value in instance.items():
            if name in properties:
                validate_instance(value, properties[name], schema_path, root_schema, f"{path}.{name}")

    if isinstance(instance, list):
        if "minItems" in schema and len(instance) < schema["minItems"]:
            raise SchemaViolation(f"{path}: fewer than minItems")
        if "maxItems" in schema and len(instance) > schema["maxItems"]:
            raise SchemaViolation(f"{path}: more than maxItems")
        if "items" in schema:
            for index, value in enumerate(instance):
                validate_instance(value, schema["items"], schema_path, root_schema, f"{path}[{index}]")

    if isinstance(instance, str):
        if "minLength" in schema and len(instance) < schema["minLength"]:
            raise SchemaViolation(f"{path}: shorter than minLength")
        if "maxLength" in schema and len(instance) > schema["maxLength"]:
            raise SchemaViolation(f"{path}: longer than maxLength")
        if "pattern" in schema and re.search(schema["pattern"], instance) is None:
            raise SchemaViolation(f"{path}: does not match pattern {schema['pattern']!r}")
        if schema.get("format") == "uuidv7" and UUID_V7.fullmatch(instance) is None:
            raise SchemaViolation(f"{path}: invalid UUIDv7")
        if schema.get("format") == "date-time":
            try:
                datetime.fromisoformat(instance.replace("Z", "+00:00"))
            except ValueError as exc:
                raise SchemaViolation(f"{path}: invalid date-time") from exc

    if isinstance(instance, (int, float)) and not isinstance(instance, bool):
        if "minimum" in schema and instance < schema["minimum"]:
            raise SchemaViolation(f"{path}: below minimum")
        if "maximum" in schema and instance > schema["maximum"]:
            raise SchemaViolation(f"{path}: above maximum")


def _matches_type(value: Any, expected: str) -> bool:
    return {
        "object": isinstance(value, dict),
        "array": isinstance(value, list),
        "string": isinstance(value, str),
        "integer": isinstance(value, int) and not isinstance(value, bool),
        "number": isinstance(value, (int, float)) and not isinstance(value, bool),
        "boolean": isinstance(value, bool),
        "null": value is None,
    }.get(expected, True)


class Phase2OpenApiContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.spec = load_json(OPENAPI_PATH)

    def test_openapi_document_is_parseable_and_planned(self) -> None:
        self.assertEqual(self.spec["openapi"], "3.1.0")
        self.assertEqual(self.spec["x-ragforge-contract-status"], "planned")
        self.assertEqual(self.spec["x-ragforge-phase2-status"], "phase2-contract")
        self.assertIn("/api/v1", json.dumps(self.spec["paths"]))

    def test_required_phase2_paths_exist_and_are_contract_only(self) -> None:
        expected = {
            "/api/v1/spaces/{spaceId}/provider-connections",
            "/api/v1/spaces/{spaceId}/provider-connections/{providerConnectionId}",
            "/api/v1/spaces/{spaceId}/model-profiles",
            "/api/v1/spaces/{spaceId}/model-routes",
            "/api/v1/spaces/{spaceId}/space-bindings",
            "/api/v1/spaces/{spaceId}/prompt-templates",
            "/api/v1/spaces/{spaceId}/prompt-templates/{promptTemplateId}/versions",
            "/api/v1/spaces/{spaceId}/prompt-templates/{promptTemplateId}/versions/{promptVersion}/publish",
            "/api/v1/spaces/{spaceId}/prompt-bindings",
            "/api/v1/spaces/{spaceId}/conversations/{conversationId}/runs",
            "/api/v1/spaces/{spaceId}/runs/{runId}",
            "/api/v1/spaces/{spaceId}/runs/{runId}/events",
            "/api/v1/spaces/{spaceId}/runs/{runId}/cancel",
            "/api/v1/spaces/{spaceId}/runs/{runId}/retry",
        }
        self.assertTrue(expected.issubset(self.spec["paths"]))
        for path in expected:
            self.assertEqual(self.spec["paths"][path]["x-ragforge-implementation-status"], "phase2-contract")

    def test_phase2_operations_have_correlation_and_mutation_guards(self) -> None:
        for path, path_item in self.spec["paths"].items():
            if path.startswith("/api/v1/spaces/{spaceId}/") is False:
                continue
            inherited = path_item.get("parameters", [])
            for method, operation in path_item.items():
                if method not in {"get", "post", "put", "patch", "delete"}:
                    continue
                refs = inherited + operation.get("parameters", [])
                names = {self._resolve(parameter["$ref"])["name"] for parameter in refs if "$ref" in parameter}
                self.assertIn("X-Correlation-Id", names, f"{method.upper()} {path} lacks correlation")
                if method in {"post", "put", "patch", "delete"}:
                    self.assertIn("Idempotency-Key", names, f"{method.upper()} {path} lacks idempotency")
                    self.assertIn("X-CSRF-Token", names, f"{method.upper()} {path} lacks CSRF")

    def test_versioned_space_resources_require_space_and_version(self) -> None:
        for name in ("ProviderConnection", "ModelProfile", "ModelRoute", "SpaceBinding", "PromptBinding", "Run", "Step", "ModelInvocation", "UsageLedger"):
            schema = self.spec["components"]["schemas"][name]
            self.assertIn("spaceId", schema["required"], name)
            self.assertIn("version", schema["required"], name)

    def test_enum_error_and_sensitive_field_constraints(self) -> None:
        provider = self._provider_connection()
        schema = self.spec["components"]["schemas"]["ProviderConnection"]
        validate_instance(provider, schema, OPENAPI_PATH, self.spec)
        invalid_enum = copy.deepcopy(provider)
        invalid_enum["egressClass"] = "CLOUD_FALLBACK"
        with self.assertRaises(SchemaViolation):
            validate_instance(invalid_enum, schema, OPENAPI_PATH, self.spec)
        invalid_secret = copy.deepcopy(provider)
        invalid_secret["apiKey"] = "not-a-real-secret"
        with self.assertRaises(SchemaViolation):
            validate_instance(invalid_secret, schema, OPENAPI_PATH, self.spec)

        problem = self.spec["components"]["schemas"]["ProblemDetails"]
        error = {"type": "about:blank", "title": "Timeout", "status": 504, "detail": "Provider timed out", "instance": "/runs/1", "code": "PROVIDER_TIMEOUT", "correlationId": self.ids["correlation"], "errorClass": "TIMEOUT", "retryable": True}
        validate_instance(error, problem, OPENAPI_PATH, self.spec)
        invalid_error = copy.deepcopy(error)
        invalid_error["errorClass"] = "UNKNOWN"
        with self.assertRaises(SchemaViolation):
            validate_instance(invalid_error, problem, OPENAPI_PATH, self.spec)

    def test_published_prompt_versions_are_immutable(self) -> None:
        prompt = self.spec["components"]["schemas"]["PromptVersion"]
        self.assertEqual(prompt["properties"]["immutableAfterPublish"]["const"], True)
        self.assertEqual(prompt["properties"]["state"]["enum"], ["DRAFT", "PUBLISHED", "RETIRED"])
        version_path = self.spec["paths"]["/api/v1/spaces/{spaceId}/prompt-templates/{promptTemplateId}/versions/{promptVersion}"]
        self.assertIn("get", version_path)
        self.assertNotIn("put", version_path)
        self.assertNotIn("patch", version_path)
        self.assertNotIn("delete", version_path)
        self.assertIn("immutable", self.spec["paths"]["/api/v1/spaces/{spaceId}/prompt-templates/{promptTemplateId}/versions/{promptVersion}/publish"]["post"]["description"])

    def test_cloud_route_requires_explicit_authorization_and_no_silent_fallback(self) -> None:
        schema = self.spec["components"]["schemas"]["SpaceBindingUpdateRequest"]
        local = {"version": 1, "chatRouteId": self.ids["route"], "embeddingRouteId": self.ids["route2"], "rerankRouteId": self.ids["route3"], "promptVersionId": self.ids["prompt"], "cloudEgressEnabled": False}
        validate_instance(local, schema, OPENAPI_PATH, self.spec)
        cloud_without_approval = copy.deepcopy(local)
        cloud_without_approval["cloudEgressEnabled"] = True
        with self.assertRaises(SchemaViolation):
            validate_instance(cloud_without_approval, schema, OPENAPI_PATH, self.spec)
        cloud_with_approval = copy.deepcopy(cloud_without_approval)
        cloud_with_approval["cloudEgressAuthorization"] = {"approvalId": self.ids["approval"], "approvedBy": self.ids["user"], "approvedAt": "2026-08-13T00:00:00Z", "expiresAt": "2026-08-14T00:00:00Z", "scope": "CHAT"}
        validate_instance(cloud_with_approval, schema, OPENAPI_PATH, self.spec)
        route = self.spec["components"]["schemas"]["ModelRouteRequest"]
        self.assertNotIn("CLOUD_FALLBACK", route["properties"]["failoverPolicy"]["enum"])
        self.assertIn("same egress", self.spec["paths"]["/api/v1/spaces/{spaceId}/model-routes"]["post"]["description"])

    def test_sse_contract_has_sequence_replay_snapshot_and_cancel(self) -> None:
        events_path = self.spec["paths"]["/api/v1/spaces/{spaceId}/runs/{runId}/events"]
        operation = events_path["get"]
        names = {self._resolve(parameter["$ref"])["name"] for parameter in operation["parameters"]}
        self.assertIn("Last-Event-ID", names)
        self.assertIn("snapshot", names)
        self.assertEqual(operation["responses"]["200"]["content"]["text/event-stream"]["schema"]["$ref"], "#/components/schemas/SseEvent")
        self.assertIn("replays retained events", operation["description"])
        cancel = self.spec["paths"]["/api/v1/spaces/{spaceId}/runs/{runId}/cancel"]["post"]
        self.assertIn("Idempotency-Key", {self._resolve(parameter["$ref"])["name"] for parameter in cancel["parameters"]})
        self.assertIn("idempotent", cancel["description"])

        schema = self.spec["components"]["schemas"]["SseEvent"]
        event = {"id": self.ids["event"], "sequence": 1, "runId": self.ids["run"], "spaceId": self.ids["space"], "correlationId": self.ids["correlation"], "occurredAt": "2026-08-13T00:00:00Z", "type": "run.snapshot", "version": "v1", "payload": {"status": "RUNNING"}}
        validate_instance(event, schema, OPENAPI_PATH, self.spec)
        invalid_sequence = copy.deepcopy(event)
        invalid_sequence["sequence"] = 0
        with self.assertRaises(SchemaViolation):
            validate_instance(invalid_sequence, schema, OPENAPI_PATH, self.spec)
        invalid_payload = copy.deepcopy(event)
        invalid_payload["payload"]["apiKey"] = "forbidden"
        with self.assertRaises(SchemaViolation):
            validate_instance(invalid_payload, schema, OPENAPI_PATH, self.spec)

    def test_run_usage_ledger_projection_allows_cancelled_run_without_usage(self) -> None:
        schema = self.spec["components"]["schemas"]["Run"]
        run = {
            "runId": self.ids["run"],
            "spaceId": self.ids["space"],
            "conversationId": self.ids["run"],
            "version": 1,
            "status": "CANCELLED",
            "correlationId": self.ids["correlation"],
            "modelRouteId": self.ids["route"],
            "promptVersionId": self.ids["prompt"],
            "usageLedgerId": None,
            "cancelRequested": True,
            "error": None,
            "createdAt": "2026-08-13T00:00:00Z",
            "startedAt": None,
            "finishedAt": "2026-08-13T00:00:01Z",
        }
        validate_instance(run, schema, OPENAPI_PATH, self.spec)

    def test_phase2_event_schemas_parse_validate_and_require_trace_identity(self) -> None:
        samples = self._event_samples()
        self.assertEqual({path.name for path in EVENTS_DIR.glob("run.*.schema.json")}, set(samples))
        for filename, sample in samples.items():
            path = EVENTS_DIR / filename
            schema = load_json(path)
            self.assertEqual(schema["$schema"], "https://json-schema.org/draft/2020-12/schema")
            validate_instance(sample, schema, path)
            for missing in ("spaceId", "correlationId", "causationId"):
                invalid = copy.deepcopy(sample)
                del invalid[missing]
                with self.assertRaises(SchemaViolation, msg=f"{filename} accepted missing {missing}"):
                    validate_instance(invalid, schema, path)
            invalid_secret = copy.deepcopy(sample)
            invalid_secret["payload"]["secret"] = "forbidden"
            with self.assertRaises(SchemaViolation, msg=f"{filename} accepted secret"):
                validate_instance(invalid_secret, schema, path)

    def test_usage_dedupe_semantics_are_part_of_the_contract(self) -> None:
        schema = load_json(EVENTS_DIR / "run.usage.recorded.v1.schema.json")
        self.assertEqual(schema["x-ragforge-on-duplicate"], "upsert-same-ledger-row")
        self.assertEqual(schema["x-ragforge-uniqueness"], ["spaceId", "modelInvocationId", "usageSource", "dedupeKey"])
        first = self._event_samples()["run.usage.recorded.v1.schema.json"]
        duplicate = copy.deepcopy(first)
        duplicate["eventId"] = self.ids["event2"]
        duplicate["payload"]["ledgerVersion"] = 2
        key = (first["spaceId"], first["payload"]["modelInvocationId"], first["payload"]["usageSource"], first["payload"]["dedupeKey"])
        duplicate_key = (duplicate["spaceId"], duplicate["payload"]["modelInvocationId"], duplicate["payload"]["usageSource"], duplicate["payload"]["dedupeKey"])
        self.assertEqual(key, duplicate_key)
        validate_instance(duplicate, schema, EVENTS_DIR / "run.usage.recorded.v1.schema.json")

    ids = {
        "event": "0190f5c2-7c1e-7abc-8def-1234567890ab",
        "event2": "0190f5c2-7c1e-7abd-8def-1234567890ab",
        "correlation": "0190f5c2-7c1e-7abe-8def-1234567890ab",
        "causation": "0190f5c2-7c1e-7abf-8def-1234567890ab",
        "space": "0190f5c2-7c1e-7ac0-8def-1234567890ab",
        "run": "0190f5c2-7c1e-7ac1-8def-1234567890ab",
        "step": "0190f5c2-7c1e-7ac2-8def-1234567890ab",
        "invocation": "0190f5c2-7c1e-7ac3-8def-1234567890ab",
        "ledger": "0190f5c2-7c1e-7ac4-8def-1234567890ab",
        "route": "0190f5c2-7c1e-7ac5-8def-1234567890ab",
        "route2": "0190f5c2-7c1e-7ac6-8def-1234567890ab",
        "route3": "0190f5c2-7c1e-7ac7-8def-1234567890ab",
        "prompt": "0190f5c2-7c1e-7ac8-8def-1234567890ab",
        "approval": "0190f5c2-7c1e-7ac9-8def-1234567890ab",
        "user": "0190f5c2-7c1e-7aca-8def-1234567890ab",
    }

    def _provider_connection(self) -> dict[str, Any]:
        return {"providerConnectionId": self.ids["invocation"], "spaceId": self.ids["space"], "version": 1, "providerType": "OLLAMA", "egressClass": "LOCAL", "endpoint": "http://ollama:11434", "credentialRef": "local-ollama", "status": "ACTIVE", "createdAt": "2026-08-13T00:00:00Z", "updatedAt": "2026-08-13T00:00:00Z"}

    def _base_event(self, event_type: str, payload: dict[str, Any]) -> dict[str, Any]:
        return {"eventId": self.ids["event"], "eventType": event_type, "occurredAt": "2026-08-13T00:00:00Z", "producer": "ragforge-server", "correlationId": self.ids["correlation"], "causationId": self.ids["causation"], "spaceId": self.ids["space"], "aggregateId": self.ids["run"], "payload": payload}

    def _event_samples(self) -> dict[str, dict[str, Any]]:
        return {
            "run.status.changed.v1.schema.json": self._base_event("run.status.changed.v1", {"runId": self.ids["run"], "runVersion": 1, "previousStatus": "QUEUED", "status": "RUNNING", "correlationId": self.ids["correlation"], "cancelRequested": False, "errorClass": None}),
            "run.model.invocation.recorded.v1.schema.json": self._base_event("run.model.invocation.recorded.v1", {"runId": self.ids["run"], "stepId": self.ids["step"], "modelInvocationId": self.ids["invocation"], "invocationVersion": 1, "modelProfileId": self.ids["route"], "modelProfileVersion": 1, "status": "SUCCEEDED", "attempt": 1, "usageLedgerId": self.ids["ledger"], "correlationId": self.ids["correlation"], "errorClass": None}),
            "run.usage.recorded.v1.schema.json": self._base_event("run.usage.recorded.v1", {"runId": self.ids["run"], "modelInvocationId": self.ids["invocation"], "usageLedgerId": self.ids["ledger"], "ledgerVersion": 1, "correlationId": self.ids["correlation"], "usageSource": "PROVIDER_REPORTED", "dedupeKey": "provider-report-001", "inputTokens": 12, "outputTokens": 8, "totalTokens": 20, "isFinal": True}),
        }

    def _resolve(self, ref: str) -> dict[str, Any]:
        prefix, _, fragment = ref.partition("#")
        self.assertEqual(prefix, "")
        return json_pointer(self.spec, fragment)


if __name__ == "__main__":
    unittest.main()
