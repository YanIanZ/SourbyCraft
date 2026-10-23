"""Offline provenance regression tests."""
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

import engine_baseline_origin as origin


class OriginTests(unittest.TestCase):
    def test_marker_classification(self):
        cases = [('+// Folia - region\n', 'folia'),
                 ('+// Canvas - tick\n', 'canvas'),
                 ('+// Folia - x\n+// Canvas - y\n', 'mixed'),
                 ('+// Paper - x\n+// Pufferfish fast\n', 'other'),
                 ('-// Folia - removed\n+++ b/Canvas.java\n+plain\n', 'unknown'),
                 ('+io.papermc.paper.threadedregions.TickRegions x;\n', 'folia'),
                 ('+dev.iyanz.aurora.engine.Config x;\n', 'canvas')]
        for patch, expected in cases:
            with self.subTest(expected=expected):
                self.assertEqual(origin.classify(patch, 'net/Test.java.patch')[0], expected)

    def test_import_normalization_history_and_determinism(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            def git(*args):
                return subprocess.check_output(['git', '-C', temp, '-c', 'core.autocrlf=false', *args])
            git('init', '-q')
            relative = origin.BASELINE_ROOTS[0] + '/Test.java.patch'
            path = root / relative
            path.parent.mkdir(parents=True)
            path.write_bytes(b'+// Folia - x\r\n')
            (root / 'build-data').mkdir()
            (root / origin.MANIFEST).write_text('0' * 64 + '  ' + relative + '\n')
            git('add', '.')
            git('-c', 'user.name=Test', '-c', 'user.email=test@example.invalid',
                '-c', 'core.autocrlf=false', 'commit', '-qm', 'import')
            commit = git('rev-parse', 'HEAD').decode().strip()
            path.write_bytes(b'+// Folia - x\n')
            first = origin.generate(root, commit)
            self.assertEqual(origin.render(first), origin.render(origin.generate(root, commit)))
            record = first['files'][0]
            self.assertEqual(record['import_sha256'], hashlib.sha256(b'+// Folia - x\n').hexdigest())
            self.assertFalse(record['modified_after_import'])
            path.write_text('+// Folia - changed\n')
            git('add', '.')
            git('-c', 'user.name=Test', '-c', 'user.email=test@example.invalid', 'commit', '-qm', 'change')
            changed = origin.generate(root, commit)['files'][0]
            self.assertTrue(changed['modified_after_import'])
            self.assertEqual(len(changed['change_commits']), 1)
            renamed = relative.replace('Test.java.patch', 'Renamed.java.patch')
            git('mv', relative, renamed)
            (root / origin.MANIFEST).write_text('0' * 64 + '  ' + renamed + '\n')
            git('add', '.')
            git('-c', 'user.name=Test', '-c', 'user.email=test@example.invalid', 'commit', '-qm', 'rename')
            record = origin.generate(root, commit)['files'][0]
            self.assertIsNone(record['import_sha256'])
            self.assertEqual(record['renamed_from'], relative)
            self.assertEqual(len(record['change_commits']), 2)


    def test_confidence_and_marker_counts(self):
        kind, markers, confidence = origin.classify(
            '+// Folia - one // Folia - two\n+io.papermc.paper.threadedregions.TickRegions x;\n',
            'net/Test.java.patch')
        self.assertEqual((kind, markers, confidence), ('folia', {'folia': 2}, 'high'))
        self.assertEqual(origin.classify('+// Folia - x\n+io.canvasmc.canvas.Config c;\n',
                                         'Test.java.patch')[2], 'low')

    @unittest.skipUnless(subprocess.run(['git', '-C', str(Path(__file__).resolve().parents[1]), 'cat-file', '-e',
                                         origin.IMPORT_COMMIT + '^{commit}'], capture_output=True).returncode == 0,
                         'the baseline import commit is not in this clone (shallow checkout)')
    def test_repository_check(self):
        script = Path(__file__).with_name('engine_baseline_origin.py')
        result = subprocess.run(['python3', str(script), '--check'], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_check_ignores_change_commits(self):
        doc = {'files': [{'path': 'a', 'change_commits': ['abc']}]}
        other = {'files': [{'path': 'a', 'change_commits': ['abc', 'def']}]}
        self.assertEqual(origin.comparable(json.dumps(doc)), origin.comparable(json.dumps(other)))
        other['files'][0]['path'] = 'b'
        self.assertNotEqual(origin.comparable(json.dumps(doc)), origin.comparable(json.dumps(other)))
