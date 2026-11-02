#!/usr/bin/env python3
"""Fork-only measurement of unchanged CI test selection and passive startup spans."""
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import resource
import time
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path.cwd()
OUT = ROOT / 'startup-evidence'
TOOLS = ROOT / 'tools/startup-full'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write(name, value):
    OUT.mkdir(exist_ok=True)
    (OUT / name).write_text(json.dumps(value, indent=2) + '\n')


def phase(name, command):
    """Measure the command process tree, including waited-for JVM children."""
    if not re.fullmatch(r'[a-z-]+', name) or not command:
        raise ValueError('Expected a phase name and command argv')
    before = resource.getrusage(resource.RUSAGE_CHILDREN)
    start = time.monotonic_ns()
    started = time.time_ns()
    OUT.mkdir(exist_ok=True)
    with (OUT / ('phase-' + name + '.log')).open('w') as log:
        result = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        for line in result.stdout:
            log.write(line)
            print(line, end='', flush=True)
        result.wait()
    end = time.monotonic_ns()
    after = resource.getrusage(resource.RUSAGE_CHILDREN)
    cpu = (after.ru_utime - before.ru_utime) + (after.ru_stime - before.ru_stime)
    wall = (end - start) / 1e9
    write('phase-' + name + '.json', {
        'phase': name, 'command': command, 'exitCode': result.returncode,
        'startedEpochNanos': started, 'startNanos': start, 'endNanos': end,
        'wallSeconds': wall, 'userCpuSeconds': after.ru_utime - before.ru_utime,
        'systemCpuSeconds': after.ru_stime - before.ru_stime,
        'cpuSecondsPerWallSecond': cpu / wall if wall else None,
        'maxChildRssKiB': after.ru_maxrss,
        'limits': 'CPU includes waited-for child processes. Background image prefetch is separate. '
                  'RSS is largest child high-water mark, not simultaneous tree peak. '
                  'Test phase includes discovery, augmentation, resources, application startup and tests; '
                  'recorder spans separate overlapping internal phases.'})
    return result.returncode


def aggregate(folder):
    """Require both complete five-shard inventories and identical executed cases."""
    errors = []
    modes = {}
    expected = None
    inputs_seen = None
    for mode in ('plain', 'recorded'):
        union = set()
        cases = []
        for shard in range(1, 6):
            job = folder / ('startup-' + mode + '-' + str(shard))
            selected_lines = (job / 'shard.txt').read_text().splitlines()
            selected = set(selected_lines)
            all_lines = (job / 'all-classes.txt').read_text().splitlines()
            inventory = set(all_lines)
            identity = json.loads((job / 'startup-evidence/inputs.json').read_text())
            identity = {'head': identity['head'], 'files': identity['files']}
            if expected is None:
                expected = inventory
                inputs_seen = identity
            if inventory != expected or identity != inputs_seen:
                errors.append('Source or full inventory differs: ' + job.name)
            if len(selected_lines) != len(selected) or len(all_lines) != len(inventory):
                errors.append('Duplicate manifest entry: ' + job.name)
            if union & selected:
                errors.append('Overlapping shards: ' + job.name)
            union.update(selected)
            inventory_proof = json.loads((job / 'startup-evidence/source-compiled-inventory.json').read_text())
            if not inventory_proof['equal'] or inventory_proof['sourceSha256'] != inventory_proof['compiledSha256']:
                errors.append('Source-to-compiled inventory check failed: ' + job.name)
            if (inventory_proof['sourceCount'] != len(inventory)
                    or inventory_proof['compiledCount'] != len(inventory)
                    or inventory_proof['sourceSha256'] != digest(job / 'all-classes.txt')):
                errors.append('Source-to-compiled inventory count differs: ' + job.name)
            for phase_name in ('compile', 'tests'):
                receipt = json.loads((job / ('startup-evidence/phase-' + phase_name + '.json')).read_text())
                if receipt['exitCode'] != 0:
                    errors.append('Command phase failed: ' + job.name + ':' + phase_name)
            proof = json.loads((job / 'startup-evidence/verification.json').read_text())
            if proof['errors'] or not proof['compiledUnchanged']:
                errors.append('Failed job verification: ' + job.name)
            cases.extend(json.loads((job / 'startup-evidence/test-cases.json').read_text()))
        if union != expected:
            errors.append('Incomplete mode union: ' + mode)
        modes[mode] = {'classes': len(union), 'cases': sorted(
            (c['class'], c['name'], c['skipped']) for c in cases)}
    if modes['plain']['cases'] != modes['recorded']['cases']:
        errors.append('Executed case identities or skips differ between modes')
    write('aggregate.json', {'modes': modes, 'errors': errors,
                            'limits': 'Independent runners and metrics enabled in both modes; '
                                      'not a causal optimization or normal-CI overhead result.'})
    if errors:
        raise AssertionError(errors)


