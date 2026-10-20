#!/usr/bin/env python3
"""Fingerprint actual compiler inputs without optional Git index writes.

Unavailable or unsupported provenance is explicitly unknown and dirty, allowing
local builds while preventing the publication gate from certifying them.
"""
import hashlib
import json
from pathlib import Path
import re
import stat
import subprocess


UNKNOWN = {'commit': 'unknown', 'treeHash': 'unknown', 'dirty': 'true'}


def git(root, *args):
    return subprocess.check_output(
        ['git', '--no-optional-locks', '-C', str(root), *args], stderr=subprocess.DEVNULL)


def patched_build(source, patch):
    """Apply the single-file diffpatch hunks strictly, without fuzzy matching.

    A generator format change fails to unknown rather than certifying a guess.
    Both paperweight's file patcher and these build scripts use LF text lines.
    """
    original = source.decode('utf-8').splitlines()
    result, cursor = [], 0
    lines = iter(patch.decode('utf-8').splitlines())
    if not next(lines).startswith('--- ') or not next(lines).startswith('+++ '):
        raise ValueError('Unsupported build patch header')
    body = list(lines)
    index = 0
    while index < len(body):
        match = re.fullmatch(r'@@ -(\d+)(?:,(\d+))? \+(?:_|\d+)(?:,(\d+))? @@.*', body[index])
        if not match:
            raise ValueError('Unsupported build patch hunk')
        start, old_count, new_count = int(match[1]), int(match[2] or 1), int(match[3] or 1)
        start = start - 1 if old_count else start
        if start < cursor or start > len(original):
            raise ValueError('Overlapping/out-of-range build patch')
        result.extend(original[cursor:start])
        cursor = start
        removed = added = 0
        index += 1
        while index < len(body) and not body[index].startswith('@@ '):
            line = body[index]
            if not line or line[0] not in ' +-':
                raise ValueError('Unsupported build patch line')
            if line[0] in ' -':
                if cursor >= len(original) or original[cursor] != line[1:]:
                    raise ValueError('Build patch context differs from pinned upstream')
                cursor += 1
                removed += 1
            if line[0] in ' +':
                result.append(line[1:])
                added += 1
            index += 1
        if (removed, added) != (old_count, new_count):
            raise ValueError('Build patch hunk counts differ')
    result.extend(original[cursor:])
    return ('\n'.join(result) + '\n').encode('utf-8')


def _identity(root):
    if not (root / '.git').exists():
        raise ValueError('Source root lacks Git metadata')
    commit = git(root, 'rev-parse', 'HEAD').decode().strip()
    dirty = bool(git(root, 'status', '--porcelain', '--untracked-files=all').strip())
    nested = ('paper-api', 'paper-server', 'sourbycraft-server/src/minecraft/java',
              'sourbycraft-server/src/minecraft/resources')
    paths = set(git(root, 'ls-files', '-z', '--cached', '--others', '--exclude-standard').split(b'\0')) - {b''}
    for name in nested:
        checkout = root / name
        if not checkout.exists():
            continue
        if not (checkout / '.git').exists():
            raise ValueError(f'Materialized checkout lacks Git metadata: {name}')
        status = git(checkout, 'status', '--porcelain', '--untracked-files=all')
        dirty = dirty or bool(status.strip())
        entries = git(checkout, 'ls-files', '-z', '--cached', '--others', '--exclude-standard')
        paths.update(name.encode() + b'/' + entry for entry in entries.split(b'\0') if entry)
    for module, upstream in (('sourbyapi', 'paper-api'), ('sourbycraft-server', 'paper-server')):
        generated = f'{module}/build.gradle.kts'
        if not (root / generated).is_file():
            continue
        paths.add(generated.encode())
        properties = dict(line.split('=', 1) for line in (root / 'gradle.properties').read_text().splitlines()
                          if '=' in line and not line.lstrip().startswith('#'))
        pin = properties['paperRef'].strip()
        if not re.fullmatch(r'[0-9a-f]{40}', pin):
            raise ValueError('Missing full upstream pin')
        cache = root / '.gradle/caches/paperweight/upstreams/paper'
        source = git(cache, 'show', f'{pin}:{upstream}/build.gradle.kts')
        expected = patched_build(source, (root / module / 'build.gradle.kts.patch').read_bytes())
        dirty = dirty or (root / generated).read_bytes() != expected
    digest = hashlib.sha256()
    for raw in sorted(paths):
        path = root / raw.decode('utf-8', errors='surrogateescape')
        digest.update(len(raw).to_bytes(8, 'big') + raw)
        try:
            mode = path.lstat().st_mode
        except FileNotFoundError:
            digest.update(b'deleted\0')
            continue
        if stat.S_ISLNK(mode):
            data = str(path.readlink()).encode('utf-8', errors='surrogateescape')
            kind = b'symlink\0'
        elif stat.S_ISREG(mode):
            data = path.read_bytes()
            kind = b'executable\0' if mode & stat.S_IXUSR else b'file\0'
        else:
            raise ValueError(f'Unsupported source entry: {path}')
        digest.update(kind + len(data).to_bytes(8, 'big') + data)
    return {'commit': commit, 'treeHash': digest.hexdigest(), 'dirty': str(dirty).lower()}


