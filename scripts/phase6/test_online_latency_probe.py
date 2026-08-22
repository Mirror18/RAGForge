import unittest
from unittest.mock import patch

from online_latency_probe import _read_first_sse_event, probe


class FakeSseResponse:
    status = 200

    def __init__(self, lines):
        self.lines = iter(lines)
        self.request_headers = None

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    def readline(self):
        return next(self.lines, b"")

    def read(self, _size):
        return b"ok"


class OnlineLatencyProbeTest(unittest.TestCase):
    def test_first_event_reader_stops_at_complete_event(self):
        response = FakeSseResponse([b"id: 1\n", b"data: {}\n", b"\n", b"data: later\n"])

        self.assertTrue(_read_first_sse_event(response))

    def test_probe_reads_first_event_and_passes_auth_header_without_serializing_it(self):
        response = FakeSseResponse([b"event: run.snapshot\n", b"data: {}\n", b"\n"])
        with patch("urllib.request.urlopen", return_value=response) as open_url:
            result = probe(
                "http://127.0.0.1:8080/events",
                1,
                1,
                headers={"Cookie": "RAGFORGE_SESSION.synthetic"},
                first_event=True,
            )

        request = open_url.call_args.args[0]
        self.assertEqual(request.get_header("Cookie"), "RAGFORGE_SESSION.synthetic")
        self.assertEqual(result["errors"], 0)
        self.assertIsNotNone(result["p95_ms"])
        self.assertNotIn("RAGFORGE_SESSION", str(result))


if __name__ == "__main__":
    unittest.main()
