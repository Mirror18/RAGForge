"""Phase 3 duplicate-delivery performance and side-effect gate."""

from __future__ import annotations

import subprocess
import os
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


class Phase3IdempotencyTest(unittest.TestCase):
    def test_twenty_concurrent_deliveries_have_one_side_effect(self) -> None:
        subprocess.run(
            [
                maven_command(),
                "--batch-mode",
                "--no-transfer-progress",
                "-pl",
                "apps/ingestion-worker",
                "-Dtest=IdempotencyConcurrencyIntegrationTest",
                "test",
            ],
            cwd=ROOT,
            env=java_environment(),
            check=True,
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
