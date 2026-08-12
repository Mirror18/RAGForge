"""Executable Phase 3 contract checks using only the Python standard library."""

from __future__ import annotations

import copy
import json
import re
import unittest
from datetime import datetime
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
CONTRACTS = ROOT / "contracts"
INGESTION = CONTRACTS / "ingestion"
EVENTS = CONTRACTS / "events"
FIXTURES = ROOT / "tests" / "contract" / "fixtures" / "phase3"
UUID_V7 = re.compile(
    r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-7[0-9a-fA-F]{3}-"
    r"[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"
)


class SchemaViolation(AssertionError):
    pass


def load_json(path: Path) -> dict[str, Any]:
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
    return {
        "object": isinstance(value, dict),
        "array": isinstance(value, list),
        "string": isinstance(value, str),
        "integer": isinstance(value, int) and not isinstance(value, bool),
        "number": isinstance(value, (int, float)) and not isinstance(value, bool),
        "boolean": isinstance(value, bool),
        "null": value is None,
    }.get(expected, True)


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
    if "oneOf" in schema:
        matches = sum(_valid(value, sub, schema_path, root, path) for sub in schema["oneOf"])
        if matches != 1:
            raise SchemaViolation(f"{path}: expected one oneOf branch, matched {matches}")
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


def normalize_path(path: str) -> str:
    if "\\" in path or path.startswith("/") or re.match(r"^[A-Za-z]:", path):
        raise ValueError(path)
    parts = path.split("/")
    if any(part in {"", ".", ".."} for part in parts):
        raise ValueError(path)
    return "/".join(parts)


class Phase3ContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.domain_path = INGESTION / "ingestion-domain.v1.schema.json"
        cls.domain = load_json(cls.domain_path)
        cls.status_path = EVENTS / "ingestion.job.status.changed.v1.schema.json"
        cls.status = load_json(cls.status_path)
        cls.valid_domain = load_json(FIXTURES / "valid" / "domain-resources.json")
        cls.valid_events = load_json(FIXTURES / "valid" / "status-events.json")["events"]

    def test_all_phase3_json_contracts_and_fixtures_are_parseable(self) -> None:
        for path in list(INGESTION.glob("*.json")) + [self.status_path]:
            self.assertIsInstance(load_json(path), dict)
        for path in (FIXTURES / "valid").glob("*.json"):
            self.assertIsInstance(load_json(path), dict)
        for path in (FIXTURES / "invalid").glob("*.json"):
            self.assertIsInstance(load_json(path), dict)

    def test_versioned_domain_resources_validate_and_are_space_scoped(self) -> None:
        for name, value in self.valid_domain.items():
            validate(value, {"$ref": f"#/$defs/{name}"}, self.domain_path, self.domain)
            self.assertIn("spaceId", value)
            if name not in {"sourceChangeSet", "checkpointCommitResult"}:
                self.assertIn("version", value)
        self.assertEqual(self.valid_domain["source"]["readOnly"], True)
        self.assertEqual(self.valid_domain["documentRevision"]["immutable"], True)
        self.assertEqual(self.valid_domain["artifact"]["immutable"], True)

    def test_connector_contract_freezes_operations_change_kinds_and_path_rules(self) -> None:
        contract = load_json(INGESTION / "source-connector.v1.contract.json")
        self.assertEqual(set(contract["operations"]), {"discover", "fetch", "commitCheckpoint"})
        self.assertEqual(contract["changeKinds"]["canonical"], ["ADD", "MODIFY", "MOVE", "DELETE", "UNCHANGED"])
        changes = self.valid_domain["sourceChangeSet"]["changes"]
        self.assertEqual({change["kind"] for change in changes}, set(contract["changeKinds"]["canonical"]))
        for change in changes:
            self.assertEqual(change["canonicalSourcePath"], normalize_path(change["canonicalSourcePath"]))
        self.assertEqual(normalize_path("notes/a.md"), "notes/a.md")
        for invalid in (r"notes\\a.md", "/notes/a.md", "../notes/a.md", "notes//a.md"):
            with self.assertRaises(ValueError):
                normalize_path(invalid)
        self.assertIn("duplicateBasenameRule", contract["identity"])

    def test_status_events_validate_trace_identity_and_all_states(self) -> None:
        for event in self.valid_events:
            validate(event, self.status, self.status_path)
            for field in ("spaceId", "correlationId", "causationId"):
                self.assertIn(field, event)
        self.assertEqual(
            {event["payload"]["status"] for event in self.valid_events},
            {"REQUESTED", "COMPLETED", "FAILED", "RETRY_SCHEDULED", "DLQ"},
        )
        outbox = load_json(INGESTION / "outbox-worker.v1.contract.json")
        self.assertEqual(outbox["delivery"]["guarantee"], "AT_LEAST_ONCE")
        self.assertFalse(outbox["delivery"]["exactlyOnce"])
        self.assertEqual(outbox["retry"]["maxAttempts"], 20)

    def test_sensitive_fields_are_rejected_from_source_and_events(self) -> None:
        source_schema = {"$ref": "#/$defs/source"}
        invalid_source = load_json(FIXTURES / "invalid" / "source-with-credential-ref.json")
        with self.assertRaises(SchemaViolation):
            validate(invalid_source, source_schema, self.domain_path, self.domain)
        invalid_event = load_json(FIXTURES / "invalid" / "status-with-credential-ref.json")
        with self.assertRaises(SchemaViolation):
            validate(invalid_event, self.status, self.status_path)
        for event in self.valid_events:
            forbidden = copy.deepcopy(event)
            forbidden["payload"]["fullText"] = "synthetic text must not cross the boundary"
            with self.assertRaises(SchemaViolation):
                validate(forbidden, self.status, self.status_path)

    def test_checkpoint_failure_and_image_only_pdf_cannot_look_successful(self) -> None:
        result_schema = {"$ref": "#/$defs/checkpointCommitResult"}
        invalid_checkpoint = load_json(FIXTURES / "invalid" / "checkpoint-advanced-before-success.json")
        with self.assertRaises(SchemaViolation):
            validate(invalid_checkpoint, result_schema, self.domain_path, self.domain)
        report = load_json(FIXTURES / "invalid" / "image-only-pdf-faked-success.json")
        report_schema = {"$ref": "#/$defs/parseReport"}
        with self.assertRaises(SchemaViolation):
            validate(report, report_schema, self.domain_path, self.domain)
        failed = next(event for event in self.valid_events if event["payload"]["status"] == "FAILED")
        self.assertFalse(failed["payload"]["sideEffects"]["checkpointAdvanced"])
        dlq = next(event for event in self.valid_events if event["payload"]["status"] == "DLQ")
        self.assertEqual(dlq["payload"]["deliveryAttempt"], 20)

    def test_cross_space_reference_fixture_is_rejected(self) -> None:
        fixture = load_json(FIXTURES / "invalid" / "cross-space-document.json")
        source = fixture["source"]
        document = fixture["sourceDocument"]
        validate(source, {"$ref": "#/$defs/source"}, self.domain_path, self.domain)
        validate(document, {"$ref": "#/$defs/sourceDocument"}, self.domain_path, self.domain)
        self.assertNotEqual(source["spaceId"], document["spaceId"])
        self.assertNotEqual((source["spaceId"], source["sourceId"]), (document["spaceId"], document["sourceId"]))


if __name__ == "__main__":
    unittest.main()
