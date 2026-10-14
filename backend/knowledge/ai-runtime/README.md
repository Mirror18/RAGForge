# knowledge / ai-runtime

Python rerank helper owned by the **knowledge** business module (RF-10 / T007).

It is **not** a Compose/deployed business service. Java calls it only when a space
explicitly configures a local rerank provider endpoint (see
`AiRuntimeRerankProviderAdapter`). Default product path remains cloud-authorized
OpenAI-compatible APIs.

## Local optional serve

```powershell
uv run --project backend/knowledge/ai-runtime ragforge-ai-runtime
$env:RAGFORGE_AI_RUNTIME_SERVE = "true"
uv run --project backend/knowledge/ai-runtime ragforge-ai-runtime
python -m unittest discover -s backend/knowledge/ai-runtime/tests -p "test_*.py" -v
```

Default loopback address is `127.0.0.1:8090`. Service mode starts only when
`RAGFORGE_AI_RUNTIME_SERVE=true`.
