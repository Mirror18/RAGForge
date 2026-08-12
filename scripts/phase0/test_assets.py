#!/usr/bin/env python3
"""Regression tests for Phase 0 asset generation and validation."""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from generate_assets import generate
from validate_assets import ValidationError, validate


class Phase0AssetTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="ragforge-phase0-assets-")
        self.root = Path(self.temp.name) / "fixtures"
        generate(self.root)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def test_generated_assets_validate(self) -> None:
        result = validate(self.root)
        self.assertEqual(result["document_count"], 36)
        self.assertEqual(result["question_count"], 33)

    def test_tampered_document_is_rejected(self) -> None:
        path = self.root / "documents/corpus/markdown/guide.md"
        path.write_bytes(path.read_bytes() + b"tampered\n")
        with self.assertRaisesRegex(ValidationError, "hash mismatch"):
            validate(self.root)

    def test_unknown_reference_is_rejected(self) -> None:
        path = self.root / "evaluation/question_manifest.json"
        manifest = json.loads(path.read_text(encoding="utf-8"))
        manifest["questions"][0]["expected_document_ids"] = ["doc-does-not-exist"]
        path.write_text(json.dumps(manifest, ensure_ascii=False, sort_keys=True, indent=2) + "\n", encoding="utf-8", newline="\n")
        with self.assertRaisesRegex(ValidationError, "references missing document"):
            validate(self.root)


if __name__ == "__main__":
    unittest.main()
