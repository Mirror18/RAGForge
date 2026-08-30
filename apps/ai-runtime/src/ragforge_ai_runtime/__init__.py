"""AI Runtime process boundary for bounded local OCR and rerank capabilities."""

import json
import os

from .rerank import RerankEngine, RerankService, serve

__all__ = ["RerankEngine", "RerankService", "main", "serve"]


def main() -> None:
    """Run the local service or emit readiness metadata."""
    if os.environ.get("RAGFORGE_AI_RUNTIME_SERVE", "").lower() in {"1", "true", "yes"}:
        serve(os.environ.get("RAGFORGE_AI_RUNTIME_HOST", "127.0.0.1"),
             int(os.environ.get("RAGFORGE_AI_RUNTIME_PORT", "8090")))
        return
    print(json.dumps({
        "service": "ragforge-ai-runtime",
        "status": "ready",
        "capabilities": ["RERANK"],
        "egress": "LOCAL_ONLY",
        "phase": "7C",
    }))
