#!/usr/bin/env python3
"""Generate the Ed25519 release-signing key pair for the auto-updater (audit SEC-2).

The private key is written OUTSIDE the repository (mode 0600) and never printed. The public key
is what ReleaseSignatureVerifier reads from META-INF/sourbycraft-release.pub; `--install-pub`
copies it there. Before returning, the pair is checked with the exact `openssl pkeyutl -rawin`
commands the CI signing step in docs/development/AUTO-UPDATER.md uses, so a key that works here
signs and verifies there.

Nothing is uploaded: setting the GitHub secret is printed as a command for the owner to run.

`--add-pub` appends the new public key to the resource as an additional trusted key instead of
refusing because a different key is already there (ReleaseSignatureVerifier accepts a signature
from any block). That is step one of a key rotation; the rotation itself needs owner approval.
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


def key_blocks(text):
    """Every PEM public-key block in a key file, armour included, in file order."""
    return re.findall(r'-----BEGIN PUBLIC KEY-----\s.*?-----END PUBLIC KEY-----', text, flags=re.S)


def fingerprints(openssl, path):
    """The fingerprint of every PEM public key in a trusted-key file (it may hold several)."""
    return [hashlib.sha256(run(openssl, 'pkey', '-pubin', '-outform', 'DER', data=block.encode())).hexdigest()
            for block in key_blocks(Path(path).read_text())]


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


def install_pub(pub, openssl, root=ROOT, add=False):
    """Copy the public key to the jar resource; refuse to replace a different key.

    With add=True a key the resource does not hold yet is appended as an additional trusted key
    (the old ones stay first) instead of being refused. Returns (target, changed).
    """
    target = root / PUB_RESOURCE
    if target.exists():
        if fingerprint(openssl, pub) in fingerprints(openssl, target):
            return target, False
        if not add:
            raise SigningKeyError(f'{target} already holds a different public key; replacing it would make '
                                  'servers that already enforce reject every future release '
                                  '(--add-pub trusts it in addition, for an owner-approved key rotation)')
        blocks = key_blocks(pub.read_text())
        if len(blocks) != 1:
            raise SigningKeyError(f'{pub} must hold exactly one PEM public key, found {len(blocks)}')
        text = target.read_text()
        target.write_text(text + ('' if text.endswith('\n') else '\n') + blocks[0] + '\n')
        return target, True
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
    parser.add_argument('--add-pub', action='store_true',
                        help=f'append the public key to {PUB_RESOURCE} as an additional trusted key '
                             '(key rotation; needs owner approval)')
    args = parser.parse_args(argv)
    try:
        openssl = find_openssl(args.openssl)
        key, pub = generate(args.out_dir, openssl)
        print(f'private key : {key}  (mode 0600, never commit or paste it)')
        print(f'public key  : {pub}')
        print(f'fingerprint : sha256:{fingerprint(openssl, pub)}')
        print('self-test   : signed and verified a probe with the CI commands; tampered bytes rejected')
        rotating = False
        if args.install_pub or args.add_pub:
            rotating = args.add_pub and (ROOT / PUB_RESOURCE).exists()
            target, copied = install_pub(pub, openssl, add=args.add_pub)
            print((f'added       : {target} (additional trusted key)' if rotating else f'installed   : {target}')
                  if copied else f'unchanged   : {target} already holds this key')
            rotating = rotating and copied
        print('\nNext (owner):')
        print(f'  1. Back up {key} somewhere safe (password manager); a lost key cannot be replaced '
              'for servers that already enforce, except by a manual update.')
        if rotating:
            print('  2. Keep the CI secret on the OLD key and ship at least one release built with both keys.')
            print(f'  3. Only then: gh secret set {SECRET_NAME} --repo YanIanZ/SourbyCraft < {key}. Moving the new '
                  'block first in the resource is optional (the release job, like servers, accepts a signature '
                  'from any block).')
            print('  Servers that never ran a build trusting the new key need a manual update after the switch.')
            return 0
        print(f'  2. gh secret set {SECRET_NAME} --repo YanIanZ/SourbyCraft < {key}')
        if not (args.install_pub or args.add_pub):
            print(f'  3. Re-run with --install-pub, or copy {pub} to {PUB_RESOURCE}')
        print('  Then ship the public key and the CI signing step in the same release '
              '(docs/development/AUTO-UPDATER.md, "Turning it on").')
        return 0
    except SigningKeyError as failure:
        print(f'release_signing_key: {failure}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
