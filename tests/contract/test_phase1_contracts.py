"""Executable Phase 1 contract checks using only the Python standard library."""

from __future__ import annotations

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
    """Raised by the small schema subset exercised by these contract fixtures."""


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
    """Validate the JSON Schema features used by the checked-in event schemas.

    This deliberately small validator keeps contract tests dependency-free. It is
    not a replacement for a production JSON Schema implementation.
    """

    root_schema = schema if root_schema is None else root_schema
    if "$ref" in schema:
        ref = schema["$ref"]
        ref_file, _, fragment = ref.partition("#")
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


class Phase1OpenApiContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.spec = load_json(OPENAPI_PATH)

    def test_openapi_document_is_parseable_and_planned(self) -> None:
        self.assertEqual(self.spec["openapi"], "3.1.0")
        self.assertEqual(self.spec["x-ragforge-contract-status"], "planned")
        self.assertEqual(self.spec["servers"][0]["url"], "/")
        self.assertIn("/api/v1", json.dumps(self.spec["paths"]))

    def test_required_auth_and_space_paths_exist(self) -> None:
        expected = {
            ("/api/v1/auth/register", "post"),
            ("/api/v1/auth/login", "post"),
            ("/api/v1/sessions", "post"),
            ("/api/v1/sessions/current", "get"),
            ("/api/v1/sessions/current", "delete"),
            ("/api/v1/spaces", "get"),
            ("/api/v1/spaces", "post"),
            ("/api/v1/spaces/{spaceId}/members/{userId}", "put"),
        }
        actual = {
            (path, method)
            for path, path_item in self.spec["paths"].items()
            for method in path_item
            if method in {"get", "post", "put", "patch", "delete"}
        }
        self.assertTrue(expected.issubset(actual))

    def test_mutations_require_csrf_and_idempotency(self) -> None:
        anonymous_bootstrap = {
            ("/api/v1/auth/register", "post"),
            ("/api/v1/auth/login", "post"),
            ("/api/v1/sessions", "post"),
            ("/api/v1/bootstrap/platform-admin", "post"),
        }
        for path, path_item in self.spec["paths"].items():
            for method, operation in path_item.items():
                if method not in {"post", "put", "patch", "delete"}:
                    continue
                names = {self._resolve(ref)["name"] for ref in self._refs(operation.get("parameters", []))}
                self.assertIn("Idempotency-Key", names, f"{method.upper()} {path} lacks idempotency")
                if (path, method) in anonymous_bootstrap:
                    self.assertNotIn("X-CSRF-Token", names, f"{method.upper()} {path} must be CSRF-exempt")
                else:
                    self.assertIn("X-CSRF-Token", names, f"{method.upper()} {path} lacks CSRF")

    def test_correlation_id_is_available_on_every_operation(self) -> None:
        for path, path_item in self.spec["paths"].items():
            for method, operation in path_item.items():
                if method not in {"get", "post", "put", "patch", "delete"}:
                    continue
                references = self._refs(operation.get("parameters", []))
                names = {self._resolve(ref)["name"] for ref in references}
                self.assertIn("X-Correlation-Id", names, f"{method.upper()} {path} lacks correlation")

    def test_space_member_path_requires_uuidv7_space_and_user_ids(self) -> None:
        item = self.spec["paths"]["/api/v1/spaces/{spaceId}/members/{userId}"]
        names = {self._resolve(ref["$ref"])["name"] for ref in item["parameters"]}
        self.assertEqual(names, {"spaceId", "userId"})
        for ref in item["parameters"]:
            parameter = self._resolve(ref["$ref"])
            self.assertTrue(parameter["required"])
            self.assertEqual(parameter["in"], "path")
            self.assertEqual(parameter["schema"]["$ref"], "#/components/schemas/UuidV7")

    def test_cursor_pagination_is_opaque_and_response_has_next_cursor(self) -> None:
        operation = self.spec["paths"]["/api/v1/spaces"]["get"]
        cursor = next(self._resolve(ref) for ref in self._refs(operation["parameters"]) if self._resolve(ref)["name"] == "cursor")
        self.assertEqual(cursor["in"], "query")
        self.assertFalse(cursor["required"])
        self.assertEqual(cursor["schema"]["type"], "string")
        page = self._resolve(operation["responses"]["200"]["content"]["application/json"]["schema"]["$ref"])
        self.assertIn("nextCursor", page["required"])
        self.assertEqual(page["properties"]["nextCursor"]["type"], ["string", "null"])

    def test_problem_details_are_rfc9457_compatible_and_have_correlation(self) -> None:
        problem = self.spec["components"]["schemas"]["ProblemDetails"]
        for field in ("type", "title", "status", "detail", "instance", "code", "correlationId"):
            self.assertIn(field, problem["required"])
        self.assertEqual(problem["properties"]["correlationId"]["$ref"], "#/components/schemas/UuidV7")
        self.assertIn("fieldErrors", problem["properties"])
        for name, response in self.spec["components"]["responses"].items():
            if name == "InternalError" or response.get("content"):
                self.assertIn("application/problem+json", response["content"])

    def test_uuidv7_schema_rejects_wrong_version_or_variant(self) -> None:
        schema = self.spec["components"]["schemas"]["UuidV7"]
        valid = "0190f5c2-7c1e-7abc-8def-1234567890ab"
        validate_instance(valid, schema, OPENAPI_PATH, self.spec)
        for invalid in (
            "0190f5c2-7c1e-6abc-8def-1234567890ab",
            "0190f5c2-7c1e-7abc-0def-1234567890ab",
            "not-a-uuid",
        ):
            with self.assertRaises(SchemaViolation):
                validate_instance(invalid, schema, OPENAPI_PATH, self.spec)

    def _refs(self, parameters: list[dict[str, Any]]) -> list[dict[str, Any]]:
        return [parameter["$ref"] for parameter in parameters]

    def _resolve(self, ref: str) -> dict[str, Any]:
        prefix, _, fragment = ref.partition("#")
        self.assertEqual(prefix, "")
        return json_pointer(self.spec, fragment)


