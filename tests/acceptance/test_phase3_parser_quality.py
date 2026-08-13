"""Phase 3 parser corpus and OCR acceptance gate."""

from __future__ import annotations

import subprocess
import os
import json
import shutil
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


def java_environment() -> dict[str, str]:
    environment = os.environ.copy()
    if os.name == "nt":
        jdk = Path("C:/Program Files/Java/jdk-21")
        if jdk.exists():
            environment["JAVA_HOME"] = str(jdk)
        environment["DOCKER_HOST"] = "npipe:////./pipe/dockerDesktopLinuxEngine"
    return environment


def maven_command() -> str:
    return "mvn.cmd" if os.name == "nt" and shutil.which("mvn.cmd") else "mvn"


class Phase3ParserQualityTest(unittest.TestCase):
    def test_native_corpus_and_ocr_boundaries_pass_real_java_gate(self) -> None:
        manifest = ROOT / "tests" / "fixtures" / "phase3" / "parser-corpus.json"
        self.assertTrue(manifest.is_file())
        self.assertEqual(len(json.loads(manifest.read_text(encoding="utf-8"))["fixtures"]), 8)
        subprocess.run(
            [
                maven_command(),
                "--batch-mode",
                "--no-transfer-progress",
                "-pl",
                "apps/ingestion-worker",
                "-Dtest=NativeDocumentParserTest",
                "test",
            ],
            cwd=ROOT,
            env=java_environment(),
            check=True,
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
