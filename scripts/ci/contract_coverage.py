#!/usr/bin/env python3
"""Check that the public OpenAPI operations and server mappings cover each other."""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable, Mapping


REPO_ROOT = Path(__file__).resolve().parents[2]
OPENAPI_PATH = REPO_ROOT / "contracts" / "openapi" / "ragforge-api-v1.yaml"

# This is the implementation surface for the current server contract. Keeping the
# list explicit makes the gate deterministic and prevents unrelated source trees
# or generated files from becoming an accidental part of the security boundary.
CONTROLLER_PATHS = (
    "apps/server/src/main/java/com/ragforge/server/answer/AnswerFeedbackController.java",
    "apps/server/src/main/java/com/ragforge/server/answer/api/AnswerApiController.java",
    "apps/server/src/main/java/com/ragforge/server/identity/AuthController.java",
    "apps/server/src/main/java/com/ragforge/server/identity/PlatformAdminBootstrapController.java",
    "apps/server/src/main/java/com/ragforge/server/identity/UserAdminController.java",
    "apps/server/src/main/java/com/ragforge/server/index/BusinessIndexController.java",
    "apps/server/src/main/java/com/ragforge/server/ingestion/BusinessIngestionController.java",
    "apps/server/src/main/java/com/ragforge/server/ops/ManagementController.java",
    "apps/server/src/main/java/com/ragforge/server/prompt/PromptManagementController.java",
    "apps/server/src/main/java/com/ragforge/server/provider/ModelProfileController.java",
    "apps/server/src/main/java/com/ragforge/server/provider/ModelRouteController.java",
    "apps/server/src/main/java/com/ragforge/server/provider/ProviderConnectionController.java",
    "apps/server/src/main/java/com/ragforge/server/provider/SpaceBindingController.java",
    "apps/server/src/main/java/com/ragforge/server/run/RunEventController.java",
    "apps/server/src/main/java/com/ragforge/server/run/RunExecutionController.java",
    "apps/server/src/main/java/com/ragforge/server/space/SpaceController.java",
    "apps/server/src/main/java/com/ragforge/server/studio/ChunkStudioController.java",
    "apps/server/src/main/java/com/ragforge/server/studio/RetrievalPlaygroundController.java",
)

HTTP_METHODS = ("get", "post", "put", "patch", "delete", "options", "head", "trace")
# Do not let a mapping without arguments consume the following Java method's
# parameter list; whitespace before annotation arguments is horizontal only.
MAPPING_RE = re.compile(r"@(Get|Post|Put|Patch|Delete|Request)Mapping[ \t]*(?:\(([^)]*)\))?")
CLASS_RE = re.compile(r"\bclass\s+\w+")
METHOD_RE = re.compile(
    r"(?m)^\s*(?:public|protected|private)\s+(?:static\s+)?[\w$.<>?, \[\]]+\s+(\w+)\s*\("
)
STRING_RE = re.compile(r'"([^"\\]*(?:\\.[^"\\]*)*)"')


@dataclass(frozen=True, order=True)
class Endpoint:
    method: str
    path: str
    controller: str
    method_name: str = ""
    operation_id: str = ""


def _paths_from_annotation(arguments: str | None) -> tuple[str, ...]:
    if not arguments:
        return ("",)
    named = re.search(r"\b(?:value|path)\s*=\s*(?:\{([^}]*)\}|([^,)]*))", arguments, re.S)
    if named:
        values = named.group(1) if named.group(1) is not None else named.group(2)
    else:
        values = arguments
    paths = tuple(STRING_RE.findall(values or ""))
    return paths or ("",)


def _mapping_paths(source: str, controller: str) -> tuple[str, ...]:
    class_match = CLASS_RE.search(source)
    class_start = class_match.start() if class_match else len(source)
    class_mapping = MAPPING_RE.search(source, 0, class_start)
    base_paths = _paths_from_annotation(class_mapping.group(2) if class_mapping else None)
    endpoints: list[Endpoint] = []
    for mapping in MAPPING_RE.finditer(source, class_start):
        mapping_name = mapping.group(1).lower()
        if mapping_name == "request":
            continue
        method_match = METHOD_RE.search(source, mapping.end())
        if not method_match:
            raise ValueError(f"{controller}: mapping has no following method declaration")
        method_name = method_match.group(1)
        for base in base_paths:
            for suffix in _paths_from_annotation(mapping.group(2)):
                path = "/" + "/".join(part.strip("/") for part in (base, suffix) if part.strip("/"))
                endpoints.append(Endpoint(mapping_name, path if path != "/" else "/", controller, method_name))
    return tuple(endpoints)


