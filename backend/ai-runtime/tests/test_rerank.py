import json
import pathlib
import sys
import threading
import unittest
from http.client import HTTPConnection
from http.server import ThreadingHTTPServer
from uuid import uuid4

sys.path.insert(0, str(pathlib.Path(__file__).parents[1] / "src"))

from ragforge_ai_runtime.rerank import RerankRequestHandler, RerankService, RerankValidationError


class RerankServiceTest(unittest.TestCase):
    def setUp(self):
        self.space = uuid4()
        self.first = uuid4()
        self.second = uuid4()

    def payload(self):
        return {
            "space_id": str(self.space),
            "model": "local-rerank-v1",
            "query": "local retrieval",
            "top_k": 2,
            "candidates": [
                {"space_id": str(self.space), "candidate_id": str(self.first), "text": "local retrieval"},
                {"space_id": str(self.space), "candidate_id": str(self.second), "text": "unrelated"},
            ],
        }

    def test_real_response_retains_identity_and_metadata(self):
        response = RerankService().rerank(self.payload())
        self.assertEqual(response["model"], "local-rerank-v1")
        self.assertEqual(response["capabilities"], ["RERANK"])
        self.assertEqual(response["results"][0]["candidate_id"], str(self.first))

    def test_foreign_space_is_rejected(self):
        payload = self.payload()
        payload["candidates"][0]["space_id"] = str(uuid4())
        with self.assertRaises(RerankValidationError):
            RerankService().rerank(payload)

    def test_candidate_and_text_bounds_are_rejected(self):
        payload = self.payload()
        payload["candidates"] = payload["candidates"] * 51
        with self.assertRaises(RerankValidationError):
            RerankService().rerank(payload)

    def test_loopback_http_returns_json(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0), RerankRequestHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            connection = HTTPConnection("127.0.0.1", server.server_port, timeout=2)
            body = json.dumps(self.payload()).encode()
            connection.request("POST", "/v1/rerank", body, {"Content-Type": "application/json"})
            response = connection.getresponse()
            result = json.loads(response.read())
            self.assertEqual(response.status, 200)
            self.assertEqual(result["capabilities"], ["RERANK"])
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)
