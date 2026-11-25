#!/usr/bin/env python3
"""Deterministic group-by-profile test partitioner for CI sharding.

Usage: partition.sh <all-classes.txt> <shard-total> <shard-index>

<all-classes.txt> holds one test class per line in surefire includesFile path
format (io/github/.../FooTest, no extension). Classes sharing a @TestProfile are
assigned to the SAME shard atomically, except known slow classes: per-shard Quarkus augmentation cost is
(distinct profiles in shard) x ~5.4s, so splitting a profile group pays its
augmentation once per shard it touches. Profile-less classes fill the remainder.
Recorded CI timings weight classes while keeping their profile groups together.
Explicit SLOW_CLASSES overrides are separate groups when their run time would
otherwise be invisible to a partition that balances class counts. These are
separate groups even when they share a profile: saving one augmentation must
not force minutes of Docker work onto the same shard.

Deterministic: sorted inputs, greedy least-loaded bin-packing, no RNG/timestamps.
Exit 2 on bad arguments or an unreadable input file.
"""
import json
import math
from pathlib import Path
import os
import re
import sys

AUGMENTATION_WEIGHT = 10  # one augmentation ~ 5.4s ~ 10 average test classes

# Classes that take far longer than the suite's average, keyed by class name so package renames
# retain their weight, as {"ClassName": seconds}. None today: the EMR runner (651s on GitHub's
# runners on 2026-11-12) and the Hive steps now run only in the nightly checks. The tests pass
# their own through PARTITION_SLOW_CLASSES, a JSON object of the same shape.
SLOW_CLASSES = json.loads(os.environ.get('PARTITION_SLOW_CLASSES', '{}'))
SECONDS_PER_CLASS = 1.2  # the suite's mean: 4212s over 3451 classes on 2026-11-09

def durations():
    path = Path(__file__).with_name('test-durations.json')
    with path.open(encoding='utf-8') as source:
        seconds = json.load(source)['seconds']
    if not isinstance(seconds, dict) or any(
        not isinstance(name, str) or isinstance(value, bool)
        or not isinstance(value, (int, float)) or not math.isfinite(value) or value <= 0
        for name, value in seconds.items()
    ):
        raise ValueError('test durations must map class paths to positive finite seconds')
    return seconds


def weight(cls, seconds):
    """Recorded timings retain profile groups; explicit slow overrides may split them."""
    override = SLOW_CLASSES.get(cls.rsplit('/', 1)[-1])
    if override is not None:
        return round(override / SECONDS_PER_CLASS)
    recorded = seconds.get(cls)
    return 1 if recorded is None else recorded / SECONDS_PER_CLASS

def warn_of_stale_slow_classes(classes):
    """A renamed class would quietly lose its weight, so name it where the run's annotations show it."""
    known = {c.rsplit('/', 1)[-1] for c in classes}
    for name in sorted(set(SLOW_CLASSES) - known):
        print(f'::warning title=partition.sh::stale SLOW_CLASSES key: no test class named {name}', file=sys.stderr)

def profile_groups(test_root):
    """Map class-path -> profile key from @TestProfile usages in src/test."""
    ann = re.compile(r'@TestProfile\(\s*([A-Za-z0-9_.]+?)(?:\.class)?\s*\)')
    mapping = {}
    for dirpath, _, files in sorted(os.walk(test_root)):
        for f in sorted(files):
            if not f.endswith('.java'):
                continue
            path = os.path.join(dirpath, f)
            try:
                src = open(path, encoding='utf-8', errors='replace').read()
            except OSError:
                continue
            m = ann.search(src)
            if not m:
                continue
            cls = os.path.relpath(path, test_root)[:-len('.java')]
            # Key on the referenced profile token; identical tokens merge groups,
            # which is always safe (merging groups never splits an augmentation).
            mapping[cls] = m.group(1)
    return mapping

def main():
    if len(sys.argv) != 4:
        sys.exit('usage: partition.sh <all-classes.txt> <shard-total> <shard-index>')
    try:
        classes = [l.strip() for l in open(sys.argv[1]) if l.strip()]
        total, index = int(sys.argv[2]), int(sys.argv[3])
        seconds = durations()
    except (OSError, ValueError, KeyError) as e:
        sys.exit(f'partition.sh: {e}')
    if not (1 <= index <= total):
        sys.exit(f'partition.sh: shard index {index} outside 1..{total}')

    warn_of_stale_slow_classes(classes)
    prof = profile_groups('src/test/java')
    groups = {}
    plain = []
    for c in sorted(set(classes)):
        key = prof.get(c)
        if key is None:
            plain.append(c)
        else:
            if c.rsplit('/', 1)[-1] in SLOW_CLASSES and weight(c, seconds) > 1:
                key = (key, c)
            else:
                key = (key, '')
            groups.setdefault(key, []).append(c)

    load = [0] * total
    shard_of = {}
    # Heaviest first; ties broken by name for determinism.
    for key, members in sorted(groups.items(), key=lambda kv: (-sum(weight(c, seconds) for c in kv[1]), kv[0])):
        target = min(range(total), key=lambda s: (load[s], s))
        load[target] += AUGMENTATION_WEIGHT + sum(weight(c, seconds) for c in members)
        for c in members:
            shard_of[c] = target
    for c in sorted(plain, key=lambda c: (-weight(c, seconds), c)):
        target = min(range(total), key=lambda s: (load[s], s))
        load[target] += weight(c, seconds)
        shard_of[c] = target

    out = sorted(c for c, s in shard_of.items() if s == index - 1)
    if not out:
        sys.exit(f'partition.sh: shard {index}/{total} would be empty')
    print('\n'.join(out))

if __name__ == '__main__':
    main()
