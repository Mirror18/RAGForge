from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock

from scripts.ci import preflight


class PreflightTests(unittest.TestCase):
    def setUp(self) -> None:
        self.jdk = tempfile.TemporaryDirectory()
        self.env = {"JAVA_HOME": self.jdk.name}

    def tearDown(self) -> None:
        self.jdk.cleanup()

    @staticmethod
    def successful_runner(command: tuple[str, ...]) -> Mock:
        result = Mock(returncode=0, stdout="tool 21.0.1", stderr="")
        if command[-2:] == ("java", "-version"):
            result.stderr = 'openjdk version "21.0.1"'
        if command[-1:] == ("--version",) and command[0].endswith("/mvn"):
            result.stdout = "Apache Maven 3.9.6\nJava version: 21.0.1, vendor: Eclipse Adoptium\nJava home: /jdk-21"
        return result

    @staticmethod
    def all_tools(executable: str) -> str:
        return "/" + executable

    def test_java_home_success(self) -> None:
        result = preflight._check_java_home(self.env)
        self.assertTrue(result.passed)
        self.assertEqual(result.result_code, "ok")

    def test_java_home_missing(self) -> None:
        result = preflight._check_java_home({})
        self.assertFalse(result.passed)
        self.assertEqual(result.result_code, "java_home_missing")

    def test_java_home_non_directory(self) -> None:
        missing = str(Path(self.jdk.name) / "missing")
        result = preflight._check_java_home({"JAVA_HOME": missing})
        self.assertFalse(result.passed)
        self.assertEqual(result.result_code, "java_home_not_directory")

    def test_node_path_success(self) -> None:
        result = preflight._check_command(
            "node", "node", ("node", "--version"), self.all_tools, self.successful_runner
        )
        self.assertTrue(result.passed)
        self.assertEqual(result.version, "21.0.1")

    def test_node_path_missing(self) -> None:
        result = preflight._check_command("node", "node", ("node", "--version"), lambda _: None, self.successful_runner)
        self.assertFalse(result.passed)
        self.assertEqual(result.result_code, "node_not_found")

    def test_maven_rejects_jdk_8_binding(self) -> None:
        def old_maven(_: tuple[str, ...]) -> Mock:
            return Mock(returncode=0, stdout="Apache Maven 3.9.6\nJava version: 1.8.0_472", stderr="")

        result = preflight._check_maven(self.all_tools, old_maven)
        self.assertFalse(result.passed)
        self.assertEqual(result.result_code, "maven_jdk_too_old")
        self.assertNotIn("JAVA_HOME=", result.message)

    def test_maven_requires_reported_java_version(self) -> None:
        def missing_java_version(_: tuple[str, ...]) -> Mock:
            return Mock(returncode=0, stdout="Apache Maven 3.9.6", stderr="")

        result = preflight._check_maven(self.all_tools, missing_java_version)
        self.assertFalse(result.passed)
        self.assertEqual(result.result_code, "maven_java_version_unknown")

    def test_maven_success_reports_jdk_binding(self) -> None:
        result = preflight._check_maven(self.all_tools, self.successful_runner)
        self.assertTrue(result.passed)
        self.assertEqual(result.result_code, "ok")
        self.assertEqual(result.version, "3.9.6")

    def test_docker_daemon_success(self) -> None:
        result = preflight._check_docker(self.all_tools, self.successful_runner)
        self.assertTrue(result.passed)
        self.assertEqual(result.version, "21.0.1")

    def test_docker_daemon_failure(self) -> None:
        def daemon_down(command: tuple[str, ...]) -> Mock:
            if command[-1:] == ("info",):
                return Mock(returncode=1, stdout="", stderr="permission denied; token=should-not-print")
            return Mock(returncode=0, stdout="Docker version 28.0.0", stderr="")

        result = preflight._check_docker(self.all_tools, daemon_down)
        self.assertFalse(result.passed)
        self.assertEqual(result.result_code, "docker_daemon_unavailable")
        self.assertNotIn("token", result.message)

    def test_report_has_stable_schema_and_no_command_output(self) -> None:
        report = preflight.run_preflight(self.env, self.all_tools, self.successful_runner)
        self.assertEqual(tuple(report), ("passed", "checks", "tool_versions", "remediation"))
        self.assertEqual(len(report["checks"]), 6)
        self.assertEqual(set(report["tool_versions"]), {"java", "maven", "node", "npm", "docker"})
        self.assertEqual(report["remediation"], [])
        self.assertNotIn("tool 21.0.1", str(report))

    def test_strict_exit_code_depends_on_required_checks(self) -> None:
        original = preflight.run_preflight
        try:
            preflight.run_preflight = lambda: {
                "passed": False,
                "checks": [],
                "tool_versions": {},
                "remediation": [],
            }
            self.assertEqual(preflight.main(["--json"]), 0)
            self.assertEqual(preflight.main(["--json", "--strict"]), 1)
        finally:
            preflight.run_preflight = original


if __name__ == "__main__":
    unittest.main()
