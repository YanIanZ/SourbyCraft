#!/usr/bin/env python3
"""Publish pinned private checkouts to Maven Local, or verify the installed artifacts."""
import argparse
import hashlib
import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    value = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for data in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(data)
    return value.hexdigest()


def properties():
    return dict(line.split('=', 1) for line in (ROOT / 'gradle.properties').read_text().splitlines()
                if '=' in line and not line.lstrip().startswith('#'))


def verify(repository):
    config = properties()
    artifacts = {
        'SourbyClip': ('dev/iyanz/sourbyclip', 'sourbyclip', 'clipVersion', 'clipSha256'),
        'SourbyPatcher': ('dev/iyanz/sourbypatcher/canvas-toolchain', 'canvas-toolchain', 'patcherVersion', 'patcherSha256'),
    }
    for name, (group, artifact, version_key, hash_key) in artifacts.items():
        version = config[version_key]
        path = Path(repository) / group / version / f'{artifact}-{version}.jar'
        if not path.is_file():
            raise ValueError(f'{name} missing: {path}. Publish the pinned private checkout to Maven Local first.')
        if digest(path) != config[hash_key]:
            raise ValueError(f'{name} SHA-256 mismatch: {path}; use the approved private revision.')
        print(f'Verified {name} {version}')


def publish(checkouts, repository):
    checkouts = Path(checkouts).resolve()
    repository = Path(repository).resolve()
    lock = json.loads((ROOT / 'build-data/private-toolchain.lock.json').read_text())
    for name, relative in [('SourbyPatcher', 'canvas-toolchain'), ('SourbyClip', '.')]:
        checkout = Path(checkouts) / name
        actual = subprocess.check_output(['git', '-C', str(checkout), 'rev-parse', 'HEAD'], text=True).strip()
        if actual != lock[name]['revision']:
            raise ValueError(f'{name} checkout is {actual}; expected {lock[name]["revision"]}')
        dirty = subprocess.check_output(['git', '-C', str(checkout), 'status', '--porcelain'], text=True)
        if dirty.strip():
            raise ValueError(f'{name} checkout has uncommitted changes; refusing an unpinned publication')
        subprocess.run([str(checkout / 'gradlew'), '-p', str(checkout / relative),
                        f'-Dmaven.repo.local={Path(repository).resolve()}',
                        'test', 'publishToMavenLocal', '--no-daemon'], cwd=checkout, check=True)
    verify(repository)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--publish', type=Path, metavar='CHECKOUTS',
                        help='directory containing pinned SourbyPatcher and SourbyClip checkouts')
    parser.add_argument('--maven-local', type=Path, default=Path.home() / '.m2/repository')
    args = parser.parse_args()
    try:
        if args.publish:
            publish(args.publish, args.maven_local)
        else:
            verify(args.maven_local)
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        parser.exit(1, f'Private toolchain: {error}\n')


if __name__ == '__main__':
    main()
