import os
import io
import json
import tempfile
import subprocess
from build_identity import PROVENANCE_KEYS, directory_hash, identity, provenance
from pathlib import Path
import unittest
import zipfile
from verify_build_identity import verify, verify_api_version


def provenance_lines(values=None, jdk='Eclipse Adoptium 25.0.1+8-LTS'):
    """(properties text, manifest text) for a complete provenance block."""
    values = dict(values or {})
    values.setdefault('Jdk', jdk)
    for key in PROVENANCE_KEYS:
        values.setdefault(key, 'd' * 64)
    props = ''.join(f'provenance.{k}={v}\n' for k, v in values.items())
    manifest = ''.join(f'Provenance-{k}: {v}\r\n' for k, v in values.items())
    return props, manifest


def jar(directory, properties, manifest, api_metadata=None):
    path = Path(directory) / "server.jar"
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("META-INF/sourbycraft-build.properties", properties)
        archive.writestr("META-INF/MANIFEST.MF", manifest)
        if api_metadata is not None:
            nested = io.BytesIO()
            with zipfile.ZipFile(nested, "w") as api_jar:
                api_jar.writestr("apiVersioning.json", json.dumps(api_metadata))
            archive.writestr(
                "META-INF/libraries/dev/iyanz/sourbycraft/sourbyapi/26.2-DEV/sourbyapi-26.2-DEV.jar",
                nested.getvalue(),
            )
    return path


