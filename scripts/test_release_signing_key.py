from pathlib import Path
import os
import stat
import tempfile
import unittest

import release_signing_key as rsk

try:
    OPENSSL = rsk.find_openssl()
except rsk.SigningKeyError:
    OPENSSL = None

needs_openssl = unittest.skipUnless(OPENSSL, 'OpenSSL 3+ not installed')


def fake_binary(directory, banner):
    path = Path(directory) / 'openssl'
    path.write_text(f'#!/bin/sh\necho "{banner}"\n')
    path.chmod(0o755)
    return str(path)


class FindOpensslTest(unittest.TestCase):
    def test_libressl_is_refused_because_it_cannot_sign_raw_input(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(rsk.SigningKeyError) as raised:
                rsk.find_openssl(fake_binary(directory, 'LibreSSL 3.3.6'))
            self.assertIn('OpenSSL 3', str(raised.exception))

    def test_openssl_1_is_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(rsk.SigningKeyError):
                rsk.find_openssl(fake_binary(directory, 'OpenSSL 1.1.1w  11 Sep 2023'))

    def test_openssl_3_and_newer_are_accepted(self):
        for banner in ('OpenSSL 3.0.13 30 Jan 2024', 'OpenSSL 4.0.3 29 Sep 2026'):
            with self.subTest(banner=banner), tempfile.TemporaryDirectory() as directory:
                binary = fake_binary(directory, banner)
                self.assertEqual(rsk.find_openssl(binary), binary)


@needs_openssl
class GenerateTest(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory()
        self.addCleanup(self.scratch.cleanup)
        self.base = Path(self.scratch.name)
        self.repo = self.base / 'repo'
        self.repo.mkdir()

    def test_the_pair_is_written_private_and_passes_the_ci_sign_verify_round_trip(self):
        key, pub = rsk.generate(self.base / 'keys', OPENSSL, root=self.repo)
        self.assertEqual(stat.S_IMODE(key.stat().st_mode), 0o600)
        self.assertEqual(stat.S_IMODE((self.base / 'keys').stat().st_mode), 0o700)
        self.assertIn('BEGIN PRIVATE KEY', key.read_text())
        self.assertIn('BEGIN PUBLIC KEY', pub.read_text())
        self.assertEqual(rsk.self_test(OPENSSL, key, pub), 64)
        self.assertRegex(rsk.fingerprint(OPENSSL, pub), r'^[0-9a-f]{64}$')

    def test_two_runs_make_different_keys(self):
        _, first = rsk.generate(self.base / 'a', OPENSSL, root=self.repo)
        _, second = rsk.generate(self.base / 'b', OPENSSL, root=self.repo)
        self.assertNotEqual(rsk.fingerprint(OPENSSL, first), rsk.fingerprint(OPENSSL, second))

    def test_the_private_key_is_never_written_inside_the_repository(self):
        with self.assertRaises(rsk.SigningKeyError):
            rsk.generate(self.repo / 'keys', OPENSSL, root=self.repo)
        self.assertFalse((self.repo / 'keys').exists())

    def test_an_existing_key_is_never_overwritten(self):
        key, _ = rsk.generate(self.base / 'keys', OPENSSL, root=self.repo)
        before = key.read_bytes()
        with self.assertRaises(rsk.SigningKeyError):
            rsk.generate(self.base / 'keys', OPENSSL, root=self.repo)
        self.assertEqual(key.read_bytes(), before)

    def test_install_copies_the_public_key_and_is_idempotent(self):
        _, pub = rsk.generate(self.base / 'keys', OPENSSL, root=self.repo)
        target, copied = rsk.install_pub(pub, OPENSSL, root=self.repo)
        self.assertTrue(copied)
        self.assertEqual(target, self.repo / rsk.PUB_RESOURCE)
        self.assertEqual(target.read_bytes(), pub.read_bytes())
        self.assertEqual(rsk.install_pub(pub, OPENSSL, root=self.repo), (target, False))

    def test_install_refuses_to_replace_a_different_public_key(self):
        _, first = rsk.generate(self.base / 'a', OPENSSL, root=self.repo)
        _, second = rsk.generate(self.base / 'b', OPENSSL, root=self.repo)
        target, _ = rsk.install_pub(first, OPENSSL, root=self.repo)
        with self.assertRaises(rsk.SigningKeyError):
            rsk.install_pub(second, OPENSSL, root=self.repo)
        self.assertEqual(target.read_bytes(), first.read_bytes())

    def test_main_never_prints_the_private_key(self):
        import contextlib
        import io
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = rsk.main(['--out-dir', str(self.base / 'keys'), '--openssl', OPENSSL])
        self.assertEqual(code, 0)
        key = (self.base / 'keys' / rsk.KEY_NAME).read_text()
        body = ''.join(line for line in key.splitlines() if not line.startswith('-----'))
        self.assertNotIn(body, out.getvalue())
        self.assertIn(rsk.SECRET_NAME, out.getvalue())


class RepositoryTest(unittest.TestCase):
    def test_no_private_key_is_tracked_and_none_is_ignored_silently(self):
        ignore = (rsk.ROOT / '.gitignore').read_text()
        self.assertIn(rsk.KEY_NAME, ignore)
        self.assertFalse((rsk.ROOT / rsk.PUB_RESOURCE.parent / rsk.KEY_NAME).exists())


if __name__ == '__main__':
    unittest.main()
