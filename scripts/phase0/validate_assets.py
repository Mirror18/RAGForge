#!/usr/bin/env python3
"""Validate Phase 0 corpus files, manifests, references, and hashes."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path
from typing import Any


MIN_DOCUMENTS = 30
MAX_DOCUMENTS = 50
MIN_QUESTIONS = 30
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
REQUIRED_DOCUMENT_CATEGORIES = {
    "markdown",
    "yaml",
    "wikilink",
    "headings",
    "table",
    "code",
    "callout",
    "long_text",
    "pdf",
    "docx",
    "xlsx",
    "scan_placeholder",
    "same_name",
    "conflict",
    "no_answer",
    "unicode",
    "prompt_injection",
    "space_boundary",
}


class ValidationError(Exception):
    pass


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValidationError(f"cannot load JSON {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise ValidationError(f"JSON root must be an object: {path}")
    return value


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def require_keys(value: dict[str, Any], keys: set[str], context: str) -> None:
    missing = sorted(keys - value.keys())
    if missing:
        raise ValidationError(f"{context} missing required fields: {', '.join(missing)}")


def validate_document_manifest(root: Path) -> tuple[dict[str, Any], dict[str, dict[str, Any]]]:
    manifest_path = root / "documents" / "document_manifest.json"
    manifest = load_json(manifest_path)
    require_keys(manifest, {"schema_version", "dataset_version", "generator_version", "seed", "immutable_id", "documents"}, "document manifest")
    if not isinstance(manifest["documents"], list):
        raise ValidationError("document manifest documents must be a list")
    if not MIN_DOCUMENTS <= len(manifest["documents"]) <= MAX_DOCUMENTS:
        raise ValidationError(f"document count must be {MIN_DOCUMENTS}-{MAX_DOCUMENTS}, got {len(manifest['documents'])}")
    if not SHA256_PATTERN.fullmatch(manifest["immutable_id"]):
        raise ValidationError("document manifest immutable_id must be a SHA-256 hex digest")
    seen_ids: set[str] = set()
    seen_paths: set[str] = set()
    categories: set[str] = set()
    by_id: dict[str, dict[str, Any]] = {}
    for index, entry in enumerate(manifest["documents"]):
        context = f"document[{index}]"
        require_keys(entry, {"document_id", "relative_path", "format", "title", "space_id", "categories", "provenance", "license", "content_sha256", "byte_size"}, context)
        document_id = entry["document_id"]
        if document_id in seen_ids:
            raise ValidationError(f"duplicate document_id: {document_id}")
        seen_ids.add(document_id)
        relative_path = entry["relative_path"]
        if relative_path in seen_paths:
            raise ValidationError(f"duplicate document path: {relative_path}")
        seen_paths.add(relative_path)
        if not relative_path.startswith("documents/corpus/") or ".." in Path(relative_path).parts:
            raise ValidationError(f"document path escapes corpus: {relative_path}")
        path = root / Path(relative_path)
        if not path.is_file():
            raise ValidationError(f"document file does not exist: {relative_path}")
        actual_hash = sha256_file(path)
        if entry["content_sha256"] != actual_hash:
            raise ValidationError(f"hash mismatch for {document_id}: expected {entry['content_sha256']}, got {actual_hash}")
        if entry["byte_size"] != path.stat().st_size:
            raise ValidationError(f"byte_size mismatch for {document_id}")
        if not isinstance(entry["categories"], list) or not entry["categories"]:
            raise ValidationError(f"{context} categories must be non-empty")
        categories.update(entry["categories"])
        require_keys(entry["provenance"], {"kind", "source", "source_id", "copied_third_party_text", "limitation"}, f"{context}.provenance")
        require_keys(entry["license"], {"id", "url", "status"}, f"{context}.license")
        if entry["provenance"]["kind"] != "synthetic" or entry["provenance"]["copied_third_party_text"] is not False:
            raise ValidationError(f"{context} is not marked synthetic-only")
        if entry["license"]["id"] != "CC0-1.0":
            raise ValidationError(f"{context} must use CC0-1.0")
        if not SHA256_PATTERN.fullmatch(entry["content_sha256"]):
            raise ValidationError(f"{context} content_sha256 is invalid")
        by_id[document_id] = entry
    missing_categories = sorted(REQUIRED_DOCUMENT_CATEGORIES - categories)
    if missing_categories:
        raise ValidationError(f"corpus is missing required categories: {', '.join(missing_categories)}")
    return manifest, by_id


def validate_question_manifest(root: Path, documents: dict[str, dict[str, Any]]) -> dict[str, Any]:
    manifest_path = root / "evaluation" / "question_manifest.json"
    manifest = load_json(manifest_path)
    require_keys(manifest, {"schema_version", "dataset_version", "generator_version", "seed", "immutable_id", "questions"}, "question manifest")
    if not isinstance(manifest["questions"], list) or len(manifest["questions"]) < MIN_QUESTIONS:
        raise ValidationError(f"question count must be at least {MIN_QUESTIONS}")
    if not SHA256_PATTERN.fullmatch(manifest["immutable_id"]):
        raise ValidationError("question manifest immutable_id must be a SHA-256 hex digest")
    seen_ids: set[str] = set()
    for index, entry in enumerate(manifest["questions"]):
        context = f"question[{index}]"
        require_keys(
            entry,
            {
                "case_id",
                "dataset_version",
                "space_fixture",
                "question",
                "question_type",
                "language",
                "difficulty",
                "answerability",
                "expected_abstention",
                "expected_document_ids",
                "gold_references",
                "reference_answer",
                "answer_notes",
                "required_citations",
                "forbidden_citations",
                "tags",
                "annotation",
            },
            context,
        )
        if entry["case_id"] in seen_ids:
            raise ValidationError(f"duplicate case_id: {entry['case_id']}")
        seen_ids.add(entry["case_id"])
        for field in ("expected_document_ids", "required_citations", "forbidden_citations"):
            if not isinstance(entry[field], list):
                raise ValidationError(f"{context}.{field} must be a list")
            for document_id in entry[field]:
                if document_id not in documents:
                    raise ValidationError(f"{context}.{field} references missing document: {document_id}")
        if not isinstance(entry["gold_references"], list) or not entry["gold_references"]:
            raise ValidationError(f"{context}.gold_references must contain at least one reference")
        gold_ids: set[str] = set()
        for ref_index, ref in enumerate(entry["gold_references"]):
            ref_context = f"{context}.gold_references[{ref_index}]"
            require_keys(ref, {"document_id", "relevance", "locator", "claim", "access"}, ref_context)
            document_id = ref["document_id"]
            if document_id not in documents:
                raise ValidationError(f"{ref_context} references missing document: {document_id}")
            if document_id in gold_ids:
                raise ValidationError(f"{ref_context} duplicates document reference: {document_id}")
            gold_ids.add(document_id)
            if ref["relevance"] not in (1, 2, 3):
                raise ValidationError(f"{ref_context}.relevance must be 1, 2, or 3")
            if ref["access"] not in ("allowed", "forbidden_cross_space", "not_ocr_evidence"):
                raise ValidationError(f"{ref_context}.access is invalid")
        if entry["answerability"] not in ("ANSWERABLE", "UNANSWERABLE", "CONFLICTING"):
            raise ValidationError(f"{context}.answerability is invalid")
        if entry["answerability"] == "ANSWERABLE" and entry["expected_abstention"]:
            raise ValidationError(f"{context} answerable case cannot expect abstention")
        if entry["answerability"] != "ANSWERABLE" and not entry["expected_abstention"]:
            raise ValidationError(f"{context} non-answerable case must expect abstention")
        if not isinstance(entry["answer_notes"], str) or not entry["answer_notes"].strip():
            raise ValidationError(f"{context}.answer_notes must be non-empty")
        for document_id in entry["required_citations"]:
            if document_id not in gold_ids:
                raise ValidationError(f"{context}.required_citations must be gold references: {document_id}")
        if set(entry["required_citations"]) & set(entry["forbidden_citations"]):
            raise ValidationError(f"{context} has citation in both required and forbidden")
        for ref in entry["gold_references"]:
            if ref["access"] == "allowed" and ref["document_id"] in documents:
                if documents[ref["document_id"]]["space_id"] != entry["space_fixture"] and ref["document_id"] not in entry["forbidden_citations"]:
                    raise ValidationError(f"{context} allowed reference crosses space boundary")
    return manifest


def validate_index(root: Path, document_manifest: dict[str, Any], question_manifest: dict[str, Any]) -> dict[str, Any]:
    index_path = root / "evaluation" / "dataset_index.json"
    index = load_json(index_path)
    require_keys(index, {"schema_version", "dataset_version", "generator_version", "seed", "immutable_id", "corpus_document_count", "question_count", "document_manifest", "question_manifest", "document_manifest_sha256", "question_manifest_sha256", "scan_placeholder_policy"}, "dataset index")
    if index["immutable_id"] != document_manifest["immutable_id"] or index["immutable_id"] != question_manifest["immutable_id"]:
        raise ValidationError("immutable_id differs between manifests and dataset index")
    document_manifest_path = root / "documents" / "document_manifest.json"
    question_manifest_path = root / "evaluation" / "question_manifest.json"
    if index["document_manifest_sha256"] != sha256_file(document_manifest_path):
        raise ValidationError("document manifest hash mismatch in dataset index")
    if index["question_manifest_sha256"] != sha256_file(question_manifest_path):
        raise ValidationError("question manifest hash mismatch in dataset index")
    if index["corpus_document_count"] != len(document_manifest["documents"]):
        raise ValidationError("dataset index document count mismatch")
    if index["question_count"] != len(question_manifest["questions"]):
        raise ValidationError("dataset index question count mismatch")
    return index


def validate(root: Path) -> dict[str, Any]:
    document_manifest, documents = validate_document_manifest(root)
    question_manifest = validate_question_manifest(root, documents)
    index = validate_index(root, document_manifest, question_manifest)
    return {
        "status": "valid",
        "immutable_id": index["immutable_id"],
        "document_count": len(document_manifest["documents"]),
        "question_count": len(question_manifest["questions"]),
        "document_manifest_sha256": index["document_manifest_sha256"],
        "question_manifest_sha256": index["question_manifest_sha256"],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parents[2] / "fixtures",
        help="fixtures root containing documents/ and evaluation/",
    )
    args = parser.parse_args()
    result = validate(args.root.resolve())
    print(json.dumps(result, ensure_ascii=False, sort_keys=True, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
