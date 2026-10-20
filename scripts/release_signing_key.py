#!/usr/bin/env python3
"""Generate the Ed25519 release-signing key pair for the auto-updater (audit SEC-2).

The private key is written OUTSIDE the repository (mode 0600) and never printed. The public key
is what ReleaseSignatureVerifier reads from META-INF/sourbycraft-release.pub; `--install-pub`
copies it there. Before returning, the pair is checked with the exact `openssl pkeyutl -rawin`
commands the CI signing step in docs/development/AUTO-UPDATER.md uses, so a key that works here
signs and verifies there.

Nothing is uploaded: setting the GitHub secret is printed as a command for the owner to run.
"""
import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PUB_RESOURCE = Path('sourbycraft-server/src/main/resources/META-INF/sourbycraft-release.pub')
KEY_NAME = 'sourbycraft-release.key'
PUB_NAME = 'sourbycraft-release.pub'
SECRET_NAME = 'SOURBYCRAFT_RELEASE_SIGNING_KEY'
DEFAULT_OUT = Path.home() / '.sourbycraft' / 'release-signing'
# Homebrew keeps OpenSSL off PATH ahead of macOS's LibreSSL, which has no `pkeyutl -rawin`.
CANDIDATES = ('openssl', '/opt/homebrew/bin/openssl', '/opt/homebrew/opt/openssl@3/bin/openssl',
              '/usr/local/opt/openssl@3/bin/openssl')


class SigningKeyError(Exception):
    pass


def run(openssl, *args, data=None):
    result = subprocess.run([openssl, *args], input=data, capture_output=True)
    if result.returncode != 0:
        raise SigningKeyError(f"openssl {' '.join(args)} failed: {result.stderr.decode(errors='replace').strip()}")
    return result.stdout


def openssl_major(openssl):
    """The OpenSSL major version, or None for LibreSSL or anything that is not OpenSSL."""
    try:
        text = run(openssl, 'version').decode()
    except (OSError, SigningKeyError):
        return None
    match = re.match(r'OpenSSL (\d+)\.', text)
    return int(match.group(1)) if match else None


def find_openssl(explicit=None):
    for candidate in ([explicit] if explicit else CANDIDATES):
        path = shutil.which(candidate) or (candidate if Path(candidate).is_file() else None)
        if path and (openssl_major(path) or 0) >= 3:
            return path
    raise SigningKeyError('OpenSSL 3 or newer is required (LibreSSL lacks `pkeyutl -rawin`); '
                          'install it (e.g. `brew install openssl@3`) or pass --openssl PATH')


def inside(path, root):
    try:
        path.resolve().relative_to(root.resolve())
        return True
    except ValueError:
        return False


def fingerprint(openssl, pub):
    """SHA-256 of the DER SubjectPublicKeyInfo: the same bytes whichever text armour is used."""
    der = run(openssl, 'pkey', '-pubin', '-in', str(pub), '-outform', 'DER')
    return hashlib.sha256(der).hexdigest()


def self_test(openssl, key, pub):
    """Sign and verify a random blob exactly as the CI step does; return the signature length."""
    with tempfile.TemporaryDirectory() as scratch:
        blob = Path(scratch) / 'probe.jar'
        sig = Path(scratch) / 'probe.jar.sig'
        blob.write_bytes(os.urandom(4096))
        run(openssl, 'pkeyutl', '-sign', '-rawin', '-inkey', str(key), '-in', str(blob), '-out', str(sig))
        run(openssl, 'pkeyutl', '-verify', '-rawin', '-pubin', '-inkey', str(pub), '-in', str(blob),
            '-sigfile', str(sig))
        length = sig.stat().st_size
        blob.write_bytes(os.urandom(4096))
        tampered = subprocess.run([openssl, 'pkeyutl', '-verify', '-rawin', '-pubin', '-inkey', str(pub),
                                   '-in', str(blob), '-sigfile', str(sig)], capture_output=True)
        if tampered.returncode == 0:
            raise SigningKeyError('self-test: a signature verified over different bytes')
    if length != 64:
        raise SigningKeyError(f'self-test: Ed25519 signature is {length} bytes, expected 64')
    return length


def generate(out_dir, openssl, root=ROOT):
    out_dir = Path(out_dir).expanduser()
    if inside(out_dir, root):
        raise SigningKeyError(f'refusing to write the private key inside the repository: {out_dir}')
    key, pub = out_dir / KEY_NAME, out_dir / PUB_NAME
    for existing in (key, pub):
        if existing.exists():
            raise SigningKeyError(f'{existing} already exists; refusing to overwrite a signing key')
    old_umask = os.umask(0o077)
    try:
        out_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
        run(openssl, 'genpkey', '-algorithm', 'Ed25519', '-out', str(key))
        os.chmod(key, 0o600)
        run(openssl, 'pkey', '-in', str(key), '-pubout', '-out', str(pub))
        os.chmod(pub, 0o644)
    finally:
        os.umask(old_umask)
    self_test(openssl, key, pub)
    return key, pub


def install_pub(pub, openssl, root=ROOT):
    """Copy the public key to the jar resource; refuse to replace a different key."""
    target = root / PUB_RESOURCE
    if target.exists():
        if fingerprint(openssl, target) == fingerprint(openssl, pub):
            return target, False
        raise SigningKeyError(f'{target} already holds a different public key; replacing it would make '
                              'servers that already enforce reject every future release')
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(pub, target)
    return target, True


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--out-dir', default=str(DEFAULT_OUT),
                        help=f'where the key pair is written (default {DEFAULT_OUT}); must be outside the repo')
    parser.add_argument('--openssl', help='OpenSSL 3+ binary to use (default: first one found)')
    parser.add_argument('--install-pub', action='store_true',
                        help=f'also copy the public key to {PUB_RESOURCE}')
    args = parser.parse_args(argv)
    try:
        openssl = find_openssl(args.openssl)
        key, pub = generate(args.out_dir, openssl)
        print(f'private key : {key}  (mode 0600, never commit or paste it)')
        print(f'public key  : {pub}')
        print(f'fingerprint : sha256:{fingerprint(openssl, pub)}')
        print('self-test   : signed and verified a probe with the CI commands; tampered bytes rejected')
        if args.install_pub:
            target, copied = install_pub(pub, openssl)
            print(f'installed   : {target}' if copied else f'unchanged   : {target} already holds this key')
        print('\nNext (owner):')
        print(f'  1. Back up {key} somewhere safe (password manager); a lost key cannot be replaced '
              'for servers that already enforce, except by a manual update.')
        print(f'  2. gh secret set {SECRET_NAME} --repo YanIanZ/SourbyCraft < {key}')
        if not args.install_pub:
            print(f'  3. Re-run with --install-pub, or copy {pub} to {PUB_RESOURCE}')
        print('  Then ship the public key and the CI signing step in the same release '
              '(docs/development/AUTO-UPDATER.md, "Turning it on").')
        return 0
    except SigningKeyError as failure:
        print(f'release_signing_key: {failure}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