class Phase1EventContractTest(unittest.TestCase):
    ids = {
        "event": "0190f5c2-7c1e-7abc-8def-1234567890ab",
        "correlation": "0190f5c2-7c1e-7abd-8def-1234567890ab",
        "causation": "0190f5c2-7c1e-7abe-8def-1234567890ab",
        "space": "0190f5c2-7c1e-7abf-8def-1234567890ab",
        "job": "0190f5c2-7c1e-7ac0-8def-1234567890ab",
        "source": "0190f5c2-7c1e-7ac1-8def-1234567890ab",
        "revision": "0190f5c2-7c1e-7ac2-8def-1234567890ab",
        "pipeline": "0190f5c2-7c1e-7ac3-8def-1234567890ab",
        "attempt": "0190f5c2-7c1e-7ac4-8def-1234567890ab",
        "artifact": "0190f5c2-7c1e-7ac5-8def-1234567890ab",
        "parsed": "0190f5c2-7c1e-7ac6-8def-1234567890ab",
        "index": "0190f5c2-7c1e-7ac7-8def-1234567890ab",
    }

    def test_requested_event_has_space_and_trace_identity(self) -> None:
        path = EVENTS_DIR / "ingestion.job.requested.v1.schema.json"
        event = self._requested_event()
        validate_instance(event, load_json(path), path)
        self.assertEqual(event["eventType"], "ingestion.job.requested.v1")
        for field in ("spaceId", "aggregateId", "correlationId", "causationId"):
            self.assertIn(field, event)

    def test_completed_event_has_space_and_trace_identity(self) -> None:
        path = EVENTS_DIR / "ingestion.job.completed.v1.schema.json"
        event = self._completed_event()
        validate_instance(event, load_json(path), path)
        self.assertEqual(event["eventType"], "ingestion.job.completed.v1")
        for field in ("spaceId", "aggregateId", "correlationId", "causationId"):
            self.assertIn(field, event)

    def test_event_schemas_reject_missing_space_or_causation(self) -> None:
        for filename, event in (
            ("ingestion.job.requested.v1.schema.json", self._requested_event()),
            ("ingestion.job.completed.v1.schema.json", self._completed_event()),
        ):
            path = EVENTS_DIR / filename
            schema = load_json(path)
            for missing in ("spaceId", "causationId"):
                invalid = json.loads(json.dumps(event))
                del invalid[missing]
                with self.assertRaises(SchemaViolation, msg=f"{filename} accepted missing {missing}"):
                    validate_instance(invalid, schema, path)

    def test_event_payload_rejects_secret_and_full_document_fields(self) -> None:
        forbidden_keys = ("password", "secret", "apiKey", "accessToken", "documentContent", "fullText", "rawDocument")
        for filename, event in (
            ("ingestion.job.requested.v1.schema.json", self._requested_event()),
            ("ingestion.job.completed.v1.schema.json", self._completed_event()),
        ):
            path = EVENTS_DIR / filename
            schema = load_json(path)
            for forbidden in forbidden_keys:
                invalid = json.loads(json.dumps(event))
                invalid["payload"][forbidden] = "must-not-cross-event-boundary"
                with self.assertRaises(SchemaViolation, msg=f"{filename} accepted {forbidden}"):
                    validate_instance(invalid, schema, path)

    def test_generic_envelope_rejects_forbidden_payload_keys(self) -> None:
        path = EVENTS_DIR / "event-envelope.v1.schema.json"
        schema = load_json(path)
        event = {
            "eventId": self.ids["event"],
            "eventType": "example.event.v1",
            "occurredAt": "2026-08-12T08:00:00Z",
            "producer": "ragforge-server",
            "correlationId": self.ids["correlation"],
            "causationId": self.ids["causation"],
            "spaceId": self.ids["space"],
            "aggregateId": self.ids["job"],
            "payload": {"safeReference": self.ids["artifact"]},
        }
        validate_instance(event, schema, path)
        event["payload"]["fullText"] = "must-not-cross-event-boundary"
        with self.assertRaises(SchemaViolation):
            validate_instance(event, schema, path)

    def test_event_schema_sources_do_not_define_sensitive_payload_keys(self) -> None:
        key_pattern = re.compile(r'"[^"\\]*(?:password|secret|apikey|access[_-]?token|documentcontent|fulltext|rawdocument)[^"\\]*"\s*:', re.I)
        for path in EVENTS_DIR.glob("*.schema.json"):
            self.assertIsNone(key_pattern.search(path.read_text(encoding="utf-8")), path.name)

    def _base_event(self, event_type: str, payload: dict[str, Any]) -> dict[str, Any]:
        return {
            "eventId": self.ids["event"],
            "eventType": event_type,
            "occurredAt": "2026-08-12T08:00:00Z",
            "producer": "ragforge-server",
            "correlationId": self.ids["correlation"],
            "causationId": self.ids["causation"],
            "spaceId": self.ids["space"],
            "aggregateId": self.ids["job"],
            "payload": payload,
        }

    def _requested_event(self) -> dict[str, Any]:
        return self._base_event(
            "ingestion.job.requested.v1",
            {
                "jobId": self.ids["job"],
                "sourceId": self.ids["source"],
                "documentRevisionId": self.ids["revision"],
                "pipelineVersionId": self.ids["pipeline"],
                "attemptId": self.ids["attempt"],
                "operation": "DOCUMENT_UPSERT",
                "sourceVersion": "git:abc123",
                "priority": 50,
                "artifactRef": {
                    "artifactId": self.ids["artifact"],
                    "mediaType": "text/markdown",
                    "byteLength": 1024,
                    "sha256": "a" * 64,
                    "storageUri": f"spaces/{self.ids['space']}/sources/{self.ids['source']}/revisions/{self.ids['revision']}/artifacts/{self.ids['artifact']}/sha256/{'a' * 64}",
                },
            },
        )

    def _completed_event(self) -> dict[str, Any]:
        return self._base_event(
            "ingestion.job.completed.v1",
            {
                "jobId": self.ids["job"],
                "sourceId": self.ids["source"],
                "documentRevisionId": self.ids["revision"],
                "pipelineVersionId": self.ids["pipeline"],
                "attemptId": self.ids["attempt"],
                "status": "SUCCEEDED",
                "result": {
                    "parsedArtifactId": self.ids["parsed"],
                    "indexVersionId": self.ids["index"],
                    "chunkCount": 8,
                    "embeddingCount": 8,
                },
                "failure": None,
            },
        )


if __name__ == "__main__":
    unittest.main()
