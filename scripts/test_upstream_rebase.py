"""Synthetic offline source rebase and apply tests."""
import json
from pathlib import Path
import tempfile
import subprocess
import sys
import unittest

import upstream_rebase as rebase


class RebaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.old, self.new, self.ours = [self.root / n for n in ('old', 'new', 'ours')]
        for tree in (self.old, self.new, self.ours):
            tree.mkdir()
        self.records = []

    def file(self, name, base, ours, theirs, origin='folia'):
        relative = 'sourbycraft-server/src/minecraft/java/' + name
        for tree, content in ((self.old, base), (self.ours, ours), (self.new, theirs)):
            if content is not None:
                path = tree / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content)
        self.records.append({'path': rebase.BASELINE_ROOTS[0] + '/' + name + '.patch',
                             'origin_class': origin})
        return relative

    def plan(self):
        return rebase.make_plan(self.ours, self.old, self.new, self.records, rebase.DEFAULT_RENAMES)

    def test_classes_and_rename(self):
        self.file('Same.java', 'io.canvasmc.canvas.A\n', 'dev.iyanz.aurora.engine.A\n', 'io.canvasmc.canvas.B\n')
        self.file('Clean.java', 'one\ntwo\nthree\nfour\nfive\n', 'ONE\ntwo\nthree\nfour\nfive\n', 'one\ntwo\nthree\nfour\nFIVE\n')
        self.file('Conflict.java', 'base\n', 'ours\n', 'theirs\n')
        self.file('Gone.java', 'gone\n', 'gone\n', None)
        self.file('Unknown.java', 'x\n', 'x\n', 'y\n', 'unknown')
        self.file('Missing.java', None, 'x\n', 'x\n')
        added = self.new / 'sourbycraft-server/src/minecraft/java/New.java'
        added.write_text('new\n')
        entries = {Path(e['path']).name: e for e in self.plan()['files']}
        self.assertEqual(entries['Same.java']['classification'], 'VERBATIM')
        self.assertEqual(entries['Same.java']['content'], 'dev.iyanz.aurora.engine.B\n')
        self.assertEqual(entries['Clean.java']['classification'], 'MERGE3_CLEAN')
        self.assertEqual(entries['Clean.java']['content'], 'ONE\ntwo\nthree\nfour\nFIVE\n')
        for name in ('Conflict.java', 'Unknown.java', 'Missing.java'):
            self.assertEqual(entries[name]['classification'], 'MANUAL')
        self.assertEqual(entries['Gone.java']['classification'], 'DELETED')
        self.assertEqual(entries['New.java']['classification'], 'ADDED')
        self.assertEqual(self.plan(), self.plan())

    def test_apply_refusal_write_delete_and_stale(self):
        relative = self.file('A.java', 'a\n', 'a\n', 'b\n')
        gone = self.file('Gone.java', 'x\n', 'x\n', None)
        plan = self.plan()
        with self.assertRaises(ValueError):
            rebase.apply_plan(plan, self.ours, False)
        self.assertEqual((self.ours / relative).read_text(), 'a\n')
        rebase.apply_plan(plan, self.ours, True)
        self.assertEqual((self.ours / relative).read_text(), 'b\n')
        self.assertFalse((self.ours / gone).exists())
        with self.assertRaises(ValueError):
            rebase.apply_plan(plan, self.ours, True)

    def test_apply_rejects_escape_patch_and_symlink(self):
        relative = self.file('A.java', 'a', 'a', 'b')
        plan = self.plan()
        for bad in ('../escape', '/tmp/escape', 'features/X.java', 'X.patch', 'build-data/engine-baseline.sha256'):
            plan['files'][0]['path'] = bad
            with self.subTest(path=bad), self.assertRaises(ValueError):
                rebase.apply_plan(plan, self.ours, True)
        plan['files'][0]['path'] = relative
        (self.ours / relative).unlink()
        (self.ours / relative).symlink_to(self.old / relative)
        with self.assertRaises(ValueError):
            rebase.apply_plan(plan, self.ours, True)

    def test_custom_ordered_map(self):
        mapping = self.root / 'map.json'
        mapping.write_text(json.dumps([['a', 'b'], ['b', 'c']]))
        self.assertEqual(rebase.transform('a', rebase.load_renames(mapping)), 'c')

    def test_package_path_rename_and_standalone_tree(self):
        relative = self.file('dev/iyanz/aurora/engine/A.java', None,
                             'dev.iyanz.aurora.engine.A\n', None, 'canvas')
        for tree, value in ((self.old, 'A'), (self.new, 'B')):
            path = tree / 'io/canvasmc/canvas/A.java'
            path.parent.mkdir(parents=True)
            path.write_text('io.canvasmc.canvas.' + value + '\n')
        entry = self.plan()['files'][0]
        self.assertEqual(entry['path'], relative)
        self.assertEqual(entry['classification'], 'VERBATIM')
        self.assertEqual(entry['content'], 'dev.iyanz.aurora.engine.B\n')

    def test_cli_apply_preview_and_yes(self):
        relative = self.file('A.java', 'a\n', 'a\n', 'b\n')
        plan_path = self.root / 'plan.json'
        plan_path.write_text(json.dumps(self.plan()))
        command = [sys.executable, str(Path(rebase.__file__)), 'apply',
                   '--plan', str(plan_path), '--target', str(self.ours)]
        preview = subprocess.run(command, capture_output=True, text=True)
        self.assertNotEqual(preview.returncode, 0)
        self.assertIn('would write/delete:', preview.stderr)
        self.assertIn(relative, preview.stderr)
        applied = subprocess.run(command + ['--yes'], capture_output=True, text=True)
        self.assertEqual(applied.returncode, 0, applied.stderr)
        self.assertEqual((self.ours / relative).read_text(), 'b\n')
