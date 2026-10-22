#!/usr/bin/env python3
"""Inventory engine baseline provenance using local Git evidence only.

Only added lines (leading +, excluding +++) contribute marker counts. Count literal
// Folia - , // Canvas - , // Paper - , and // Pufferfish occurrences. Package
references in added lines and package paths are additional evidence: io.canvasmc
and dev.iyanz.aurora.engine imply Canvas; io.papermc.paper.threadedregions implies
Folia. Both engine signals => mixed; one => that origin; only Paper/Pufferfish
markers => other; no evidence => unknown. Confidence is high only when nonempty
engine marker and package/path evidence sets agree, otherwise low. These are
heuristics, not authorship proof. Import hashes refer to the SAME path at import,
not a rename predecessor; absent paths have null import hashes and are modified.
Rename ancestry and change commits follow detected renames in a single range log
(the cheap equivalent of per-file --follow, limited to post-import history).

--check compares everything except change_commits: a commit that touches a baseline file
cannot list its own hash, so change_commits is a snapshot as of the last --write. The
check needs the import commit in the clone; a shallow clone gets exit 2.
"""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path, PurePosixPath
import subprocess
import sys

from baseline_policy import BASELINE_ROOTS, MANIFEST, BaselineError, read_manifest

IMPORT_COMMIT = 'e20df47b'
VERSION = '1'
OUTPUT = 'build-data/engine-baseline-origin.json'
MARKERS = {'folia': '// Folia - ', 'canvas': '// Canvas - ',
           'paper': '// Paper - ', 'pufferfish': '// Pufferfish'}


def digest(data):
    return hashlib.sha256(data.replace(b'\r\n', b'\n')).hexdigest()


def render(data):
    return json.dumps(data, indent=2, sort_keys=True) + '\n'


def classify(text, path):
    added = '\n'.join(line[1:] for line in text.splitlines()
                      if line.startswith('+') and not line.startswith('+++'))
    counts = {kind: added.count(marker) for kind, marker in MARKERS.items()
              if added.count(marker)}
    evidence = added + '\n' + path.replace('/', '.')
    packages = set()
    if 'io.canvasmc' in evidence or 'dev.iyanz.aurora.engine' in evidence:
        packages.add('canvas')
    if 'io.papermc.paper.threadedregions' in evidence:
        packages.add('folia')
    markers = set(counts) & {'canvas', 'folia'}
    signals = packages | markers
    origin = ('mixed' if len(signals) == 2 else next(iter(signals)) if signals
              else 'other' if counts else 'unknown')
    confidence = 'high' if markers and markers == packages else 'low'
    return origin, counts, confidence


def git(root, *args, data=None):
    result = subprocess.run(['git', '-C', str(root), *args], input=data,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        raise ValueError(result.stderr.decode(errors='replace').strip())
    return result.stdout


def manifest_paths(root):
    paths = sorted(read_manifest(root))
    for path in paths:
        p = PurePosixPath(path)
        if p.is_absolute() or '..' in p.parts or not any(path.startswith(r + '/') for r in BASELINE_ROOTS):
            raise ValueError('unsafe baseline manifest path: ' + path)
    actual = {p.relative_to(root).as_posix() for r in BASELINE_ROOTS
              for p in (root / r).rglob('*') if p.is_file()}
    differences = {'unlisted': sorted(actual - set(paths)), 'missing': sorted(set(paths) - actual)}
    for kind, values in differences.items():
        for path in values:
            print(f'engine_baseline_origin: manifest difference ({kind}): {path}', file=sys.stderr)
    return paths, differences


def generate(root, import_commit=IMPORT_COMMIT):
    root = Path(root)
    paths, differences = manifest_paths(root)
    # One tree lookup and one cat-file process, rather than 722 git show processes.
    tree = git(root, 'ls-tree', '-r', '-z', import_commit, '--', *BASELINE_ROOTS)
    objects = {}
    for entry in tree.split(b'\0'):
        if entry:
            meta, name = entry.split(b'\t', 1)
            objects[name.decode()] = meta.split()[2].decode()
    present = [p for p in paths if p in objects]
    contents = {}
    if present:
        output = git(root, 'cat-file', '--batch', data=('\n'.join(objects[p] for p in present) + '\n').encode())
        offset = 0
        for path in present:
            end = output.index(b'\n', offset)
            size = int(output[offset:end].split()[2])
            offset = end + 1
            contents[path] = output[offset:offset + size]
            offset += size + 1
    log = git(root, 'log', '--format=COMMIT:%h', '--name-status', '-M',
              import_commit + '..HEAD', '--', *BASELINE_ROOTS).decode().splitlines()
    changes = {p: set() for p in paths}
    ancestry = {p: p for p in paths}
    commit = None
    for line in log:
        if line.startswith('COMMIT:'):
            commit = line[7:]
        elif line and commit:
            fields = line.split('\t')
            if len(fields) < 2:
                continue
            old, new = fields[1], fields[-1]
            for path, ancestor in list(ancestry.items()):
                if ancestor == new:
                    changes[path].add(commit)
                    if fields[0].startswith('R'):
                        ancestry[path] = old
    records = []
    for path in paths:
        current = (root / path).read_bytes()
        origin, markers, confidence = classify(current.decode('utf-8', errors='replace'), path)
        imported = digest(contents[path]) if path in contents else None
        current_hash = digest(current)
        records.append({'path': path, 'dir': next(r for r in BASELINE_ROOTS if path.startswith(r + '/')),
                        'origin_class': origin, 'markers': markers, 'import_commit': import_commit,
                        'import_sha256': imported, 'current_sha256': current_hash,
                        'modified_after_import': current_hash != imported,
                        'change_commits': sorted(changes[path]),
                        'renamed_from': ancestry[path] if ancestry[path] != path else None,
                        'confidence': confidence})
    counts = Counter(r['origin_class'] for r in records)
    return {'generator_version': VERSION, 'import_commit': import_commit,
            'manifest_differences': differences, 'files': records,
            'summary': {'origin_class': {c: counts[c] for c in ('folia', 'canvas', 'mixed', 'other', 'unknown')},
                        'total_files': len(records),
                        'modified_after_import': sum(r['modified_after_import'] for r in records)}}


def comparable(text):
    """The document without change_commits (see the module docstring)."""
    data = json.loads(text)
    for record in data.get('files', []):
        record.pop('change_commits', None)
    return data


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--write', action='store_true')
    mode.add_argument('--check', action='store_true')
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args(argv)
    try:
        expected = render(generate(args.root))
        target = args.root / OUTPUT
        if args.write:
            target.write_text(expected, encoding='utf-8')
            print('engine_baseline_origin: wrote ' + OUTPUT)
        elif not target.is_file() or comparable(target.read_text(encoding='utf-8')) != comparable(expected):
            print('engine_baseline_origin: stale or missing ' + OUTPUT + '; run --write', file=sys.stderr)
            return 1
        else:
            print('engine_baseline_origin: OK (' + OUTPUT + ' is current)')
        return 0
    except (OSError, ValueError, BaselineError) as error:
        print(f'engine_baseline_origin: ERROR: {error}', file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
