"""Phase 3 connector acceptance gate.

The Python gate validates the immutable manifest and then runs the real Java
connector test.  The same command is used on Windows development and the
Ubuntu GitHub runner; only the temporary filesystem separator differs.
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
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


class Phase3CrossPlatformConnectorTest(unittest.TestCase):
    def test_manifest_is_stable_and_real_connector_gate_passes(self) -> None:
        manifest = json.loads(
            (ROOT / "tests" / "fixtures" / "phase3" / "connector-manifest.json").read_text(
                encoding="utf-8"
            )
        )
        self.assertEqual(manifest["fixtureVersion"], "phase3-connector-v1")
        self.assertEqual(len(manifest["baseline"]), 5)
        self.assertEqual(len(set(item["path"] for item in manifest["baseline"])), 5)
        self.assertEqual(len(manifest["baselineStableObjectIds"]), 5)
        subprocess.run(
            [
                maven_command(),
                "--batch-mode",
                "--no-transfer-progress",
                "-pl",
                "apps/ingestion-worker",
                "-Dtest=CrossPlatformConnectorManifestTest",
                "test",
            ],
            cwd=ROOT,
            env=java_environment(),
            check=True,
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
