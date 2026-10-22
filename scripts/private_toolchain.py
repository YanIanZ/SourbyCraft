#!/usr/bin/env python3
"""Publish pinned private checkouts to Maven Local, or verify the installed artifacts."""
import argparse
import hashlib
import json
import subprocess
import xml.etree.ElementTree as ElementTree
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# Group of the paperweight patcher plugin the pinned SourbyPatcher metadata depends on. Its version
# is the paperweight release the toolchain actually loads, so it must match paperweightVersion.
PAPERWEIGHT_GROUP = 'io.papermc.paperweight.patcher'


def digest(path):
    value = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for data in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(data)
    return value.hexdigest()


def local_name(element):
    return element.tag.rsplit('}', 1)[-1]


def paperweight_version(pom):
    """Return the paperweight patcher version a published plugin POM declares."""
    for dependency in ElementTree.parse(pom).getroot().iter():
        if local_name(dependency) != 'dependency':
            continue
        fields = {local_name(child): (child.text or '').strip() for child in dependency}
        if fields.get('groupId') == PAPERWEIGHT_GROUP:
            return fields.get('version', '')
    raise ValueError(f'{pom} declares no {PAPERWEIGHT_GROUP} dependency')


def module_paperweight_version(module):
    """Return the paperweight patcher version published Gradle module metadata declares."""
    for variant in json.loads(Path(module).read_text()).get('variants', []):
        for dependency in variant.get('dependencies', []):
            if dependency.get('group') == PAPERWEIGHT_GROUP:
                return dependency.get('version', {}).get('requires', '')
    raise ValueError(f'{module} declares no {PAPERWEIGHT_GROUP} dependency')


def properties():
    return dict(line.split('=', 1) for line in (ROOT / 'gradle.properties').read_text().splitlines()
                if '=' in line and not line.lstrip().startswith('#'))


def verify(repository):
    config = properties()
    artifacts = {
        'SourbyClip': ('dev/iyanz/sourbyclip', 'sourbyclip', 'clipVersion', 'clipSha256'),
        'SourbyPatcher': ('dev/iyanz/sourbypatcher/paper-toolchain', 'paper-toolchain', 'patcherVersion', 'patcherSha256'),
    }
    for name, (group, artifact, version_key, hash_key) in artifacts.items():
        version = config[version_key]
        path = Path(repository) / group / version / f'{artifact}-{version}.jar'
        if not path.is_file():
            raise ValueError(f'{name} missing: {path}. Publish the pinned private checkout to Maven Local first.')
        if digest(path) != config[hash_key]:
            raise ValueError(f'{name} SHA-256 mismatch: {path}; use the approved private revision.')
        print(f'Verified {name} {version}')

    patcher = config['patcherVersion']
    base = Path(repository) / 'dev/iyanz/sourbypatcher/paper-toolchain' / patcher
    for label, extension, hash_key, declared_version in (
        ('POM', 'pom', 'patcherPomSha256', paperweight_version),
        ('module metadata', 'module', 'patcherModuleSha256', module_paperweight_version),
    ):
        artifact = base / f'paper-toolchain-{patcher}.{extension}'
        if not artifact.is_file():
            raise ValueError(f'SourbyPatcher {label} missing: {artifact}. '
                             'Publish the pinned private checkout to Maven Local first.')
        if digest(artifact) != config[hash_key]:
            raise ValueError(f'SourbyPatcher {label} SHA-256 mismatch: {artifact}; the paperweight version '
                             'may have moved. Use the approved private revision.')
        declared = declared_version(artifact)
        expected = config['paperweightVersion']
        if declared != expected:
            raise ValueError(f'SourbyPatcher {label} declares paperweight {declared}; gradle.properties '
                             f'paperweightVersion is {expected}. Update the toolchain pins and this version together.')
        print(f'Verified SourbyPatcher {label} (paperweight {declared})')


def publish(checkouts, repository):
    checkouts = Path(checkouts).resolve()
    repository = Path(repository).resolve()
    lock = json.loads((ROOT / 'build-data/private-toolchain.lock.json').read_text())
    for name, relative in [('SourbyPatcher', 'paper-toolchain'), ('SourbyClip', '.')]:
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