def compiled():
    return {str(p.relative_to(ROOT)): digest(p)
            for folder in ['target/classes', 'target/test-classes']
            for p in sorted((ROOT / folder).rglob('*.class'))}


def inputs():
    paths = subprocess.check_output(['git', 'ls-files', 'src', 'pom.xml', '.github/ci'], text=True).splitlines()
    write('inputs.json', {'head': subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
                         'files': {p: digest(ROOT / p) for p in paths},
                         'cpu': [line for line in Path('/proc/cpuinfo').read_text().splitlines()
                                 if line.startswith(('model name', 'cpu cores', 'siblings'))],
                         'cpus': os.cpu_count(), 'platform': platform.platform(),
                         'runner': {key: os.environ.get(key) for key in
                                    ['RUNNER_OS', 'RUNNER_ARCH', 'RUNNER_ENVIRONMENT', 'GITHUB_RUN_ID',
                                     'GITHUB_RUN_ATTEMPT', 'STARTUP_CACHE_MATCHED_KEY', 'STARTUP_CACHE_HIT']},
                         'java': subprocess.check_output(['java', '-version'], stderr=subprocess.STDOUT, text=True)})
    (OUT / 'original-pom.xml').write_bytes((ROOT / 'pom.xml').read_bytes())


def inventory():
    expected = (ROOT / 'all-classes.txt').read_text().splitlines()
    actual = sorted(str(p.relative_to(ROOT / 'target/test-classes'))[:-6]
                    for p in (ROOT / 'target/test-classes').rglob('*.class')
                    if '$' not in p.name and (p.stem.endswith(('Test', 'Tests', 'TestCase'))
                                             or p.stem.startswith('Test')))
    equal = expected == actual
    write('source-compiled-inventory.json', {
        'equal': equal, 'sourceCount': len(expected), 'compiledCount': len(actual),
        'sourceSha256': hashlib.sha256(('\n'.join(expected) + '\n').encode()).hexdigest(),
        'compiledSha256': hashlib.sha256(('\n'.join(actual) + '\n').encode()).hexdigest()})
    assert equal, 'Source selector differs from compiled selector'


def configure(mode):
    assert mode in ('plain', 'recorded')
    original = (OUT / 'original-pom.xml').read_text()
    assert (ROOT / 'pom.xml').read_text() == original
    needle = '<argLine>@{argLine} -Xmx6g</argLine>'
    assert original.count(needle) == 1
    args = ' -Dquarkus.builder.metrics.enabled=true'
    if mode == 'recorded':
        args += ' -javaagent:' + str(OUT / 'startup-agent.jar') + '=' + str(OUT / 'trace')
    (ROOT / 'pom.xml').write_text(original.replace(needle, '<argLine>@{argLine} -Xmx6g' + args + '</argLine>'))
    write('configuration.json', {'mode': mode, 'pom': digest(ROOT / 'pom.xml'),
                                 'beforeCompilation': True, 'metricsEnabledBothModes': True})


def prepare(mode):
    assert json.loads((OUT / 'configuration.json').read_text())['pom'] == digest(ROOT / 'pom.xml')
    write('compiled-before.json', compiled())
    jars = [Path.home() / '.m2/repository/org/ow2/asm' / a / '9.10.1' / (a + '-9.10.1.jar')
            for a in ['asm', 'asm-commons']]
    assert all(p.is_file() for p in jars), 'Expected pinned ASM dependencies from project compilation'
    classes = OUT / 'agent-classes'
    classes.mkdir()
    subprocess.run(['javac', '-cp', os.pathsep.join(map(str, jars)), '-d', str(classes),
                    str(TOOLS / 'StartupAgent.java'), str(TOOLS / 'StartupRecorder.java')], check=True)
    manifest = OUT / 'agent.mf'
    manifest.write_text('Premain-Class: StartupAgent\nClass-Path: ' + ' '.join(p.as_uri() for p in jars) + '\n')
    jar = OUT / 'startup-agent.jar'
    subprocess.run(['jar', 'cfm', str(jar), str(manifest), '-C', str(classes), '.'], check=True)
    write('preparation.json', {'mode': mode, 'pom': digest(ROOT / 'pom.xml'), 'agent': digest(jar),
                              'source': {p.name: digest(p) for p in TOOLS.glob('*.java')},
                              'originalHeap': '6g', 'metricsEnabledBothModes': True})


