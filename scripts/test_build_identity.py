import os
import io
import json
import tempfile
import subprocess
from build_identity import PROVENANCE_KEYS, directory_hash, identity, provenance
from pathlib import Path
import unittest
import zipfile
from verify_build_identity import (GPL3_SHA256, LICENCE_SOURCE_DIR, verify, verify_api_version,
                                   verify_licences)


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

    def test_dirty_counts_tracked_and_staged_changes_but_not_untracked_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            git = self.fixture_repo(root)
            (root / 'source').write_text('original')
            git('add', 'source')
            git('-c', 'user.name=Test', '-c', 'user.email=test@example.invalid', 'commit', '-qm', 'source')
            clean = identity(root)
            self.assertEqual('false', clean['dirty'])
            # An untracked file (AGENT-COORDINATION.md, scratch notes) is not a tracked change...
            (root / 'AGENT-COORDINATION.md').write_text('notes')
            untracked = identity(root)
            self.assertEqual('false', untracked['dirty'])
            # ...but it is still hashed as a source input, so the fingerprint is not a clean checkout's.
            self.assertNotEqual(clean['treeHash'], untracked['treeHash'])
            # A tracked modification is dirty.
            (root / 'source').write_text('changed')
            self.assertEqual('true', identity(root)['dirty'])
            # A staged modification (worktree equal to the index) is dirty.
            git('add', 'source')
            self.assertEqual('true', identity(root)['dirty'])
            # Back to the committed content in both index and worktree: clean again.
            (root / 'source').write_text('original')
            git('add', 'source')
            self.assertEqual('false', identity(root)['dirty'])
            # A newly staged file is a tracked change; a deleted tracked file too.
            (root / 'added').write_text('x')
            git('add', 'added')
            self.assertEqual('true', identity(root)['dirty'])
            git('rm', '-q', '--cached', 'added')
            (root / 'added').unlink()
            self.assertEqual('false', identity(root)['dirty'])
            (root / 'source').unlink()
            self.assertEqual('true', identity(root)['dirty'])

    def test_untracked_files_in_a_materialized_repo_still_mark_the_build_dirty(self):
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
            self.assertEqual('false', identity(root)['dirty'])
            (root / 'root-notes').write_text('untracked in the root: ignored by the flag')
            self.assertEqual('false', identity(root)['dirty'])
            # The materialized repo is not root-tracked state; a file there that no patch commit holds
            # is compiled into the jar, so it keeps marking the build dirty (behaviour before this change).
            (nested / 'NewClass.java').write_text('class NewClass {}')
            self.assertEqual('true', identity(root)['dirty'])

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


REPO = Path(__file__).resolve().parent.parent


