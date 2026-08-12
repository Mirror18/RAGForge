"""Repeatable Phase 1 API smoke test for a running local server.

The test uses only the Python standard library and synthetic data.  Start the
server against an isolated Compose project, then run:

    python tests/acceptance/test_phase1_api_smoke.py

Set ``RAGFORGE_API_BASE_URL`` when the server is not on the default port.
"""

from __future__ import annotations

import json
import os
import time
import unittest
import urllib.error
import urllib.request
import uuid
from http.cookiejar import CookieJar
from typing import Any


class ApiClient:
    def __init__(self, base_url: str) -> None:
        self.base_url = base_url.rstrip("/")
        self.cookies = CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.cookies)
        )

    def call(
        self,
        method: str,
        path: str,
        body: dict[str, Any] | None = None,
        headers: dict[str, str] | None = None,
    ) -> tuple[int, dict[str, Any], dict[str, str]]:
        request_headers = {"Accept": "application/json"}
        if body is not None:
            request_headers["Content-Type"] = "application/json"
        if headers:
            request_headers.update(headers)
        request = urllib.request.Request(
            self.base_url + path,
            data=json.dumps(body).encode("utf-8") if body is not None else None,
            headers=request_headers,
            method=method,
        )
        try:
            with self.opener.open(request, timeout=10) as response:
                status = response.status
                response_headers = {
                    key.lower(): value for key, value in response.headers.items()
                }
                payload = response.read().decode("utf-8")
        except urllib.error.HTTPError as error:
            status = error.code
            response_headers = {
                key.lower(): value for key, value in error.headers.items()
            }
            payload = error.read().decode("utf-8")
        return status, json.loads(payload) if payload else {}, response_headers


class Phase1ApiSmokeTest(unittest.TestCase):
    """Exercise the Phase 1 identity, CSRF, idempotency and space boundary."""

    base_url = os.environ.get("RAGFORGE_API_BASE_URL", "http://127.0.0.1:18081")

    @classmethod
    def setUpClass(cls) -> None:
        cls.suffix = uuid.uuid4().hex[:12]
        cls.login_value = "phase1-test-credential-123456"
        cls.space_name = f"Private Alpha {cls.suffix}"
        cls.alice = ApiClient(cls.base_url)
        cls.bob = ApiClient(cls.base_url)
        cls.eve = ApiClient(cls.base_url)

        for client, name in ((cls.alice, "Alice"), (cls.bob, "Bob"), (cls.eve, "Eve")):
            email = f"{name.lower()}-{cls.suffix}@example.test"
            status, body, _ = client.call(
                "POST",
                "/api/v1/auth/register",
                {"email": email, "password": cls.login_value, "displayName": name},
                {"Idempotency-Key": f"register-{name.lower()}-{cls.suffix}"},
            )
            if status != 201:
                raise AssertionError(f"register {name}: status={status}, body={body}")
            status, body, headers = client.call(
                "POST",
                "/api/v1/auth/login",
                {"email": email, "password": cls.login_value},
                {"Idempotency-Key": f"login-{name.lower()}-{cls.suffix}"},
            )
            if status != 201:
                raise AssertionError(f"login {name}: status={status}, body={body}")
            client.user_id = body["user"]["userId"]
            client.csrf_token = headers.get("x-csrf-token") or body["session"]["csrfToken"]

        status, body, _ = cls.alice.call(
            "POST",
            "/api/v1/spaces",
            {"name": cls.space_name, "description": "synthetic phase1 space"},
            {
                "X-CSRF-Token": cls.alice.csrf_token,
                "Idempotency-Key": f"space-create-{cls.suffix}",
            },
        )
        if status != 201:
            raise AssertionError(f"create space: status={status}, body={body}")
        cls.space_id = body["spaceId"]

    def test_health_and_current_session(self) -> None:
        status, body, _ = self.alice.call("GET", "/actuator/health")
        self.assertEqual(status, 200)
        self.assertEqual(body.get("status"), "UP")

        status, body, _ = self.alice.call("GET", "/api/v1/sessions/current")
        self.assertEqual(status, 200)
        self.assertEqual(body["user"]["userId"], self.alice.user_id)
        self.assertEqual(body["session"]["csrfToken"], self.alice.csrf_token)

    def test_space_boundary_csrf_and_idempotency(self) -> None:
        status, body, _ = self.alice.call(
            "POST", "/api/v1/spaces", {"name": "Private Alpha"}
        )
        self.assertEqual(status, 403, body)
        self.assertEqual(body.get("code"), "CSRF_FAILED")

        key = f"space-create-{self.suffix}"
        status, space, _ = self.alice.call(
            "POST",
            "/api/v1/spaces",
            {"name": self.space_name, "description": "synthetic phase1 space"},
            {"X-CSRF-Token": self.alice.csrf_token, "Idempotency-Key": key},
        )
        self.assertEqual(status, 409, space)
        self.assertEqual(space.get("code"), "IDEMPOTENCY_KEY_REUSED")

        status, body, _ = self.alice.call(
            "POST",
            "/api/v1/spaces",
            {"name": self.space_name, "description": "synthetic phase1 space"},
            {"X-CSRF-Token": self.alice.csrf_token, "Idempotency-Key": key},
        )
        self.assertEqual(status, 409, body)
        self.assertEqual(body.get("code"), "IDEMPOTENCY_KEY_REUSED")

    def test_cross_space_membership_and_no_leak(self) -> None:
        status, body, _ = self.bob.call("GET", "/api/v1/spaces")
        self.assertEqual(status, 200, body)
        self.assertEqual(body["items"], [])

        status, body, _ = self.alice.call(
            "PUT",
            f"/api/v1/spaces/{self.space_id}/members/{self.bob.user_id}",
            {"role": "VIEWER"},
            {
                "X-CSRF-Token": self.alice.csrf_token,
                "Idempotency-Key": f"member-add-{self.suffix}",
            },
        )
        self.assertEqual(status, 200, body)
        self.assertEqual(body["role"], "VIEWER")

        status, body, _ = self.bob.call("GET", "/api/v1/spaces")
        self.assertEqual(status, 200, body)
        self.assertEqual(body["items"][0]["spaceId"], self.space_id)

        status, body, _ = self.eve.call(
            "PUT",
            f"/api/v1/spaces/{self.space_id}/members/{self.alice.user_id}",
            {"role": "VIEWER"},
            {
                "X-CSRF-Token": self.eve.csrf_token,
                "Idempotency-Key": f"member-cross-space-{self.suffix}",
            },
        )
        self.assertEqual(status, 404, body)
        self.assertEqual(body.get("code"), "SPACE_NOT_FOUND")
        self.assertNotIn(self.space_name, json.dumps(body))


if __name__ == "__main__":
    unittest.main(verbosity=2)