# Build provenance (TODO Inovasi "provenance manifest"). Each key becomes the property
# provenance.<key> in META-INF/sourbycraft-build.properties and the manifest attribute
# Provenance-<key>; verify_build_identity.py checks both and can recompute them from a checkout.
PROVENANCE_DIRS = {
    'Minecraft-Features': 'sourbycraft-server/minecraft-patches/features',
    'Paper-Features': 'sourbycraft-server/paper-patches/features',
    'Api-Paper-Patches': 'sourbyapi/paper-patches',
}
PROVENANCE_FILES = {
    'Engine-Baseline': 'build-data/engine-baseline.sha256',
    'Toolchain-Lock': 'build-data/private-toolchain.lock.json',
}
PROVENANCE_PINS = {
    'Patcher-Sha256': 'patcherSha256',
    'Patcher-Pom-Sha256': 'patcherPomSha256',
    'Patcher-Module-Sha256': 'patcherModuleSha256',
    'Clip-Sha256': 'clipSha256',
}
# Added by Gradle (the Java 25 toolchain that compiles the sources), not computable here.
PROVENANCE_JDK = 'Jdk'
PROVENANCE_KEYS = tuple(PROVENANCE_DIRS) + tuple(PROVENANCE_FILES) + tuple(PROVENANCE_PINS) + (PROVENANCE_JDK,)


def directory_hash(directory):
    """SHA-256 over every regular file under ``directory`` on disk, uncommitted edits included.

    One line per file in sorted POSIX-relative-path order: ``<path> NUL <sha256 hex of the bytes> LF``.
    Names starting with a dot (``.DS_Store``) are skipped; symlinks and other entries are refused
    so a hash never silently follows a link out of the tree.
    """
    directory = Path(directory)
    if not directory.is_dir():
        raise FileNotFoundError(directory)
    lines = []
    for path in directory.rglob('*'):
        relative = path.relative_to(directory)
        if any(part.startswith('.') for part in relative.parts):
            continue
        mode = path.lstat().st_mode
        if stat.S_ISDIR(mode):
            continue
        if not stat.S_ISREG(mode):
            raise ValueError(f'Unsupported provenance entry: {path}')
        lines.append(relative.as_posix().encode('utf-8', errors='surrogateescape') + b'\0'
                     + hashlib.sha256(path.read_bytes()).hexdigest().encode() + b'\n')
    return hashlib.sha256(b''.join(sorted(lines))).hexdigest()


def provenance(root):
    """Patch-directory, baseline, lock and pin hashes; any unavailable value is ``unknown``."""
    root = Path(root)
    result = {}
    for key, relative in PROVENANCE_DIRS.items():
        try:
            result[key] = directory_hash(root / relative)
        except (OSError, ValueError):
            result[key] = 'unknown'
    for key, relative in PROVENANCE_FILES.items():
        try:
            result[key] = hashlib.sha256((root / relative).read_bytes()).hexdigest()
        except OSError:
            result[key] = 'unknown'
    try:
        properties = dict(line.split('=', 1) for line in (root / 'gradle.properties').read_text().splitlines()
                          if '=' in line and not line.lstrip().startswith('#'))
    except OSError:
        properties = {}
    for key, name in PROVENANCE_PINS.items():
        value = properties.get(name, '').strip().lower()
        result[key] = value if re.fullmatch(r'[0-9a-f]{64}', value) else 'unknown'
    return result


def identity(root):
    try:
        return _identity(Path(root))
    except (OSError, subprocess.SubprocessError, ValueError, KeyError, StopIteration):
        return dict(UNKNOWN)


if __name__ == '__main__':
    import sys
    result = identity(Path(__file__).resolve().parent.parent)
    if result == UNKNOWN:
        print('Build provenance unavailable/unsupported; using unknown + dirty.', file=sys.stderr)
    if len(sys.argv) == 2:
        print(result[sys.argv[1]])
    else:
        result['provenance'] = provenance(Path(__file__).resolve().parent.parent)
        print(json.dumps(result, sort_keys=True))
