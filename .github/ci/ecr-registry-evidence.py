#!/usr/bin/env python3
"""Observe only registry test containers; never retain environment or request headers."""
import datetime
import json
import pathlib
import re
import signal
import subprocess
import threading

OUTPUT = pathlib.Path('ecr-evidence')
OUTPUT.mkdir(exist_ok=True)
STOP = threading.Event()
LOCK = threading.Lock()
CHILDREN = []


def record(kind, **fields):
    row = dict(atUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(), kind=kind, **fields)
    with LOCK:
        with (OUTPUT / 'registry-startup.jsonl').open('a') as stream:
            stream.write(json.dumps(row) + '\n')


def observe(container_id, name):
    child = subprocess.Popen(['docker', 'logs', '--timestamps', '--follow', container_id],
                             stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    CHILDREN.append(child)
    for line in child.stdout:
        # Export only the listening timestamp/port, not registry HTTP requests, tokens or environment.
        match = re.search(r'msg="listening on (?:\[::\]|0\.0\.0\.0|127\.0\.0\.1):([0-9]+)"', line)
        if match:
            record('registry-listening', id=container_id, name=name,
                   dockerTimestamp=line.split(' ', 1)[0], port=int(match.group(1)))
    record('registry-log-stream-ended', id=container_id, exitCode=child.wait())


def stop(signum, frame):
    STOP.set()
    for child in list(CHILDREN):
        if child.poll() is None:
            child.terminate()


signal.signal(signal.SIGTERM, stop)
signal.signal(signal.SIGINT, stop)
events = subprocess.Popen(['docker', 'events', '--filter', 'type=container', '--filter', 'event=start',
                           '--format', '{{json .}}'], stdout=subprocess.PIPE, text=True)
CHILDREN.append(events)
record('observer-started')
try:
    for line in events.stdout:
        event = json.loads(line)
        actor = event.get('Actor', {})
        name = actor.get('Attributes', {}).get('name', '')
        container_id = actor.get('ID', '')
        if name.startswith('floci-') and name.endswith('-ecr-registry') and re.fullmatch('[a-f0-9]{64}', container_id):
            record('registry-container-start', id=container_id, name=name, dockerTimeNano=event.get('timeNano'))
            threading.Thread(target=observe, args=(container_id, name), daemon=True).start()
finally:
    stop(None, None)
    for child in list(CHILDREN):
        try:
            child.wait(timeout=5)
        except subprocess.TimeoutExpired:
            child.kill()
            child.wait()
    record('observer-stopped')