def verify(mode):
    errors = []
    if json.loads((OUT / 'configuration.json').read_text())['pom'] != digest(ROOT / 'pom.xml'):
        errors.append('POM changed after configuration before compilation')
    before = json.loads((OUT / 'compiled-before.json').read_text())
    after = compiled()
    if before != after:
        errors.append('Compiled classes changed during tests')
    manifest = {line.replace('/', '.') for line in (ROOT / 'shard.txt').read_text().splitlines()}
    reports = sorted((ROOT / 'target/surefire-reports').glob('TEST-*.xml'))
    actual = set()
    counts = dict(tests=0, failures=0, errors=0, skipped=0)
    cases = []
    report_hashes = {}
    for report in reports:
        suite = ET.parse(report).getroot()
        actual.add(suite.attrib['name'])
        report_hashes[report.name] = digest(report)
        for key in counts:
            counts[key] += int(suite.get(key, '0'))
        cases.extend({'class': c.get('classname'), 'name': c.get('name'),
                      'skipped': c.find('skipped') is not None} for c in suite.findall('testcase'))
    # Existing selector includes a source utility; it has no JUnit annotations.
    utility = 'io.github.hectorvent.floci.services.rekognition.TestImages'
    utility_source = ROOT / 'src/test/java' / (utility.replace('.', '/') + '.java')
    assert utility_source.is_file() and not re.search(r'@(?:Test|ParameterizedTest|RepeatedTest|TestFactory)\b', utility_source.read_text())
    missing = sorted(manifest - actual - {utility})
    unexpected = sorted(actual - manifest)
    if missing or unexpected or not reports:
        errors.append('Test report classes differ from selector')
    if counts['failures'] or counts['errors']:
        errors.append('Actual tests failed')
    trace = {}
    if mode == 'recorded':
        path = OUT / 'trace/events.tsv'
        rows = [line.split('\t') for line in path.read_text().splitlines()] if path.exists() else []
        starts = {row[1] for row in rows if row[0] == 'enter'}
        exits = [row for row in rows if row[0] == 'exit']
        if not starts or starts != {row[1] for row in exits} or len(exits) != len(starts):
            errors.append('Unbalanced or absent recording spans')
        if ['shutdown', '0'] not in rows:
            errors.append('Missing balanced recording shutdown')
        if any(row[0] == 'unsupported-loader-class' for row in rows):
            errors.append('Unsupported framework class')
        dumps = [row for row in exits if row[3] == 'io/quarkus/builder/BuildMetrics.dumpTo']
        metrics = list((OUT / 'trace').glob('metrics-*.json'))
        if not dumps or len(metrics) != len(dumps) or any(row[8] != 'return' for row in dumps):
            errors.append('Incomplete per-profile build metrics')
        trace = {'spans': len(exits), 'metrics': len(metrics),
                 'methodCounts': {method: sum(row[3] == method for row in exits)
                                  for method in sorted({row[3] for row in exits})}}
    write('verification.json', {'mode': mode, 'counts': counts, 'reportHashes': report_hashes,
                                'missing': missing, 'unexpected': unexpected, 'trace': trace,
                                'compiledUnchanged': before == after, 'errors': errors,
                                'limits': 'Independent hosted runners may differ. No causal speedup, native, or AWS claim. Skips retained as reported.'})
    write('test-cases.json', cases)
    assert not errors, errors


if __name__ == '__main__':
    if sys.argv[1] == 'phase':
        sys.exit(phase(sys.argv[2], sys.argv[3:]))
    elif sys.argv[1] == 'aggregate':
        aggregate(Path(sys.argv[2]))
    elif sys.argv[1] == 'inputs':
        inputs()
    elif sys.argv[1] == 'inventory':
        inventory()
    elif sys.argv[1] == 'configure':
        configure(sys.argv[2])
    elif sys.argv[1] == 'prepare':
        prepare(sys.argv[2])
    elif sys.argv[1] == 'verify':
        verify(sys.argv[2])
    else:
        raise ValueError('Unknown operation')
