"""AI Runtime process boundary; OCR and rerank adapters land in later phases."""

import json


def main() -> None:
    """Expose a side-effect-free readiness check until Phase 2 adapters land."""
    print(json.dumps({
        "service": "ragforge-ai-runtime",
        "status": "ready",
        "capabilities": [],
        "phase": "1",
    }))