class LicenceFilesTest(unittest.TestCase):
    """SEC-13: the published jar carries the GPL text and source notice itself."""

    def gpl(self):
        return (REPO / LICENCE_SOURCE_DIR / 'LICENSE_SOURBYCRAFT').read_bytes()

    def notice(self):
        return (REPO / LICENCE_SOURCE_DIR / 'NOTICE_SOURBYCRAFT').read_bytes()

    def jar(self, directory, files):
        path = Path(directory) / 'slim.jar'
        with zipfile.ZipFile(path, 'w') as archive:
            archive.writestr('META-INF/MANIFEST.MF', 'Main-Class: x\r\n')
            archive.writestr('LICENSE.txt', 'MIT\n')
            for name, content in files.items():
                archive.writestr('META-INF/licenses/' + name, content)
        return path

    def test_checked_in_gpl_text_has_the_pinned_hash(self):
        import hashlib
        self.assertEqual(GPL3_SHA256, hashlib.sha256(self.gpl()).hexdigest())

    def test_jar_with_both_files_passes(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.jar(directory, {'LICENSE_SOURBYCRAFT': self.gpl(), 'NOTICE_SOURBYCRAFT': self.notice()})
            self.assertEqual(['META-INF/licenses/LICENSE_SOURBYCRAFT', 'META-INF/licenses/NOTICE_SOURBYCRAFT'],
                             verify_licences(path))

    def test_missing_or_altered_files_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, 'Missing licence file .*LICENSE_SOURBYCRAFT'):
                verify_licences(self.jar(directory, {'NOTICE_SOURBYCRAFT': self.notice()}))
            with self.assertRaisesRegex(ValueError, 'Missing licence file .*NOTICE_SOURBYCRAFT'):
                verify_licences(self.jar(directory, {'LICENSE_SOURBYCRAFT': self.gpl()}))
            with self.assertRaisesRegex(ValueError, 'not the GPL version 3 text'):
                verify_licences(self.jar(directory, {'LICENSE_SOURBYCRAFT': self.gpl()[:-10],
                                                     'NOTICE_SOURBYCRAFT': self.notice()}))
            with self.assertRaisesRegex(ValueError, 'lacks GPL-3.0-only'):
                verify_licences(self.jar(directory, {'LICENSE_SOURBYCRAFT': self.gpl(),
                                                     'NOTICE_SOURBYCRAFT': b'MIT licensed, see LICENSE_SOURBYCRAFT '
                                                                           b'https://github.com/YanIanZ/SourbyCraft'}))

    def test_source_root_requires_the_whole_licence_directory_byte_identical(self):
        source = {path.name: path.read_bytes() for path in (REPO / LICENCE_SOURCE_DIR).iterdir() if path.is_file()}
        with tempfile.TemporaryDirectory() as directory:
            self.assertEqual(len(source), len(verify_licences(self.jar(directory, source), REPO)))
            partial = {k: v for k, v in source.items() if k != 'LICENSE_PURPUR'}
            with self.assertRaisesRegex(ValueError, 'LICENSE_PURPUR'):
                verify_licences(self.jar(directory, partial), REPO)
            edited = dict(source, NOTICE_SOURBYCRAFT=source['NOTICE_SOURBYCRAFT'] + b'\n')
            with self.assertRaisesRegex(ValueError, 'differs from'):
                verify_licences(self.jar(directory, edited), REPO)
            with self.assertRaisesRegex(ValueError, 'not in the source tree'):
                verify_licences(self.jar(directory, dict(source, LICENSE_EXTRA=b'x')), REPO)

    def test_notice_describes_launcher_and_patched_jar_truthfully(self):
        text = self.notice().decode()
        self.assertNotIn('This jar does not contain the Minecraft server', text)
        self.assertIn('contains no\n  Minecraft server', text)
        self.assertIn("remains under Mojang's terms", text)


class HotfixBuildIdentityTest(unittest.TestCase):
    """A hotfix (hotfix/26.2-r48.1, tag v26.2-r48.1) stamps 48.1 into the build properties and
    Implementation-Version, which /ver and the auto-updater read; the manifest Build-Number stays
    the integer 48 because Paper parses it as an int (ServerBuildInfo.buildNumber())."""

    def make(self, directory, number='48.1', build_number='48'):
        return jar(directory, f'buildNumber={number}\nbuild={number}\nversion=26.2-REL\n',
                   f'Implementation-Version: Build {number}\r\nBuild-Number: {build_number}\r\n')

    def test_hotfix_identity_verifies(self):
        with tempfile.TemporaryDirectory() as directory:
            info = verify(self.make(directory), '48.1', 'REL')
            self.assertEqual('48.1', info['buildNumber'])

    def test_hotfix_requires_the_integer_manifest_build_number(self):
        with tempfile.TemporaryDirectory() as directory:
            for build_number in ('48.1', '49', ''):
                with self.subTest(build_number=build_number):
                    with self.assertRaises(ValueError):
                        verify(self.make(directory, build_number=build_number), '48.1')
            path = jar(directory, 'buildNumber=48.1\nbuild=48.1\n', 'Implementation-Version: Build 48.1\r\n')
            with self.assertRaises(ValueError):
                verify(path, '48.1')

    def test_hotfix_number_must_match_the_jar(self):
        with tempfile.TemporaryDirectory() as directory:
            for expected in ('48', '48.2', 48):
                with self.subTest(expected=expected):
                    with self.assertRaises(ValueError):
                        verify(self.make(directory), expected)

    def test_malformed_release_numbers_are_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            path = jar(directory, 'buildNumber=48.0\nbuild=48.0\n',
                       'Implementation-Version: Build 48.0\r\nBuild-Number: 48\r\n')
            for number in ('48.0', '48.1.2', '48-hotfix', '0', 'x'):
                with self.subTest(number=number):
                    with self.assertRaises(ValueError):
                        verify(path, number)

    def test_a_present_build_number_must_match_a_normal_build(self):
        with tempfile.TemporaryDirectory() as directory:
            ok = jar(directory, 'buildNumber=48\nbuild=48\n',
                     'Implementation-Version: Build 48\r\nBuild-Number: 48\r\n')
            self.assertEqual('48', verify(ok, 48)['buildNumber'])
            bad = jar(directory, 'buildNumber=48\nbuild=48\n',
                      'Implementation-Version: Build 48\r\nBuild-Number: 47\r\n')
            with self.assertRaises(ValueError):
                verify(bad, 48)


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
