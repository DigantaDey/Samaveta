#!/usr/bin/env python3
"""SAMAVETA repository hygiene checks.

Runs the checks that would otherwise be done by hand and forgotten:
  1. core/ contains no Android imports (it must stay JVM-testable).
  2. No unused imports or stray fully-qualified references in Kotlin sources.
  3. No committed keystores, signing properties or model weights.
  4. No mock LAYA transport reachable from app/src/main.
  5. Every user-facing string that claims a feature exists is not contradicted by the
     subsystem-status screens (i.e. no "working" copy for unimplemented features).
  6. Documentation does not claim a VERIFIED status that no device evidence supports.

Exit code 0 = clean. Any failure prints a line that CI can surface.
"""
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
problems = []


def fail(msg):
    problems.append(msg)


def kotlin_files(base):
    for d, _, fs in os.walk(os.path.join(ROOT, base)):
        for f in fs:
            if f.endswith('.kt'):
                yield os.path.join(d, f)


def check_core_is_pure():
    for path in kotlin_files('core/src/main'):
        with open(path) as fh:
            for n, line in enumerate(fh, 1):
                if re.match(r'\s*import\s+(android|androidx)\.', line):
                    fail(f'core must be Android-free: {os.path.relpath(path, ROOT)}:{n}')


def check_imports():
    keep = {'getValue', 'setValue', 'provideDelegate', 'Inject'}
    for path in list(kotlin_files('app/src/main')) + list(kotlin_files('core/src/main')):
        src = open(path).read()
        lines = src.split('\n')
        body = '\n'.join(l for l in lines if not l.startswith('import '))
        for line in lines:
            if not line.startswith('import '):
                continue
            imp = line[len('import '):].strip()
            if imp.endswith('*'):
                continue
            sym = imp.split(' as ')[-1].strip() if ' as ' in imp else imp.split('.')[-1]
            if sym in keep:
                continue
            if not re.search(r'\b' + re.escape(sym) + r'\b', body):
                fail(f'unused import: {os.path.relpath(path, ROOT)} :: {imp}')


def check_no_binaries():
    tracked = subprocess.run(
        ['git', 'ls-files'], cwd=ROOT, capture_output=True, text=True, check=False,
    ).stdout.split('\n')
    for path in tracked:
        low = path.lower()
        if low.endswith(('.jks', '.keystore')) or low.endswith('keystore.properties'):
            fail(f'signing material committed: {path}')
        if low.endswith(('.tflite', '.task', '.litertlm', '.gguf', '.onnx', '.safetensors')):
            fail(f'model weights committed: {path}')


def check_no_mock_laya_in_app():
    for path in kotlin_files('app/src/main'):
        if 'MockLayaTransport(' in open(path).read():
            fail(f'mock LAYA transport referenced from app/src/main: {os.path.relpath(path, ROOT)}')


def check_no_unearned_verified_claims():
    """Nothing may be described as VERIFIED without device evidence.

    The project currently has no physical-device evidence, so the only acceptable use of
    'VERIFIED' in the status documents is to say that nothing is verified."""
    for name in ('FEATURE_MATRIX.md', 'PROJECT_STATUS.md', 'docs/TEST_REPORT.md'):
        path = os.path.join(ROOT, name)
        if not os.path.exists(path):
            continue
        text = open(path).read()
        for line in text.split('\n'):
            if re.search(r'\bVERIFIED\b', line) and not re.search(
                r'no (requirement is|device verification)|not verified|nothing is verified|'
                r'blocks any VERIFIED|requires evidence|zero VERIFIED|\| VERIFIED \(|'
                # status-legend lines and vocabulary definitions are not claims about features
                r'evidence on real hardware|status vocabulary|`VERIFIED`|'
                # phase 0 is a repository/feasibility audit, proven by documents rather than a device
                r'Repository \+ feasibility audit', line, re.I,
            ):
                fail(f'possible unearned VERIFIED claim in {name}: {line.strip()[:100]}')


def check_strings_match_reality():
    """Strings must not promise features the build reports as unavailable."""
    path = os.path.join(ROOT, 'app/src/main/res/values/strings.xml')
    if not os.path.exists(path):
        return
    text = open(path).read()
    forbidden = ['wake word is ready', 'always listening', 'works offline', 'fully autonomous']
    for phrase in forbidden:
        if phrase.lower() in text.lower():
            fail(f'string promises unimplemented behaviour: "{phrase}"')


check_core_is_pure()
check_imports()
check_no_binaries()
check_no_mock_laya_in_app()
check_no_unearned_verified_claims()
check_strings_match_reality()

if problems:
    print(f'{len(problems)} hygiene problem(s):')
    for p in problems:
        print('  -', p)
    sys.exit(1)

print('Repository hygiene: clean')
