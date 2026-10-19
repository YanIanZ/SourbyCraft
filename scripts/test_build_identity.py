import io
import json
import tempfile
import subprocess
from build_identity import identity
from pathlib import Path
import unittest
import zipfile
from verify_build_identity import verify, verify_api_version


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
            def make(dirty='false', manifest_tree=tree):
                suffix = '-dirty' if dirty == 'true' else ''
                return jar(directory,
                    f'buildNumber=47\nbuild=47{suffix}\ngitCommit={commit}\ntreeHash={tree}\ndirty={dirty}\n',
                    f'Implementation-Version: Build 47{suffix}\r\nGit-Commit: {commit}\r\nGit-Tree-Hash: {manifest_tree}\r\nGit-Dirty: {dirty}\r\n')
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
