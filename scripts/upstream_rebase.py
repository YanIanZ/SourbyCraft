#!/usr/bin/env python3
"""Offline engine source rebase planner; never edits patches or the manifest.

Inputs may be repository-shaped materialized trees (Minecraft under
sourbycraft-server/src/minecraft/java, Paper under paper-server, API under
paper-api), or standalone source roots. The current tree defaults to this repo
(--root overrides it). Output target paths always use the repository layout.
Ordered literal replacements transform upstream contents AND package paths.
A rename-map JSON file replaces the defaults: [["old", "new"], ...].

Apply only writes VERBATIM/MERGE3_CLEAN entries and removes DELETED entries;
ADDED is informational and MANUAL is left for review. Review the JSON before
--yes. Afterwards edit patch files or the materialized repository, use Gradle
rebuild*Patches, run baseline_policy.py --write-manifest, then regenerate the
origin JSON yourself. This tool does none of those follow-up steps.
"""
import argparse
from collections import Counter
import json
from pathlib import Path, PurePosixPath
import subprocess
import sys
import tempfile

from engine_baseline_origin import (BASELINE_ROOTS, digest, generate, render)

DEFAULT_RENAMES = [('io.canvasmc.canvas', 'dev.iyanz.aurora.engine'),
                   ('io.canvasmc', 'dev.iyanz.sourbycraft.api')]
LAYOUT = dict(zip(BASELINE_ROOTS, ('sourbycraft-server/src/minecraft/java', 'paper-server', 'paper-api')))
WRITABLE = {'VERBATIM', 'MERGE3_CLEAN', 'DELETED'}


def load_renames(path):
    value = json.loads(Path(path).read_text(encoding='utf-8'))
    if not isinstance(value, list) or any(not isinstance(pair, list) or len(pair) != 2
            or not all(isinstance(x, str) and x for x in pair) for pair in value):
        raise ValueError('rename map must be an ordered JSON array of [old, new] nonempty strings')
    return value


def transform(text, renames):
    for old, new in renames:
        text = text.replace(old, new)
    return text


def safe_path(tree, relative):
    p = PurePosixPath(relative)
    if (p.is_absolute() or '..' in p.parts or not p.parts or '\\' in relative
            or any(x in {'features', 'build-data', '.git'} for x in p.parts)
            or relative.endswith('.patch')):
        raise ValueError('refusing unsafe/protected path: ' + relative)
    tree = Path(tree).resolve()
    result = tree.joinpath(*p.parts)
    # Refuse symlinks even if they currently point inside the tree.
    current = tree
    for part in p.parts:
        current = current / part
        if current.is_symlink():
            raise ValueError('refusing symlink path: ' + relative)
    if tree not in result.resolve().parents:
        raise ValueError('path escapes tree: ' + relative)
    return result


def source_path(patch):
    for root, prefix in LAYOUT.items():
        if patch.startswith(root + '/') and patch.endswith('.patch'):
            return prefix + '/' + patch[len(root) + 1:-6]
    raise ValueError('unmapped baseline path: ' + patch)


def upstream_files(tree, renames):
    tree = Path(tree).resolve()
    if not tree.is_dir():
        raise ValueError('upstream directory missing: ' + str(tree))
    result = {}
    for path in sorted(tree.rglob('*')):
        relative = path.relative_to(tree).as_posix()
        if any(p.startswith('.') for p in path.relative_to(tree).parts):
            continue
        if path.is_symlink():
            raise ValueError('symlink in upstream: ' + relative)
        if not path.is_file() or path.suffix != '.java':
            continue
        renamed = transform(relative, [(a.replace('.', '/'), b.replace('.', '/')) for a, b in renames])
        if renamed in result:
            raise ValueError('rename collision: ' + renamed)
        result[renamed] = transform(path.read_bytes().decode('utf-8').replace('\r\n', '\n'), renames)
    return result


def counterpart(files, relative):
    prefix = next(p for p in LAYOUT.values() if relative.startswith(p + '/'))
    tail = relative[len(prefix) + 1:]
    candidates = {relative, tail}
    if tail.startswith('src/main/java/'):
        candidates.add(tail[len('src/main/java/'):])
    found = sorted(candidates & files.keys())
    if len(found) > 1:
        raise ValueError('ambiguous upstream counterpart: ' + relative)
    return (found[0], files[found[0]]) if found else (None, None)


def merge3(base, ours, theirs):
    with tempfile.TemporaryDirectory(prefix='upstream-rebase-') as temp:
        paths = [Path(temp) / label for label in ('ours', 'base', 'theirs')]
        for path, text in zip(paths, (ours, base, theirs)):
            path.write_bytes(text.encode('utf-8'))
        result = subprocess.run(['git', 'merge-file', '-p', '-L', 'ours', '-L', 'base',
                                 '-L', 'theirs', *map(str, paths)], capture_output=True)
        if result.returncode < 0 or result.returncode > 127:
            raise ValueError('git merge-file failed: ' + result.stderr.decode(errors='replace'))
        return result.returncode == 0, result.stdout.decode('utf-8')