def parse_openapi(document: Mapping[str, object]) -> tuple[Endpoint, ...]:
    paths = document.get("paths")
    if not isinstance(paths, Mapping):
        raise ValueError("OpenAPI document has no paths object")
    operations: list[Endpoint] = []
    for path, path_item in paths.items():
        if not isinstance(path, str) or not isinstance(path_item, Mapping):
            continue
        for method in HTTP_METHODS:
            operation = path_item.get(method)
            if not isinstance(operation, Mapping):
                continue
            operation_id = operation.get("operationId")
            if not isinstance(operation_id, str) or not operation_id:
                raise ValueError(f"OpenAPI operation at {method.upper()} {path} has no operationId")
            operations.append(Endpoint(method, path, "", operation_id=operation_id))
    return tuple(operations)


def load_controller_sources(repo_root: Path = REPO_ROOT) -> dict[str, str]:
    sources: dict[str, str] = {}
    for relative in CONTROLLER_PATHS:
        path = repo_root / relative
        if not path.is_file():
            raise FileNotFoundError(f"controller file missing: {relative}")
        sources[relative] = path.read_text(encoding="utf-8")
    return sources


def parse_controllers(sources: Mapping[str, str]) -> tuple[Endpoint, ...]:
    endpoints: list[Endpoint] = []
    for controller, source in sorted(sources.items()):
        endpoints.extend(_mapping_paths(source, controller))
    return tuple(endpoints)


def evaluate(
    openapi_document: Mapping[str, object], controller_sources: Mapping[str, str]
) -> dict[str, object]:
    contract = parse_openapi(openapi_document)
    implementation = parse_controllers(controller_sources)
    contract_keys = {(endpoint.method, endpoint.path) for endpoint in contract}
    implementation_keys = {(endpoint.method, endpoint.path) for endpoint in implementation}

    missing_controller = [
        asdict(endpoint)
        for endpoint in contract
        if (endpoint.method, endpoint.path) not in implementation_keys
    ]
    missing_contract = [
        asdict(endpoint)
        for endpoint in implementation
        if (endpoint.method, endpoint.path) not in contract_keys
    ]
    duplicate_contract = _duplicates(contract)
    duplicate_implementation = _duplicates(implementation)
    passed = not (missing_controller or missing_contract or duplicate_contract or duplicate_implementation)
    return {
        "schema_version": "contract-coverage.v1",
        "passed": passed,
        "contract": {
            "operations": len(contract),
            "matched": len(contract) - len(missing_controller),
            "missing_controller": missing_controller,
            "duplicates": duplicate_contract,
        },
        "implementation": {
            "mappings": len(implementation),
            "matched": len(implementation) - len(missing_contract),
            "missing_contract": missing_contract,
            "duplicates": duplicate_implementation,
        },
    }


def _duplicates(endpoints: Iterable[Endpoint]) -> list[dict[str, object]]:
    grouped: dict[tuple[str, str], list[Endpoint]] = {}
    for endpoint in endpoints:
        grouped.setdefault((endpoint.method, endpoint.path), []).append(endpoint)
    return [
        {
            "method": method,
            "path": path,
            "controllers": [endpoint.controller for endpoint in values],
        }
        for (method, path), values in sorted(grouped.items())
        if len(values) > 1
    ]


def _print_failures(result: Mapping[str, object]) -> None:
    print("Contract coverage failed.", file=sys.stderr)
    contract = result["contract"]
    implementation = result["implementation"]
    for item in contract["missing_controller"]:
        print(
            f"- missing controller: operationId={item['operation_id']} "
            f"method={item['method'].upper()} path={item['path']} controller=<none>",
            file=sys.stderr,
        )
    for item in implementation["missing_contract"]:
        print(
            f"- missing contract: operationId=<none> method={item['method'].upper()} "
            f"path={item['path']} controller={item['controller']}",
            file=sys.stderr,
        )
    for label, items in (("duplicate contract", contract["duplicates"]), ("duplicate implementation", implementation["duplicates"])):
        for item in items:
            print(f"- {label}: method={item['method'].upper()} path={item['path']} controllers={','.join(item['controllers'])}", file=sys.stderr)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, help="write the machine-readable coverage summary to this path")
    args = parser.parse_args(argv)
    try:
        document = json.loads(OPENAPI_PATH.read_text(encoding="utf-8"))
        result = evaluate(document, load_controller_sources())
    except (OSError, UnicodeDecodeError, json.JSONDecodeError, ValueError) as exc:
        print(f"Contract coverage could not run: {exc}", file=sys.stderr)
        return 2
    payload = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(payload, encoding="utf-8")
    if result["passed"]:
        print(
            f"Contract coverage passed: {result['contract']['matched']}/{result['contract']['operations']} "
            f"contract operations and {result['implementation']['matched']}/{result['implementation']['mappings']} "
            "controller mappings matched."
        )
        return 0
    _print_failures(result)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
