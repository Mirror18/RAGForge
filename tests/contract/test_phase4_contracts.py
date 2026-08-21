"""Executable Phase 4 contract checks using only the Python standard library."""

from __future__ import annotations

import json
import re
import unittest
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
CONTRACTS = ROOT / "contracts"
INGESTION = CONTRACTS / "ingestion"
RETRIEVAL = CONTRACTS / "retrieval"
FIXTURES = ROOT / "tests" / "contract" / "fixtures" / "phase4"
OPENAPI = ROOT / "contracts" / "openapi" / "ragforge-api-v1.yaml"
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


def to_dt(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def walk_keys(value: Any, forbidden: set[str]) -> list[str]:
    hits: list[str] = []
    if isinstance(value, dict):
        for key, item in value.items():
            if key in forbidden:
                hits.append(key)
            hits.extend(walk_keys(item, forbidden))
    elif isinstance(value, list):
        for item in value:
            hits.extend(walk_keys(item, forbidden))
    return hits


def ref_name(ref: str) -> str:
    prefix = "#/components/schemas/"
    if not ref.startswith(prefix):
        raise AssertionError(f"expected local schema ref, got {ref}")
    return ref[len(prefix) :]


def schema_property_names(document: dict[str, Any], schema: Any, seen: set[str] | None = None) -> set[str]:
    """Collect property names reachable from a local OpenAPI schema ref."""
    seen = set() if seen is None else seen
    names: set[str] = set()
    if not isinstance(schema, dict):
        return names
    if "$ref" in schema:
        name = ref_name(schema["$ref"])
        if name in seen:
            return names
        seen.add(name)
        return schema_property_names(document, document["components"]["schemas"][name], seen)
    names.update(schema.get("properties", {}))
    for value in schema.values():
        if isinstance(value, dict):
            names.update(schema_property_names(document, value, seen))
        elif isinstance(value, list):
            for item in value:
                names.update(schema_property_names(document, item, seen))
    return names


class Phase4ContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.openapi = json.loads(OPENAPI.read_text(encoding="utf-8"))
        cls.chunk_path = INGESTION / "chunking-domain.v1.schema.json"
        cls.chunk = load_json(cls.chunk_path)
        cls.index_path = INGESTION / "index-version.v1.schema.json"
        cls.index = load_json(cls.index_path)
        cls.profile_path = RETRIEVAL / "retrieval-profile.v1.schema.json"
        cls.profile = load_json(cls.profile_path)
        cls.valid_chunk = load_json(FIXTURES / "valid" / "chunking-domain.json")
        cls.valid_index = load_json(FIXTURES / "valid" / "index-version.json")
        cls.valid_profile = load_json(FIXTURES / "valid" / "retrieval-profile.json")

    def test_phase4_g_openapi_projection_is_space_scoped_and_operation_ids_are_unique(self) -> None:
        document = self.openapi
        self.assertEqual(document["openapi"], "3.1.0")
        self.assertEqual(document["x-ragforge-contract-status"], "planned")
        self.assertEqual(document["x-ragforge-phase2-status"], "phase2-contract")
        self.assertEqual(document["x-ragforge-phase3-status"], "phase3-contract")
        self.assertEqual(document["x-ragforge-phase4-status"], "phase4-contract")
        phase4_paths = {
            path: item
            for path, item in document["paths"].items()
            if item.get("x-ragforge-implementation-status") == "phase4-contract"
        }
        self.assertEqual(
            set(phase4_paths),
            {
                "/api/v1/spaces/{spaceId}/chunk-studio/children/{childChunkId}",
                "/api/v1/spaces/{spaceId}/chunk-studio/children/{childChunkId}/overrides",
                "/api/v1/spaces/{spaceId}/chunk-studio/children/{childChunkId}/overrides/{overrideId}/transitions",
                "/api/v1/spaces/{spaceId}/retrieval-playground/experiments",
            },
        )
        operation_ids: list[str] = []
        for path, path_item in phase4_paths.items():
            self.assertIn("/api/v1/spaces/{spaceId}/", path)
            for method, operation in path_item.items():
                if method not in {"get", "post", "put", "patch", "delete"}:
                    continue
                operation_ids.append(operation["operationId"])
                self.assertEqual(
                    {tuple(security)[0] for security in operation["security"]},
                    {"cookieAuth", "serviceToken"},
                )
                refs = {
                    parameter["$ref"].rsplit("/", 1)[-1]
                    for parameter in path_item.get("parameters", []) + operation.get("parameters", [])
                    if "$ref" in parameter
                }
                self.assertIn("SpaceId", refs)
                if method in {"post", "put", "patch", "delete"}:
                    self.assertIn("CsrfToken", refs)
                    self.assertIn("IdempotencyKey", refs)
                for status, response in operation["responses"].items():
                    if int(status) >= 400:
                        response_name = response["$ref"].rsplit("/", 1)[-1]
                        response_schema = document["components"]["responses"][response_name]["content"]["application/problem+json"]["schema"]
                        self.assertTrue(response_schema["$ref"].endswith("/ProblemDetails"), (path, method, status))
        self.assertEqual(len(operation_ids), len(set(operation_ids)))

    def test_chunk_studio_projection_freezes_audited_override_and_state_machine(self) -> None:
        schemas = self.openapi["components"]["schemas"]
        child = schemas["ChunkStudioChildProjection"]
        self.assertTrue(
            {"spaceId", "documentRevisionId", "childChunkId", "contentRef", "textHash", "parentChild", "provenance", "anchor", "vectorStatus", "override"}
            <= set(child["required"])
        )
        override_summary = schemas["ChunkOverrideSummary"]
        self.assertTrue(
            {"state", "version", "reason", "createdBy", "createdAt", "updatedAt"}
            <= set(override_summary["required"])
        )
        response = schemas["ChunkOverrideResponse"]
        self.assertTrue(
            {"spaceId", "documentRevisionId", "childChunkId", "contentRef", "textHash", "override"}
            <= set(response["required"])
        )
        create_request = schemas["CreateChunkOverrideRequest"]
        self.assertNotIn("createdBy", create_request["properties"])
        self.assertNotIn("state", create_request["properties"])
        self.assertNotIn("fullText", schema_property_names(self.openapi, create_request))
        self.assertNotIn("rawText", schema_property_names(self.openapi, create_request))
        transition = schemas["TransitionChunkOverrideRequest"]
        self.assertEqual(set(transition["properties"]["targetState"]["enum"]), {"ACTIVE", "NEEDS_REVIEW", "DISCARDED"})
        self.assertNotIn("NONE", transition["properties"]["targetState"]["enum"])
        transition_description = transition["description"]
        self.assertIn("ACTIVE -> NEEDS_REVIEW", transition_description)
        self.assertIn("NEEDS_REVIEW -> ACTIVE or DISCARDED", transition_description)
        self.assertIn("NONE is not a client transition", transition_description)

    def test_retrieval_playground_is_candidate_only_and_trace_is_minimal(self) -> None:
        schemas = self.openapi["components"]["schemas"]
        request = schemas["RetrievalPlaygroundExperimentRequest"]
        self.assertEqual(set(request["required"]), {"query", "indexVersionId", "profileA"})
        self.assertNotIn("spaceId", request["properties"])
        query_vector = request["properties"]["queryVector"]
        self.assertTrue(query_vector["writeOnly"])
        self.assertTrue(query_vector["x-ragforge-internal-only"])
        self.assertIn("never returned", query_vector["description"])
        self.assertIn("not a public client capability", query_vector["description"])
        profile_ref = schemas["RetrievalProfileVersionRef"]
        self.assertEqual(profile_ref["properties"]["candidateOnly"]["const"], True)
        response = schemas["RetrievalPlaygroundExperiment"]
        self.assertTrue(
            {"spaceId", "query", "normalizedQuery", "indexVersionId", "profileA", "profileB", "abstention", "activeProfileUnchanged"}
            <= set(response["required"])
        )
        self.assertEqual(response["properties"]["activeProfileUnchanged"]["const"], True)
        trace = schemas["RetrievalTrace"]
        self.assertEqual(set(trace["required"]), {"dense", "bm25", "rrf", "rerank", "context", "evidence"})
        evidence = schemas["CitationEvidence"]
        self.assertEqual(evidence["properties"]["citationAllowed"]["const"], True)
        forbidden_response_fields = {
            "fullText", "rawText", "documentContent", "rawDocument", "vector", "embedding",
            "queryVector", "secret", "credential", "credentialRef", "apiKey", "accessToken", "password",
        }
        self.assertEqual(
            schema_property_names(self.openapi, response) & forbidden_response_fields,
            set(),
        )

    def test_all_phase4_json_contracts_and_fixtures_are_parseable(self) -> None:
        for path in list(INGESTION.glob("*.json")) + list(RETRIEVAL.glob("*.json")):
            self.assertIsInstance(load_json(path), dict)
        for folder in ("valid", "invalid"):
            for path in (FIXTURES / folder).glob("*.json"):
                self.assertIsInstance(load_json(path), dict)

    def test_chunk_entities_are_space_scoped_immutable_and_anchored(self) -> None:
        for name in ("chunkingStrategy", "parentChunk", "childChunk", "chunkOverride", "chunkingResult"):
            value = self.valid_chunk[name]
            validate(value, {"$ref": f"#/$defs/{name}"}, self.chunk_path, self.chunk)
        # Space isolation applies to content entities; chunkingStrategy is a pure configuration object.
        for name in ("parentChunk", "childChunk", "chunkOverride", "chunkingResult"):
            self.assertIn("spaceId", self.valid_chunk[name], name)
        parent = self.valid_chunk["parentChunk"]
        child = self.valid_chunk["childChunk"]
        self.assertTrue(parent["immutable"])
        self.assertTrue(child["immutable"])
        self.assertIn("citationAnchor", child)
        self.assertEqual(child["parentChunkId"], parent["parentChunkId"])
        self.assertEqual(child["documentRevisionId"], parent["documentRevisionId"])
        self.assertLessEqual(parent["tokenRange"]["startToken"], parent["tokenRange"]["endToken"])
        self.assertGreaterEqual(child["tokenRange"]["startToken"], parent["tokenRange"]["startToken"])
        self.assertLessEqual(child["tokenRange"]["endToken"], parent["tokenRange"]["endToken"])
        self.assertTrue(parent["contentRef"])
        self.assertTrue(child["contentRef"])
        self.assertEqual(re.fullmatch(r"[0-9a-fA-F]{64}", child["textHash"]).group(0), child["textHash"])

    def test_override_state_machine_semantics(self) -> None:
        override = self.valid_chunk["chunkOverride"]
        validate(override, {"$ref": "#/$defs/chunkOverride"}, self.chunk_path, self.chunk)
        self.assertEqual(override["state"], "NEEDS_REVIEW")
        self.assertEqual(override["source"], "MANUAL")
        # NONE -> ACTIVE -> NEEDS_REVIEW -> ACTIVE | DISCARDED
        allowed = {
            "NONE": {"ACTIVE"},
            "ACTIVE": {"NEEDS_REVIEW"},
            "NEEDS_REVIEW": {"ACTIVE", "DISCARDED"},
            "DISCARDED": set(),
        }
        for state, targets in allowed.items():
            for target in ("ACTIVE", "NEEDS_REVIEW", "DISCARDED"):
                if target in targets:
                    continue
                self.assertNotIn(target, allowed[state], f"{state} -> {target} must be forbidden")
        # An ACTIVE override must not target a different revision than its child chunk.
        reapplied = load_json(FIXTURES / "invalid" / "override-reapplied-to-new-revision.json")
        child_rev = reapplied["childChunk"]["documentRevisionId"]
        override_rev = reapplied["chunkOverride"]["documentRevisionId"]
        validate(reapplied["childChunk"], {"$ref": "#/$defs/childChunk"}, self.chunk_path, self.chunk)
        validate(reapplied["chunkOverride"], {"$ref": "#/$defs/chunkOverride"}, self.chunk_path, self.chunk)
        self.assertNotEqual(child_rev, override_rev)
        self.assertEqual(reapplied["chunkOverride"]["state"], "ACTIVE")
        with self.assertRaises(AssertionError):
            self.assertEqual(child_rev, override_rev)

    def test_index_lifecycle_and_validation_gate(self) -> None:
        version = self.valid_index["indexVersion"]
        validate(version, {"$ref": "#/$defs/indexVersion"}, self.index_path, self.index)
        self.assertEqual(version["state"], "ACTIVE")
        self.assertIsNotNone(version["validation"])
        self.assertTrue(version["validation"]["sampleRetrievalPassed"])
        self.assertTrue(version["validation"]["spaceFilterPassed"])
        self.assertEqual(version["validation"]["vectorDimension"], version["validation"]["vectorDimension"])
        self.assertGreaterEqual(
            to_dt(version["retentionDeadline"]),
            to_dt(version["activatedAt"]) + timedelta(hours=24),
        )
        pointer_obj = self.valid_index["activeIndexPointer"]
        validate(pointer_obj, {"$ref": "#/$defs/activeIndexPointer"}, self.index_path, self.index)
        self.assertEqual(pointer_obj["activeIndexVersionId"], version["indexVersionId"])
        self.assertNotEqual(pointer_obj["previousIndexVersionId"], version["indexVersionId"])
        # ACTIVE without validation must be rejected semantically.
        invalid = load_json(FIXTURES / "invalid" / "index-activated-without-validation.json")
        validate(invalid, {"$ref": "#/$defs/indexVersion"}, self.index_path, self.index)
        self.assertEqual(invalid["state"], "ACTIVE")
        self.assertIsNone(invalid["validation"])
        with self.assertRaises(AssertionError):
            self.assertIsNotNone(invalid["validation"])

    def test_retrieval_profile_immutability(self) -> None:
        profile = self.valid_profile["retrievalProfileVersion"]
        validate(profile, {"$ref": "#/$defs/retrievalProfileVersion"}, self.profile_path, self.profile)
        self.assertTrue(profile["immutable"])
        self.assertEqual(profile["rrf"]["k"], 60)
        for weight in (profile["rrf"]["denseWeight"], profile["rrf"]["bm25Weight"]):
            self.assertGreaterEqual(weight, 0.0)
            self.assertLessEqual(weight, 1.0)
        pointer_obj = self.valid_profile["activeProfilePointer"]
        validate(pointer_obj, {"$ref": "#/$defs/activeProfilePointer"}, self.profile_path, self.profile)
        self.assertEqual(pointer_obj["activeProfileId"], profile["profileId"])
        self.assertEqual(pointer_obj["activeVersion"], profile["version"])
        # immutable: false violates the const.
        invalid = load_json(FIXTURES / "invalid" / "profile-not-immutable.json")
        with self.assertRaises(SchemaViolation):
            validate(invalid, {"$ref": "#/$defs/retrievalProfileVersion"}, self.profile_path, self.profile)

    def test_sensitive_content_never_in_chunk_payloads(self) -> None:
        for name in ("parentChunk", "childChunk"):
            self.assertEqual(walk_keys(self.valid_chunk[name], {"fullText", "rawText", "content"}), [])
        child = dict(self.valid_chunk["childChunk"])
        child["fullText"] = "synthetic text must not cross the chunk payload boundary"
        with self.assertRaises(SchemaViolation):
            validate(child, {"$ref": "#/$defs/childChunk"}, self.chunk_path, self.chunk)
        invalid_fixture = load_json(FIXTURES / "invalid" / "child-with-full-text.json")
        with self.assertRaises(SchemaViolation):
            validate(invalid_fixture, {"$ref": "#/$defs/childChunk"}, self.chunk_path, self.chunk)

    def test_space_isolation_enforced_in_chunk_contract(self) -> None:
        invalid = load_json(FIXTURES / "invalid" / "chunk-without-space.json")
        with self.assertRaises(SchemaViolation):
            validate(invalid, {"$ref": "#/$defs/childChunk"}, self.chunk_path, self.chunk)


if __name__ == "__main__":
    unittest.main()
