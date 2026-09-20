#!/usr/bin/env python3
"""Verify a slim JAR boots from an empty cache, then with launcher networking disabled."""
import argparse
import json
import shutil
import subprocess
from pathlib import Path

from private_toolchain import digest
from run_baseline import Server


def verify(jar, output, port, timeout, java):
    jar = Path(jar).resolve(strict=True)
    output = Path(output).resolve()
    # Never let a warm cache silently turn the cold-bootstrap check into a different test.
    output.mkdir(parents=True, exist_ok=False)
    (output / 'eula.txt').write_text('eula=true\n')
    (output / 'server.properties').write_text(
        f'server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\n'
        'level-type=minecraft:flat\nspawn-protection=0\n')
    config = output / 'sourbycraft_config'
    config.mkdir()
    (config / 'sourbycraft_global_config.toml').write_text(
        '[viaversion]\nauto-provision=false\n[misc.auto_update]\nenabled=false\n')
    report = {'jar': str(jar), 'sha256': digest(jar), 'phases': [], 'passed': False}
    try:
        for phase, extra in [('cold', []), ('offline', ['-Dsourbyclip.offline=true'])]:
            command = [java, '-Xms512M', '-Xmx2G', *extra,
                       f'-XX:StartFlightRecording=filename={phase}.jfr,settings=profile,dumponexit=true',
                       '-jar', str(jar), '--nogui']
            server = Server(command, output)
            result = {'phase': phase, 'passed': False}
            report['phases'].append(result)
            try:
                result['startup_seconds'] = server.await_ready(timeout)
                server.send('version', 'stop')
                result['exit_code'] = server.process.wait(timeout=60)
                if result['exit_code'] != 0:
                    raise RuntimeError(f'{phase}: server exited {result["exit_code"]}')
                result['passed'] = True
                print(f'PASS {phase}: Done in {result["startup_seconds"]:.1f}s; clean exit', flush=True)
            except Exception as error:
                result['error'] = str(error)
                raise
            finally:
                server.close()
                shutil.copy2(output / 'server.log', output / f'{phase}.log')
        report['passed'] = True
    finally:
        (output / 'bootstrap-report.json').write_text(json.dumps(report, indent=2) + '\n')
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('jar', type=Path)
    parser.add_argument('--output', type=Path, required=True, help='new directory; existing paths are refused')
    parser.add_argument('--port', type=int, default=25578)
    parser.add_argument('--timeout', type=int, default=360)
    parser.add_argument('--java', default=shutil.which('java'))
    args = parser.parse_args()
    if not args.java:
        parser.error('Java 25 is required on PATH or via --java')
    verify(args.jar, args.output, args.port, args.timeout, args.java)


if __name__ == '__main__':
    main()
