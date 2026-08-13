"""Phase 3 fault-injection gate for checkpoint and active-pointer safety."""

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


class Phase3FaultCheckpointTest(unittest.TestCase):
    def test_worker_faults_and_database_pointer_guard_pass(self) -> None:
        subprocess.run(
            [
                maven_command(),
                "--batch-mode",
                "--no-transfer-progress",
                "-pl",
                "apps/ingestion-worker",
                "-Dtest=CheckpointFailureBoundaryTest,IngestionJobConsumerTest",
                "test",
            ],
            cwd=ROOT,
            env=java_environment(),
            check=True,
        )
        subprocess.run(
            [
                maven_command(),
                "--batch-mode",
                "--no-transfer-progress",
                "-pl",
                "apps/server",
                "-Dtest=Phase3IngestionPersistenceIntegrationTest",
                "test",
            ],
            cwd=ROOT,
            env=java_environment(),
            check=True,
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
