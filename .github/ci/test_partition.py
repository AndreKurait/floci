"""Exercise the CI partitioner's executable contract with independent fixtures."""
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


class PartitionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.script = self.root / 'partition.sh'
        shutil.copyfile(Path(__file__).with_name('partition.sh'), self.script)
        self.manifest = self.root / 'all-classes.txt'

    def prepare(self, profiles, seconds=None):
        self.profiles = profiles
        self.manifest.write_text('\n'.join(profiles) + '\n')
        (self.root / 'test-durations.json').write_text(json.dumps({'seconds': seconds or {}}))
        for name, profile in profiles.items():
            source = self.root / 'src/test/java' / (name + '.java')
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_text('' if profile is None else f'@TestProfile({profile}.class)')

    def command(self, total, index):
        return subprocess.run(
            [sys.executable, str(self.script), str(self.manifest), str(total), str(index)],
            cwd=self.root, capture_output=True, text=True, check=False,
        )

    def partition(self, total):
        shards = []
        for index in range(1, total + 1):
            result = self.command(total, index)
            self.assertEqual(0, result.returncode, result.stderr)
            shards.append(result.stdout.splitlines())
        flattened = [name for shard in shards for name in shard]
        self.assertCountEqual(self.profiles, flattened)
        self.assertEqual(len(set(flattened)), len(flattened))
        for profile in set(self.profiles.values()) - {None}:
            owners = {i for i, shard in enumerate(shards) for name in shard
                      if self.profiles[name] == profile}
            self.assertEqual(1, len(owners), profile)
        return shards

    def test_slow_profiles_and_plain_classes_use_duration_weights(self):
        profiles = {f'p/Fast{i}Test': 'Fast' for i in range(8)}
        profiles.update({'p/SlowATest': 'Slow', 'p/SlowBTest': 'Slow',
                         'p/PlainATest': None, 'p/PlainBTest': None})
        seconds = {'p/SlowATest': 150, 'p/SlowBTest': 150,
                   'p/PlainATest': 140, 'p/PlainBTest': 130}
        self.prepare(profiles, seconds)
        shards = self.partition(2)
        self.assertLessEqual(max(sum(seconds.get(name, 1) for name in shard)
                                 for shard in shards), 310)

    def test_input_order_does_not_change_complete_atomic_partition(self):
        profiles = {f'p/Example{i}Test': f'Profile{i // 3}' if i < 9 else None
                    for i in range(15)}
        self.prepare(profiles, {'p/Example1Test': 80, 'p/Example12Test': 60})
        expected = self.partition(3)
        self.manifest.write_text('\n'.join(reversed(profiles)) + '\n')
        self.assertEqual(expected, self.partition(3))

    def test_classes_without_timings_get_nonzero_weight(self):
        self.prepare({f'p/New{i}Test': None for i in range(12)}, {'p/RetiredTest': 100})
        self.assertEqual([4, 4, 4], [len(shard) for shard in self.partition(3)])

    def test_invalid_durations_fail_before_emitting_a_manifest(self):
        for value in (0, -1, True, float('nan'), '2'):
            with self.subTest(value=value):
                self.prepare({'p/ExampleTest': None}, {'p/ExampleTest': value})
                result = self.command(1, 1)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual('', result.stdout)
                self.assertIn('positive finite seconds', result.stderr)

    def test_empty_shard_is_an_error(self):
        self.prepare({'p/OnlyTest': None})
        result = self.command(2, 2)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('would be empty', result.stderr)


if __name__ == '__main__':
    unittest.main()
