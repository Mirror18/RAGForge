#!/usr/bin/env python3
"""Executable regression tests for Markdown traversal boundaries."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from check_markdown_links import SKIP_DIR_NAMES, iter_markdown


class MarkdownTraversalTests(unittest.TestCase):
    def test_ignored_and_generated_directories_are_pruned(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            visible = root / "docs" / "visible.md"
            visible.parent.mkdir(parents=True)
            visible.write_text("# visible\n", encoding="utf-8")
            for directory_name in (".git", "node_modules", "target", "dist", "generated", "tmp"):
                hidden = root / directory_name / "third-party.md"
                hidden.parent.mkdir(parents=True)
                hidden.write_text("[missing](does-not-exist.md)\n", encoding="utf-8")

            discovered = {path.relative_to(root).as_posix() for path in iter_markdown(root)}

            self.assertEqual(discovered, {"docs/visible.md"})
            self.assertTrue({"node_modules", "target", "dist", ".git"}.issubset(SKIP_DIR_NAMES))


if __name__ == "__main__":
    unittest.main()
