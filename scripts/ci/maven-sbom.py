#!/usr/bin/env python3
"""Convert Maven's resolved runtime dependency tree into an OSV-readable inventory."""
import json
from pathlib import Path
import sys
from urllib.parse import quote

source, target = map(Path, sys.argv[1:])
tree = json.loads(source.read_text())
components = {}


def visit(node):
    for child in node.get("children", []):
        group, name, version = (child[key] for key in ("groupId", "artifactId", "version"))
        if not all((group, name, version)):
            raise ValueError("Unresolved Maven dependency")
        purl = f"pkg:maven/{quote(group, safe='')}/{quote(name, safe='')}@{quote(version, safe='')}"
        components[purl] = {"type": "library", "group": group, "name": name,
                            "version": version, "purl": purl, "bom-ref": purl}
        visit(child)


visit(tree)
if not components:
    raise ValueError("Empty Maven dependency inventory")
target.parent.mkdir(parents=True, exist_ok=True)
target.write_text(json.dumps({"bomFormat": "CycloneDX", "specVersion": "1.6", "version": 1,
                              "components": [components[key] for key in sorted(components)]}, indent=2) + "\n")
print(f"Exported {len(components)} resolved Maven components to {target}")