def make_plan(ours_tree, old_tree, new_tree, records, renames):
    old, new = upstream_files(old_tree, renames), upstream_files(new_tree, renames)
    entries, matched_new = [], set()
    for record in sorted(records, key=lambda r: r['path']):
        relative = source_path(record['path'])
        current = safe_path(ours_tree, relative)
        ours_bytes = current.read_bytes() if current.is_file() else None
        ours = ours_bytes.decode('utf-8').replace('\r\n', '\n') if ours_bytes is not None else None
        old_key, base = counterpart(old, relative)
        new_key, theirs = counterpart(new, relative)
        if new_key:
            matched_new.add(new_key)
        entry = {'path': relative, 'baseline_path': record['path'], 'origin_class': record['origin_class'],
                 'ours_sha256': digest(ours_bytes) if ours_bytes is not None else None,
                 'classification': 'MANUAL'}
        if record['origin_class'] == 'unknown':
            entry['reason'] = 'unknown origin'
        elif ours is None or base is None:
            entry['reason'] = 'missing current or old-upstream counterpart'
        elif theirs is None:
            entry['classification'] = 'DELETED'
            entry['reason'] = 'old-upstream counterpart absent in new upstream; review local changes'
        elif ours == base:
            entry.update(classification='VERBATIM', content=theirs)
        else:
            clean, merged = merge3(base, ours, theirs)
            if clean:
                entry.update(classification='MERGE3_CLEAN', content=merged)
            else:
                entry.update(reason='3-way merge conflicts', conflict_content=merged)
        entries.append(entry)
    for relative in sorted(new.keys() - old.keys() - matched_new):
        # Informational paths refer to the supplied new tree, never applied.
        entries.append({'path': relative, 'classification': 'ADDED'})
    entries.sort(key=lambda e: (e['path'], e.get('baseline_path', '')))
    counts = Counter(e['classification'] for e in entries)
    return {'version': 1, 'rename_map': renames, 'files': entries,
            'summary': {c: counts[c] for c in ('VERBATIM', 'MERGE3_CLEAN', 'MANUAL', 'DELETED', 'ADDED')}}


def apply_plan(plan, target, yes):
    if plan.get('version') != 1:
        raise ValueError('unsupported plan version')
    actions, seen = [], set()
    for entry in plan['files']:
        kind = entry['classification']
        if kind not in WRITABLE:
            if kind not in {'MANUAL', 'ADDED'}:
                raise ValueError('unknown classification: ' + kind)
            continue
        relative = entry['path']
        path = safe_path(target, relative)
        if not any(relative.startswith(prefix + '/') for prefix in LAYOUT.values()):
            raise ValueError('path outside materialized source trees: ' + relative)
        if path in seen:
            raise ValueError('duplicate target path: ' + relative)
        seen.add(path)
        actual = digest(path.read_bytes()) if path.is_file() else None
        if actual != entry['ours_sha256']:
            raise ValueError('target changed since plan: ' + relative)
        if kind != 'DELETED' and not isinstance(entry.get('content'), str):
            raise ValueError('missing merged content: ' + relative)
        actions.append((path, entry))
    if not yes:
        listing = '\n'.join(f"{e['classification']} {e['path']}" for _, e in actions)
        raise ValueError('refusing apply without --yes; would write/delete:\n' + listing)
    # Validate all entries before changing anything.
    for path, entry in actions:
        if entry['classification'] == 'DELETED':
            path.unlink()
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(entry['content'].encode('utf-8'))
    return len(actions)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest='command', required=True)
    plan = sub.add_parser('plan', help='compare local materialized source trees without modifying them')
    plan.add_argument('--old-upstream', type=Path, required=True)
    plan.add_argument('--new-upstream', type=Path, required=True)
    plan.add_argument('--rename-map', type=Path)
    plan.add_argument('--out', type=Path, help='JSON destination; otherwise JSON goes to stdout')
    plan.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1],
                      help='current SourbyCraft repository/materialized trees')
    apply = sub.add_parser('apply', help='apply reviewed results; regenerate patches and manifest yourself afterwards',
                           description='Writes only reviewed source results with --yes. Afterwards run Gradle '
                           'rebuild*Patches, baseline_policy.py --write-manifest, and '
                           'engine_baseline_origin.py --write yourself; patches are never edited here.')
    apply.add_argument('--plan', type=Path, required=True)
    apply.add_argument('--target', type=Path, required=True)
    apply.add_argument('--yes', action='store_true')
    args = parser.parse_args(argv)
    try:
        if args.command == 'apply':
            count = apply_plan(json.loads(args.plan.read_text(encoding='utf-8')), args.target, args.yes)
            print(f'upstream_rebase: applied {count} files; regenerate patches with Gradle, then run '
                  'baseline_policy.py --write-manifest and engine_baseline_origin.py --write')
        else:
            renames = load_renames(args.rename_map) if args.rename_map else DEFAULT_RENAMES
            records = generate(args.root)['files']
            result = make_plan(args.root, args.old_upstream, args.new_upstream, records, renames)
            encoded = render(result)
            if args.out:
                args.out.write_text(encoded, encoding='utf-8')
            else:
                print(encoded, end='')
            print('upstream_rebase: ' + ', '.join(f'{k}={v}' for k, v in result['summary'].items()))
            for entry in result['files']:
                if entry['classification'] == 'MANUAL':
                    print('MANUAL ' + entry['path'] + ': ' + entry['reason'])
        return 0
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f'upstream_rebase: ERROR: {error}', file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
