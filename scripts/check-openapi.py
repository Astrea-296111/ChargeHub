#!/usr/bin/env python3
"""Validate the runtime OpenAPI document, internal references and operation identity."""
import argparse
from collections import Counter
from pathlib import Path
import sys

import yaml


class UniqueKeyLoader(yaml.SafeLoader):
    def construct_mapping(self, node, deep=False):
        found = set()
        for key_node, _ in node.value:
            key = self.construct_object(key_node, deep=deep)
            if key in found:
                raise ValueError(f"Duplicate YAML key {key!r} at line {key_node.start_mark.line + 1}")
            found.add(key)
        return super().construct_mapping(node, deep=deep)


def references(value):
    if isinstance(value, dict):
        if "$ref" in value:
            yield value["$ref"]
        for child in value.values():
            yield from references(child)
    elif isinstance(value, list):
        for child in value:
            yield from references(child)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("document", nargs="?", type=Path,
                        default=Path(__file__).resolve().parents[1] / "docs/openapi.yaml")
    arguments = parser.parse_args()
    try:
        from openapi_spec_validator import validate_spec
    except ImportError:
        print("Install the pinned development tools: python3 -m pip install -r requirements-dev.txt", file=sys.stderr)
        return 2
    document = yaml.load(arguments.document.read_text(encoding="utf-8"), Loader=UniqueKeyLoader)
    if not isinstance(document, dict):
        raise ValueError("OpenAPI root must be an object")
    refs = list(references(document))
    for reference in refs:
        if not isinstance(reference, str) or not reference.startswith("#/"):
            raise ValueError(f"Only inspectable internal references are allowed: {reference!r}")
        current = document
        for segment in reference[2:].split("/"):
            current = current[segment.replace("~1", "/").replace("~0", "~")]
    operations = [operation for item in document.get("paths", {}).values()
                  for method, operation in item.items()
                  if method in {"get", "post", "put", "patch", "delete", "head", "options", "trace"}]
    if any(not isinstance(operation.get("operationId"), str) or not operation["operationId"] for operation in operations):
        raise ValueError("Every operation needs a nonempty operationId")
    duplicates = [name for name, count in Counter(operation["operationId"] for operation in operations).items() if count > 1]
    if duplicates:
        raise ValueError(f"Duplicate operation IDs: {duplicates}")
    loose = [name for name, schema in document.get("components", {}).get("schemas", {}).items()
             if schema.get("type") == "object" and schema.get("additionalProperties") is not False]
    if loose:
        raise ValueError(f"Named object schemas must explicitly reject unknown properties: {loose}")
    validate_spec(document)
    print(f"OpenAPI semantic validation PASS: {len(document['paths'])} paths, {len(operations)} operations, "
          f"{len(document.get('components', {}).get('schemas', {}))} schemas, {len(refs)} resolved internal references")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, KeyError, OSError, yaml.YAMLError) as error:
        print(f"OpenAPI validation failed: {error}", file=sys.stderr)
        sys.exit(1)