class BuildIdentityTest(unittest.TestCase):
    def test_source_fingerprint_tracks_uncommitted_content_without_changing_index(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def git(*args):
                return subprocess.check_output(['git', '-C', directory, *args])
            git('init', '-q')
            (root / 'source').write_text('original')
            git('add', 'source')
            git('-c', 'user.name=Test', '-c', 'user.email=test@example.invalid', 'commit', '-qm', 'fixture')
            clean = identity(root)
            index = git('write-tree')
            self.assertEqual('false', clean['dirty'])
            (root / 'source').write_text('changed')
            dirty = identity(root)
            self.assertEqual('true', dirty['dirty'])
            self.assertNotEqual(clean['treeHash'], dirty['treeHash'])
            self.assertEqual(index, git('write-tree'))
            (root / 'new').write_text('untracked')
            self.assertNotEqual(dirty['treeHash'], identity(root)['treeHash'])
            (root / 'source').unlink()
            self.assertNotEqual(dirty['treeHash'], identity(root)['treeHash'])

    def test_materialized_working_copy_content_and_dirty_state_are_included(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def git(where, *args):
                return subprocess.check_output(['git', '-C', str(where), *args])
            git(root, 'init', '-q')
            (root / '.gitignore').write_text('paper-api/\n')
            git(root, 'add', '.gitignore')
            git(root, '-c', 'user.name=Test', '-c', 'user.email=test@example.invalid', 'commit', '-qm', 'fixture')
            nested = root / 'paper-api'
            nested.mkdir()
            git(nested, 'init', '-q')
            (nested / 'source').write_text('original')
            git(nested, 'add', 'source')
            git(nested, '-c', 'user.name=Test', '-c', 'user.email=test@example.invalid', 'commit', '-qm', 'source')
            clean = identity(root)
            (nested / 'source').write_text('local edit')
            dirty = identity(root)
            self.assertEqual('false', clean['dirty'])
            self.assertEqual('true', dirty['dirty'])
            self.assertNotEqual(clean['treeHash'], dirty['treeHash'])

    def fixture_repo(self, root):
        root.mkdir(parents=True, exist_ok=True)
        def git(*args):
            return subprocess.check_output(['git', '--no-optional-locks', '-C', str(root), *args])
        git('init', '-q')
        (root / '.gitignore').write_text('materialized/\nsourbyapi/build.gradle.kts\nsourbycraft-server/build.gradle.kts\nsourbycraft-server/src/minecraft/resources/\n.gradle/\n')
        git('add', '.')
        git('-c', 'user.name=Test', '-c', 'user.email=test@example.invalid', 'commit', '-qm', 'fixture')
        return git

    def test_minecraft_resources_are_hashed_and_mark_dirty(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture_repo(root)
            resources = root / 'sourbycraft-server/src/minecraft/resources'
            git = self.fixture_repo(resources)
            (resources / 'asset.json').write_text('{}')
            git('add', '.')
            git('-c', 'user.name=Test', '-c', 'user.email=test@example.invalid', 'commit', '-qm', 'asset')
            clean = identity(root)
            (resources / 'asset.json').write_text('{"changed":true}')
            dirty = identity(root)
            self.assertEqual('false', clean['dirty'])
            self.assertEqual('true', dirty['dirty'])
            self.assertNotEqual(clean['treeHash'], dirty['treeHash'])

    def test_generated_build_files_match_pinned_upstream_plus_patch(self):
        for module, upstream in [('sourbyapi', 'paper-api'), ('sourbycraft-server', 'paper-server')]:
            with self.subTest(module=module), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                git = self.fixture_repo(root)
                cache = root / '.gradle/caches/paperweight/upstreams/paper'
                upstream_git = self.fixture_repo(cache)
                (cache / upstream).mkdir()
                (cache / upstream / 'build.gradle.kts').write_text('before\nold\nafter\n')
                upstream_git('add', '.')
                upstream_git('-c', 'user.name=Test', '-c', 'user.email=test@example.invalid', 'commit', '-qm', 'upstream')
                pin = upstream_git('rev-parse', 'HEAD').decode().strip()
                (root / 'gradle.properties').write_text(f'paperRef={pin}\n')
                (root / module).mkdir()
                patch = root / module / 'build.gradle.kts.patch'
                patch.write_text('--- a/build.gradle.kts\n+++ b/build.gradle.kts\n@@ -1,3 +_,3 @@\n before\n-old\n+new\n after\n')
                generated = root / module / 'build.gradle.kts'
                generated.write_text('before\nnew\nafter\n')
                git('add', '.')
                git('-c', 'user.name=Test', '-c', 'user.email=test@example.invalid', 'commit', '-qm', 'inputs')
                clean = identity(root)
                self.assertEqual('false', clean['dirty'])
                generated.write_text('before\nhand edited\nafter\n')
                drift = identity(root)
                self.assertEqual('true', drift['dirty'])
                self.assertNotEqual(clean['treeHash'], drift['treeHash'])
                # A matching generated file cannot certify an unavailable upstream pin.
                generated.write_text('before\nnew\nafter\n')
                import shutil
                shutil.rmtree(cache)
                unknown = identity(root)
                self.assertEqual('true', unknown['dirty'])
                self.assertEqual('unknown', unknown['treeHash'])

    def test_status_does_not_rewrite_index_stat_cache(self):
        import os
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            git = self.fixture_repo(root)
            source = root / 'source'
            source.write_text('same bytes')
            git('add', '.')
            git('-c', 'user.name=Test', '-c', 'user.email=test@example.invalid', 'commit', '-qm', 'source')
            before = (root / '.git/index').read_bytes()
            stamp = source.stat().st_mtime_ns + 5_000_000_000
            os.utime(source, ns=(stamp, stamp))
            identity(root)
            self.assertEqual(before, (root / '.git/index').read_bytes())

    def test_missing_git_or_untracked_nested_repo_falls_back_dirty_unknown(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.assertEqual({'commit': 'unknown', 'treeHash': 'unknown', 'dirty': 'true'}, identity(root))
            self.fixture_repo(root)
            self.fixture_repo(root / 'untracked-repo')
            result = identity(root)
            self.assertEqual('true', result['dirty'])
            self.assertEqual('unknown', result['treeHash'])

    def test_source_export_inside_another_repo_cannot_borrow_its_commit(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture_repo(root)
            export = root / 'export'
            export.mkdir()
            self.assertEqual({'commit': 'unknown', 'treeHash': 'unknown', 'dirty': 'true'}, identity(export))

    def test_unknown_local_identity_is_inspectable_but_not_publishable(self):
        with tempfile.TemporaryDirectory() as directory:
            path = jar(directory,
                'buildNumber=47\nbuild=47-dirty\ngitCommit=unknown\ntreeHash=unknown\ndirty=true\n',
                'Implementation-Version: Build 47-dirty\r\nGit-Commit: unknown\r\nGit-Tree-Hash: unknown\r\nGit-Dirty: true\r\n')
            self.assertEqual('true', verify(path, 47)['dirty'])
            with self.assertRaises(ValueError):
                verify(path, 47, commit='a' * 40)

    def test_commit_gate_rejects_dirty_wrong_or_missing_provenance(self):
        with tempfile.TemporaryDirectory() as directory:
            commit = 'a' * 40
            tree = 'b' * 64
            props, manifest = provenance_lines()
            def make(dirty='false', manifest_tree=tree):
                suffix = '-dirty' if dirty == 'true' else ''
                return jar(directory,
                    f'buildNumber=47\nbuild=47{suffix}\ngitCommit={commit}\ntreeHash={tree}\ndirty={dirty}\n' + props,
                    f'Implementation-Version: Build 47{suffix}\r\nGit-Commit: {commit}\r\nGit-Tree-Hash: {manifest_tree}\r\nGit-Dirty: {dirty}\r\n' + manifest)
            self.assertEqual(commit, verify(make(), 47, commit=commit)['gitCommit'])
            with self.assertRaises(ValueError):
                verify(make(), 47, commit='c' * 40)
            with self.assertRaises(ValueError):
                verify(make('true'), 47, commit=commit)
            self.assertEqual('true', verify(make('true'), 47)['dirty'])
            with self.assertRaises(ValueError):
                verify(make(manifest_tree='c' * 64), 47, commit=commit)
            with self.assertRaises(ValueError):
                verify(jar(directory, 'buildNumber=47\nbuild=47\n', 'Implementation-Version: Build 47\r\n'), 47, commit=commit)

    def test_channel_mismatch_blocks_mislabeled_artifact(self):
        with tempfile.TemporaryDirectory() as directory:
            path = jar(directory, "buildNumber=47\nbuild=47\nversion=26.2-DEV\n",
                       "Implementation-Version: Build 47\r\n")
            self.assertEqual("26.2-DEV", verify(path, 47, "DEV")["version"])
            with self.assertRaises(ValueError):
                verify(path, 47, "REL")

    def test_mismatched_manifest_blocks_publication(self):
        with tempfile.TemporaryDirectory() as directory:
            for manifest_number in (46, 47):
                path = jar(directory, "buildNumber=47\nbuild=47\n",
                           f"Implementation-Version: Build {manifest_number}\r\n")
                if manifest_number == 46:
                    with self.assertRaises(ValueError):
                        verify(path, 47)
                else:
                    self.assertEqual("47", verify(path, 47)["buildNumber"])

    def test_an_upstream_platform_letter_is_rejected(self):
        # Build 47+ public identity carries no Canvas/Folia/Paper suffix (AGENTS.md).
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                verify(jar(directory, "buildNumber=47\nbuild=47c\n", "Implementation-Version: Build 47\r\n"), 47)
            with self.assertRaises(ValueError):
                verify(jar(directory, "buildNumber=47\nbuild=47\n", "Implementation-Version: build 47c\r\n"), 47)

    def test_bukkit_api_version_stays_separate_from_build_channel(self):
        with tempfile.TemporaryDirectory() as directory:
            props = "buildNumber=47\nbuild=47\nversion=26.2-DEV\n"
            manifest = "Implementation-Version: Build 47\r\n"
            path = jar(directory, props, manifest,
                       {"version": "26.2-R0.1-SNAPSHOT", "currentApiVersion": "26.2"})
            self.assertEqual("26.2-DEV", verify(path, 47, "DEV", "26.2")["version"])
            self.assertEqual("26.2-R0.1-SNAPSHOT", verify_api_version(path, "26.2")["version"])

            path = jar(directory, props, manifest,
                       {"version": "26.2-DEV", "currentApiVersion": "26.2"})
            with self.assertRaisesRegex(ValueError, "plugin-compatible"):
                verify(path, 47, "DEV", "26.2")


if __name__ == "__main__":
    unittest.main()


class BuildProvenanceTest(unittest.TestCase):
    """TODO Inovasi "provenance manifest": patch-directory/baseline/toolchain hashes + JDK."""

    COMMIT = 'a' * 40
    TREE = 'b' * 64

    def make(self, directory, props_extra='', manifest_extra=''):
        return jar(directory,
            f'buildNumber=47\nbuild=47\ngitCommit={self.COMMIT}\ntreeHash={self.TREE}\ndirty=false\n' + props_extra,
            f'Implementation-Version: Build 47\r\nGit-Commit: {self.COMMIT}\r\nGit-Tree-Hash: {self.TREE}\r\nGit-Dirty: false\r\n' + manifest_extra)

    def source_tree(self, root):
        for relative in ('sourbycraft-server/minecraft-patches/features', 'sourbycraft-server/paper-patches/features',
                         'sourbyapi/paper-patches/features', 'build-data'):
            (root / relative).mkdir(parents=True, exist_ok=True)
        (root / 'sourbycraft-server/minecraft-patches/features/0001-a.patch').write_text('mc\n')
        (root / 'sourbycraft-server/paper-patches/features/0001-b.patch').write_text('paper\n')
        (root / 'sourbyapi/paper-patches/features/0001-c.patch').write_text('api\n')
        (root / 'build-data/engine-baseline.sha256').write_text('baseline\n')
        (root / 'build-data/private-toolchain.lock.json').write_text('{}\n')
        (root / 'gradle.properties').write_text(
            'patcherSha256=' + '1' * 64 + '\npatcherPomSha256=' + '2' * 64
            + '\npatcherModuleSha256=' + '3' * 64 + '\nclipSha256=' + '4' * 64 + '\n')

    def test_complete_matching_provenance_passes_the_publication_gate(self):
        with tempfile.TemporaryDirectory() as directory:
            props, manifest = provenance_lines()
            info = verify(self.make(directory, props, manifest), 47, commit=self.COMMIT)
            self.assertEqual('d' * 64, info['provenance.Minecraft-Features'])

    def test_publication_requires_every_provenance_key(self):
        with tempfile.TemporaryDirectory() as directory:
            for key in PROVENANCE_KEYS:
                with self.subTest(key=key):
                    values = {k: ('Eclipse Adoptium 25.0.1+8-LTS' if k == 'Jdk' else 'd' * 64) for k in PROVENANCE_KEYS if k != key}
                    props = ''.join(f'provenance.{k}={v}\n' for k, v in values.items())
                    manifest = ''.join(f'Provenance-{k}: {v}\r\n' for k, v in values.items())
                    with self.assertRaisesRegex(ValueError, 'Missing build provenance'):
                        verify(self.make(directory, props, manifest), 47, commit=self.COMMIT)

    def test_manifest_and_properties_must_agree_on_each_provenance_value(self):
        with tempfile.TemporaryDirectory() as directory:
            props, _ = provenance_lines()
            _, manifest = provenance_lines({'Paper-Features': 'e' * 64})
            with self.assertRaisesRegex(ValueError, 'disagree on provenance Paper-Features'):
                verify(self.make(directory, props, manifest), 47)
            props, manifest = provenance_lines()
            with self.assertRaisesRegex(ValueError, 'properties lack'):
                verify(self.make(directory, props, manifest + 'Provenance-Extra: x\r\n'), 47)

    def test_values_must_be_sha256_and_the_jdk_must_be_the_25_toolchain(self):
        with tempfile.TemporaryDirectory() as directory:
            props, manifest = provenance_lines({'Engine-Baseline': 'not-a-hash'})
            with self.assertRaisesRegex(ValueError, 'not a SHA-256'):
                verify(self.make(directory, props, manifest), 47)
            props, manifest = provenance_lines(jdk='Eclipse Adoptium 21.0.4+7-LTS')
            with self.assertRaisesRegex(ValueError, 'Java 25 toolchain'):
                verify(self.make(directory, props, manifest), 47)
            props, manifest = provenance_lines(jdk='Oracle Corporation 25')
            verify(self.make(directory, props, manifest), 47)

    def test_unknown_values_are_local_only(self):
        with tempfile.TemporaryDirectory() as directory:
            props, manifest = provenance_lines({'Minecraft-Features': 'unknown'})
            self.assertEqual('unknown', verify(self.make(directory, props, manifest), 47)['provenance.Minecraft-Features'])
            with self.assertRaisesRegex(ValueError, 'not a SHA-256'):
                verify(self.make(directory, props, manifest), 47, commit=self.COMMIT)

    def test_a_jar_without_provenance_is_still_inspectable_locally(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertEqual('47', verify(self.make(directory), 47)['buildNumber'])

    def test_directory_hash_tracks_content_names_and_new_files_but_not_dot_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'sub').mkdir()
            (root / 'a.patch').write_text('one')
            (root / 'sub/b.patch').write_text('two')
            base = directory_hash(root)
            self.assertEqual(base, directory_hash(root))
            (root / '.DS_Store').write_text('finder')
            self.assertEqual(base, directory_hash(root))
            (root / 'a.patch').write_text('changed')
            changed = directory_hash(root)
            self.assertNotEqual(base, changed)
            (root / 'a.patch').write_text('one')
            (root / 'a.patch').rename(root / 'c.patch')
            self.assertNotEqual(base, directory_hash(root))
            (root / 'c.patch').rename(root / 'a.patch')
            (root / 'new.patch').write_text('three')
            self.assertNotEqual(base, directory_hash(root))
            (root / 'new.patch').unlink()
            os.symlink(root / 'a.patch', root / 'link.patch')
            with self.assertRaises(ValueError):
                directory_hash(root)

    def test_provenance_reads_the_tree_and_marks_missing_inputs_unknown(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.assertTrue(all(v == 'unknown' for v in provenance(root).values()))
            self.source_tree(root)
            result = provenance(root)
            self.assertEqual(set(PROVENANCE_KEYS) - {'Jdk'}, set(result))
            self.assertEqual('1' * 64, result['Patcher-Sha256'])
            self.assertEqual('4' * 64, result['Clip-Sha256'])
            self.assertEqual(directory_hash(root / 'sourbyapi/paper-patches'), result['Api-Paper-Patches'])
            import hashlib
            self.assertEqual(hashlib.sha256(b'baseline\n').hexdigest(), result['Engine-Baseline'])

    def test_source_root_check_detects_a_patch_edit_after_the_build(self):
        with tempfile.TemporaryDirectory() as directory, tempfile.TemporaryDirectory() as jars:
            root = Path(directory)
            self.source_tree(root)
            values = dict(provenance(root))
            props, manifest = provenance_lines(values)
            path = self.make(jars, props, manifest)
            verify(path, 47, commit=self.COMMIT, source_root=root)
            (root / 'sourbycraft-server/paper-patches/features/0002-new.patch').write_text('late\n')
            with self.assertRaisesRegex(ValueError, 'Paper-Features differs from the source tree'):
                verify(path, 47, commit=self.COMMIT, source_root=root)

    def test_this_checkout_provides_every_source_provenance_value(self):
        root = Path(__file__).resolve().parent.parent
        result = provenance(root)
        for key, value in result.items():
            self.assertRegex(value, r'^[0-9a-f]{64}$', key)
