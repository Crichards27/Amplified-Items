#!/usr/bin/env python3
"""Validate custom resource references and list proven-unreferenced textures/models.

Item definitions and equipment are entry points. External namespaces, aliases,
and documentation previews are not deleted. This tool is read-only.
"""
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def audit(assets):
    referenced = set()
    missing = set()
    nodes = {p.relative_to(assets).as_posix(): p for p in assets.rglob('*.json')}
    queue = [name for name in nodes if '/items/' in name or '/equipment/' in name]
    seen = set()

    def reference(value, category, extension):
        if not isinstance(value, str) or ':' not in value or value.startswith('#'):
            return
        namespace, name = value.split(':', 1)
        if namespace == 'minecraft':
            return
        relative = f'{namespace}/{category}/{name}{extension}'
        referenced.add(relative)
        if not (assets / relative).is_file():
            missing.add(relative)
        if relative in nodes and relative not in seen:
            queue.append(relative)

    def walk(node):
        if isinstance(node, list):
            for value in node:
                walk(value)
        elif isinstance(node, dict):
            for key, value in node.items():
                if key in ('model', 'parent') and isinstance(value, str):
                    reference(value, 'models', '.json')
                elif key == 'textures' and isinstance(value, dict):
                    for texture in value.values():
                        reference(texture, 'textures', '.png')
                elif key == 'layers' and isinstance(value, dict):
                    for layer, descriptions in value.items():
                        for description in descriptions:
                            reference(description.get('texture'), f'textures/entity/equipment/{layer}', '.png')
                walk(value)

    while queue:
        name = queue.pop()
        if name in seen:
            continue
        seen.add(name)
        referenced.add(name)
        walk(json.loads(nodes[name].read_text(encoding='utf-8')))
    candidates = {p.relative_to(assets).as_posix() for p in assets.rglob('*')
                  if p.is_file() and ('/textures/' in p.relative_to(assets).as_posix()
                                     or '/models/' in p.relative_to(assets).as_posix())}
    return {'missing': sorted(missing), 'unused': sorted(candidates - referenced),
            'referenced_files': len(referenced), 'entry_points': len([n for n in nodes if '/items/' in n or '/equipment/' in n])}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='fail if resources are missing or unused')
    args = parser.parse_args()
    report = audit(ROOT / 'resourcepack/assets')
    print(json.dumps(report, indent=2))
    raise SystemExit(1 if args.check and (report['missing'] or report['unused']) else 0)
