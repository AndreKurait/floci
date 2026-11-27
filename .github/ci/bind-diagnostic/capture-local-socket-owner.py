#!/usr/bin/env python3
"""Capture port-scoped Linux TCP evidence without reading process arguments."""
import argparse
import datetime
import json
import os
import pathlib
import socket
import sys
import time


def endpoint(value, family):
    address, port = value.split(":")
    packed = b"".join(int(address[i:i + 8], 16).to_bytes(4, sys.byteorder)
                      for i in range(0, len(address), 8))
    return {"address": socket.inet_ntop(family, packed), "port": int(port, 16)}


def capture(port, proc=pathlib.Path('/proc'), budget_seconds=2.0):
    started = time.monotonic()
    result = {"atUtc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "port": port, "sockets": [], "owners": [], "limitations": [],
              "ownerUid": os.getuid(), "scanComplete": True}

    def problem(message):
        result['scanComplete'] = False
        result['limitations'].append(message)

    for name, family in [('tcp', socket.AF_INET), ('tcp6', socket.AF_INET6)]:
        try:
            lines = (proc / 'net' / name).read_text().splitlines()[1:]
        except OSError as exc:
            problem(f'{name} unavailable: {type(exc).__name__}')
            continue
        for line in lines:
            fields = line.split()
            try:
                local = endpoint(fields[1], family)
                if local['port'] != port:
                    continue
                result['sockets'].append({'table': name, 'local': local,
                                          'remote': endpoint(fields[2], family),
                                          'stateHex': fields[3], 'uid': int(fields[7]),
                                          'inode': fields[9]})
            except (ValueError, IndexError, OSError):
                problem(f'{name} malformed row; raw contents omitted')
    inodes = {item['inode'] for item in result['sockets'] if item['inode'] != '0'}
    if inodes:
        try:
            processes = sorted((p for p in proc.iterdir() if p.name.isdigit()), key=lambda p: int(p.name))
        except OSError as exc:
            problem(f'process inventory unavailable: {type(exc).__name__}')
            processes = []
        for process in processes:
            if time.monotonic() - started > budget_seconds:
                problem('owner scan time budget exceeded')
                break
            try:
                if process.stat().st_uid != os.getuid():
                    continue
                # stat fields following the final ')' begin at field 3. Field 22 is starttime.
                identity = process.joinpath('stat').read_text().rsplit(')', 1)[1].split()[19]
                with os.scandir(process / 'fd') as descriptors:
                    for descriptor in descriptors:
                        if time.monotonic() - started > budget_seconds:
                            problem('descriptor scan time budget exceeded')
                            break
                        try:
                            target = os.readlink(descriptor.path)
                        except FileNotFoundError:
                            continue  # Concurrent descriptor close is normal, snapshot is non-atomic.
                        if target.startswith('socket:[') and target[8:-1] in inodes:
                            result['owners'].append({'pid': int(process.name),
                                                     'startTicks': identity,
                                                     'fd': int(descriptor.name),
                                                     'inode': target[8:-1]})
                after = process.joinpath('stat').read_text().rsplit(')', 1)[1].split()[19]
                if after != identity:
                    result['owners'] = [o for o in result['owners'] if o['pid'] != int(process.name)]
                    problem('process identity changed during scan')
            except FileNotFoundError:
                continue  # A process may exit between table and descriptor reads.
            except (OSError, IndexError) as exc:
                problem(f'PID {process.name} owner unavailable: {type(exc).__name__}')
    result['elapsedSeconds'] = time.monotonic() - started
    result['limits'] = [
        'Non-atomic observation in the collector network namespace; owners may exit or close descriptors.',
        'Only same-UID process descriptors are inspected; no arguments, environment, or unrelated paths are emitted.',
        'TCP tables may omit bound-but-not-listening sockets. Empty results do not prove port availability.',
        'Run immediately at the bind failure; a later snapshot cannot reconstruct former ownership.'
    ]
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, required=True)
    parser.add_argument('--output', type=pathlib.Path, required=True)
    args = parser.parse_args()
    if not 0 < args.port < 65536:
        parser.error('port must be between 1 and 65535')
    data = capture(args.port)
    # Exclusive private creation prevents replacement of existing evidence or symlink targets.
    fd = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as stream:
        json.dump(data, stream, indent=2)
        stream.write('\n')


if __name__ == '__main__':
    main()
